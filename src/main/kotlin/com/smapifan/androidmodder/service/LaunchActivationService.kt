package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.ModDefinition
import java.util.Base64

/**
 * Manages per-launch activation tokens for the APK-injection mod path.
 *
 * ## How the injection cycle works
 *
 * Once a game APK has been patched by [ApkInjectionService] its
 * `Application` class contains a smali bootstrap that checks for an
 * *activation token* on **every** `Application.onCreate()`.
 *
 * The launcher writes two files to external storage before `am start`:
 * ```
 * /sdcard/Android/data/<pkg>/files/.launcher_session   ← existence marker
 * /sdcard/Android/data/<pkg>/files/mod_launcher.sh     ← patch + backup logic
 * ```
 *
 * The injected bootstrap finds `.launcher_session`, executes
 * `mod_launcher.sh` (which runs **as the app's own UID** via
 * `Runtime.exec`), then both files are deleted by the script itself.
 *
 * The script:
 * 1. Applies each [PatchInstruction] to the game's save files in
 *    `/data/data/<pkg>/` (accessible because it runs as the game).
 * 2. Backs up the patched save files to external storage so that
 *    [SaveBackupService] can restore them if the original APK is later
 *    reinstalled.
 * 3. Deletes itself and the `.launcher_session` token.
 *
 * **Without the token**: the bootstrap is a complete no-op – the game runs
 * identically to the unpatched original.
 *
 * @param shell               shell command executor
 * @param externalStorageRoot external storage root, default `/sdcard`
 */
