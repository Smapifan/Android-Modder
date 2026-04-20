package com.smapifan.androidmodder.service

import java.util.Base64

/**
 * Backs up and restores a game's internal save data (`/data/data/<pkg>/`)
 * via external storage (`/sdcard/Android/data/<pkg>/saves_backup/`).
 *
 * ## Why this exists
 *
 * After a modded session the launcher optionally restores the original
 * Play-Store APK.  Because the patched and original APKs carry **different
 * signatures**, Android requires an `pm uninstall` + `pm install` cycle –
 * which wipes `/data/data/<pkg>/`.
 *
 * To prevent any save-data loss the workflow is:
 *
 * 1. **Before uninstall** – call [backupToExternal] to copy `/data/data/<pkg>/`
 *    to `/sdcard/Android/data/<pkg>/saves_backup/data/`.
 *    Works via `run-as <pkg>` because the *patched* APK has
 *    `android:debuggable="true"`.
 * 2. Uninstall patched APK → install original APK (data wiped by Android,
 *    but saves are safe in the external backup).
 * 3. **On next launcher-assisted launch** – [writeRestoreScript] has left a
 *    shell script + `.restore_pending` flag that the injected smali bootstrap
 *    executes automatically in `Application.onCreate()`, copying the backup
 *    back into `/data/data/<pkg>/` *before* the game reads its save files.
 *
 * ## No root required
 *
 * - Export phase: `run-as <pkg>` (works on debuggable / patched APK).
 * - Restore phase: the script runs *inside* the game's own process, so it
 *   has the same `/data/data/<pkg>/` permissions as the game itself.
 *
 * @param shell              shell command executor
 * @param externalStorageRoot external storage root, default `/sdcard`
 */
class SaveBackupService(
    private val shell: ShellExecutor = ShellExecutor(),
    val externalStorageRoot: String = "/sdcard"
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Backup: internal data → external storage
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies `/data/data/<pkg>/` to the external save-backup directory using
     * `run-as <pkg>`.
     *
     * **Requires** the currently-installed APK to be debuggable.  Call this
     * while the *patched* APK (with `android:debuggable="true"`) is still
     * installed – i.e. *before* uninstalling it.
     *
     * @param packageName the game's package name
     * @return `true` if the copy command succeeded
     */
    fun backupToExternal(packageName: String): Boolean {
        val backupDataDir = backupDataDir(packageName)
        shell.execute("mkdir -p '$backupDataDir'")
        val runAs = RunAsExecutor(packageName, shell)
        return runAs.exportDataDir(
            internalDataDir = internalDataDir(packageName),
            destination     = backupDataDir
        ).success
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Restore script: written to external storage, executed by injected smali
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes `restore_saves.sh` and `.restore_pending` to the external
     * save-backup directory so that the injected smali bootstrap executes the
     * restore on the *next* `Application.onCreate()`.
     *
     * The generated script copies the backup back into `/data/data/<pkg>/`
     * (valid because the script runs as the app's own UID), then cleans up
     * itself and the pending flag so subsequent clean launches are unaffected.
     *
     * @param packageName the game's package name
     * @return `true` if both the script and the flag were written successfully
     */
    fun writeRestoreScript(packageName: String): Boolean {
        val backupDir = backupRootDir(packageName)
        shell.execute("mkdir -p '$backupDir'")

        val encoded = Base64.getEncoder()
            .encodeToString(buildRestoreScript(packageName).toByteArray())

        val scriptOk = shell.execute(
            "printf '%s' '$encoded' | base64 -d > '$backupDir/$RESTORE_SCRIPT_FILENAME'"
        ).success
        val flagOk = shell.execute(
            "touch '$backupDir/$RESTORE_PENDING_FILENAME'"
        ).success
        return scriptOk && flagOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Status checks
    // ─────────────────────────────────────────────────────────────────────────

    /** `true` if an external save backup directory exists for [packageName]. */
    fun hasBackup(packageName: String): Boolean =
        shell.execute("test -d '${backupDataDir(packageName)}'").success

    /** `true` if a `.restore_pending` flag is present for [packageName]. */
    fun hasRestorePending(packageName: String): Boolean =
        shell.execute(
            "test -f '${backupRootDir(packageName)}/$RESTORE_PENDING_FILENAME'"
        ).success

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** `/sdcard/Android/data/<pkg>/saves_backup` */
    internal fun backupRootDir(packageName: String): String =
        "$externalStorageRoot/Android/data/$packageName/saves_backup"

    /** `/sdcard/Android/data/<pkg>/saves_backup/data` – mirrors `/data/data/<pkg>/` */
    internal fun backupDataDir(packageName: String): String =
        "${backupRootDir(packageName)}/data"

    /** `/data/data/<pkg>` */
    internal fun internalDataDir(packageName: String): String =
        "/data/data/$packageName"

    // ─────────────────────────────────────────────────────────────────────────
    //  Script generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates the shell script that restores save data from external backup
     * into `/data/data/<pkg>/`.
     *
     * The script:
     * 1. Copies `<backup>/data/` → `/data/data/<pkg>/` (available because the
     *    script runs as the game's own UID via the injected smali bootstrap).
     * 2. Deletes the `.restore_pending` flag and itself (one-shot execution).
     */
    internal fun buildRestoreScript(packageName: String): String {
        val src    = backupDataDir(packageName)
        val dst    = internalDataDir(packageName)
        val flag   = "${backupRootDir(packageName)}/$RESTORE_PENDING_FILENAME"
        val script = "${backupRootDir(packageName)}/$RESTORE_SCRIPT_FILENAME"
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# Android-Modder: restore saves from external backup")
            appendLine("# Runs inside the game process (Runtime.exec) – no root needed.")
            appendLine("SRC='$src'")
            appendLine("DST='$dst'")
            appendLine("if [ -d \"\$SRC\" ]; then")
            appendLine("    cp -r \"\$SRC/.\" \"\$DST/\"")
            appendLine("fi")
            appendLine("rm -f '$flag'")
            appendLine("rm -f '$script'")
        }
    }

    companion object {
        const val RESTORE_PENDING_FILENAME = ".restore_pending"
        const val RESTORE_SCRIPT_FILENAME  = "restore_saves.sh"
    }
}