class LaunchActivationService(
    private val shell: ShellExecutor = ShellExecutor(),
    val externalStorageRoot: String = "/sdcard"
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Write token + launcher script
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes the activation token and `mod_launcher.sh` for [packageName].
     *
     * After this call the next `am start` will cause the injected code to
     * execute the patch operations encoded in [instructions].
     *
     * @param packageName    game's Android package name
     * @param instructions   patch operations derived from active mods
     * @param deviceDataRoot root of the device's internal data directory
     *                       (default `/data`)
     * @return `true` if both files were written successfully
     */
    fun writeToken(
        packageName: String,
        instructions: List<PatchInstruction>,
        deviceDataRoot: String = "/data"
    ): Boolean {
        val dir = tokenDir(packageName)
        shell.execute("mkdir -p '$dir'")

        val encoded = Base64.getEncoder().encodeToString(
            buildModLauncherScript(packageName, instructions, deviceDataRoot).toByteArray()
        )
        val scriptOk = shell.execute(
            "printf '%s' '$encoded' | base64 -d > '$dir/mod_launcher.sh'"
        ).success
        val tokenOk = shell.execute(
            "touch '$dir/$TOKEN_FILENAME'"
        ).success
        return scriptOk && tokenOk
    }

    /**
     * Removes the activation token and launcher script for [packageName].
     *
     * Called after game exit as a safety cleanup in case the injected bootstrap
     * did not run (e.g. the game crashed before `Application.onCreate` completed).
     */
    fun clearToken(packageName: String) {
        val dir = tokenDir(packageName)
        shell.execute("rm -f '$dir/$TOKEN_FILENAME'")
        shell.execute("rm -f '$dir/mod_launcher.sh'")
    }

    /** `true` if the session token still exists on disk (i.e. script was not yet consumed). */
    fun hasToken(packageName: String): Boolean =
        shell.execute("test -f '${tokenDir(packageName)}/$TOKEN_FILENAME'").success

    // ─────────────────────────────────────────────────────────────────────────
    //  Mod → instruction conversion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts [mods] into [PatchInstruction]s targeting
     * `<deviceDataRoot>/data/<pkg>/files/save.dat` as the default save file.
     *
     * @param packageName    game package name
     * @param mods           mods whose patches should be encoded
     * @param deviceDataRoot root of internal data directories (default `/data`)
     */
    fun instructionsFromMods(
        packageName: String,
        mods: List<ModDefinition>,
        deviceDataRoot: String = "/data"
    ): List<PatchInstruction> {
        val saveFile = "$deviceDataRoot/data/$packageName/files/save.dat"
        return mods.flatMap { mod ->
            mod.patches.map { patch ->
                PatchInstruction(
                    filePath  = saveFile,
                    field     = patch.field,
                    operation = patch.operation.name,
                    value     = patch.amount
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shell-script generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates the `mod_launcher.sh` script content.
     *
     * The script:
     * 1. Applies each [PatchInstruction] to the corresponding save file using
     *    `sed` (SET) or `awk` (ADD, SUBTRACT, MUL).
     * 2. Backs up the patched save files to external storage so they survive
     *    an `pm uninstall` / restore-original cycle.
     * 3. Deletes the session token and itself (next unassisted launch is clean).
     *
     * All operations run **inside the game process** (via `Runtime.exec`),
     * giving full `/data/data/<pkg>/` access without root.
     */
    internal fun buildModLauncherScript(
        packageName: String,
        instructions: List<PatchInstruction>,
        deviceDataRoot: String = "/data"
    ): String {
        val tokenDir  = tokenDir(packageName)
        val backupDir = "$externalStorageRoot/Android/data/$packageName/saves_backup/data"
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# Android-Modder mod launcher – auto-generated, do not edit")
            appendLine()
            appendLine("# ── Apply patches ──────────────────────────────────────────────────")
            instructions.forEach { instr ->
                appendLine(buildPatchCommand(instr))
            }
            appendLine()
            appendLine("# ── Back up post-mod saves to external storage ─────────────────────")
            appendLine("mkdir -p '$backupDir'")
            instructions.map { it.filePath.substringBeforeLast('/') }.toSet().forEach { dir ->
                appendLine("cp -r '$dir/.' '$backupDir/' 2>/dev/null || true")
            }
            appendLine()
            appendLine("# ── Clean up: delete token and script ──────────────────────────────")
            appendLine("rm -f '$tokenDir/$TOKEN_FILENAME'")
            appendLine("rm -f '$tokenDir/mod_launcher.sh'")
        }
    }

    /**
     * Returns the shell command that applies a single [PatchInstruction].
     *
     * | Operation  | Command           | Notes                          |
     * |------------|-------------------|--------------------------------|
     * | SET        | `sed -i s/…/…/`   | Direct replacement             |
     * | ADD        | `awk` arithmetic  | `newVal = current + delta`     |
     * | SUBTRACT   | `awk` arithmetic  | `newVal = max(0, current - d)` |
     * | MUL        | `awk` arithmetic  | `newVal = current * factor`    |
     */
    internal fun buildPatchCommand(instr: PatchInstruction): String {
        val f = instr.filePath
        val k = instr.field
        val v = instr.value
        return when (instr.operation.uppercase()) {
            "SET" ->
                "sed -i \"s/^${k}=.*/${k}=${v}/\" '$f'"
            "ADD" ->
                "awk -F= -v k='$k' -v d=$v 'BEGIN{OFS=\"=\"} \$1==k{\$2=\$2+d}1' '$f' > /tmp/.mam_tmp && mv /tmp/.mam_tmp '$f'"
            "SUBTRACT" ->
                "awk -F= -v k='$k' -v d=$v 'BEGIN{OFS=\"=\"} \$1==k{v=\$2-d; \$2=(v<0?0:v)}1' '$f' > /tmp/.mam_tmp && mv /tmp/.mam_tmp '$f'"
            "MUL", "MULTIPLY" ->
                "awk -F= -v k='$k' -v d=$v 'BEGIN{OFS=\"=\"} \$1==k{\$2=\$2*d}1' '$f' > /tmp/.mam_tmp && mv /tmp/.mam_tmp '$f'"
            else ->
                "# SKIPPED: unknown operation '${instr.operation}' for field '$k'"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helper
    // ─────────────────────────────────────────────────────────────────────────

    /** `/sdcard/Android/data/<pkg>/files` – directory for token and launcher script. */
    internal fun tokenDir(packageName: String): String =
        "$externalStorageRoot/Android/data/$packageName/files"

    companion object {
        const val TOKEN_FILENAME = ".launcher_session"
    }
}

/**
 * A single patch directive inside the launcher script.
 *
 * @param filePath  full path to the save file (inside the app sandbox)
 * @param field     key name in the `KEY=VALUE` save file
 * @param operation one of `ADD`, `SET`, `SUBTRACT`, `MUL`
 * @param value     operand for the operation
 */
data class PatchInstruction(
    val filePath: String,
    val field: String,
    val operation: String,
    val value: Long
)
