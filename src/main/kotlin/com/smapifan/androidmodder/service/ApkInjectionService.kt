package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.ApkInjectionConfig
import java.io.File

/**
 * Patches an Android APK to inject the Android-Modder mod-loader bootstrap,
 * and later restores the original Play-Store APK without any save-data loss.
 *
 * ## Patch-and-install pipeline  (one-time per APK version)
 *
 * 1. **Decompile** – `apktool d <original.apk>` into a temporary directory.
 * 2. **Inject** – add `ModLoaderHook.smali`, set
 *    `android:debuggable="true"`, and point the `<application android:name>`
 *    attribute at the hook class.  The hook extends the game's original
 *    Application subclass (if any) so existing startup logic is preserved.
 * 3. **Rebuild** – `apktool b <decompiled-dir>` → unsigned APK.
 * 4. **Sign** – `apksigner sign --ks <keystore>` → patched APK.
 * 5. **Install** – `pm install -r <patched.apk>` (keep data).
 *    If the existing APK has a different signature (e.g. Play Store version),
 *    Android rejects `-r`; in that case the original APK must be uninstalled
 *    first (see [restoreOriginalApk] for the reverse direction).
 *
 * ## Restore-original pipeline  (Play-Store-compatible APK back, zero save loss)
 *
 * 1. [SaveBackupService.backupToExternal] – copy `/data/data/<pkg>/` to
 *    external storage via `run-as` (works because the patched APK has
 *    `debuggable=true`).
 * 2. [SaveBackupService.writeRestoreScript] – leave a restore script that the
 *    injected smali executes automatically on the next patched-APK launch,
 *    copying the backup back into `/data/data/<pkg>/` before the game reads
 *    its save files.
 * 3. `pm uninstall <pkg>` – removes the patched APK (data wiped by Android,
 *    but saves are safe in the external backup).
 * 4. `pm install <original.apk>` – reinstalls the unmodified APK.
 *
 * **Save-data guarantee**: saves are never permanently lost.  They live in the
 * external backup between sessions and are automatically restored on the next
 * launcher-assisted launch.
 *
 * ## Root requirement: none
 *
 * All operations use `run-as` (enabled by `debuggable=true` in the patched APK)
 * or the game's own process (for the in-process restore script).
 *
 * @param shell      executes shell commands
 * @param saveBackup manages save-data backup / restore to external storage
 */
class ApkInjectionService(
    private val shell: ShellExecutor = ShellExecutor(),
    private val saveBackup: SaveBackupService = SaveBackupService(shell)
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Full patch-and-install pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the complete decompile → inject → rebuild → sign → install pipeline.
     *
     * If the package is already installed with the **same** signature (i.e. a
     * previous patched version), `pm install -r` preserves app data.
     * If the currently-installed APK has a **different** signature (original
     * Play-Store version), call [restoreOriginalApk] first to uninstall it,
     * then call this method again.
     *
     * @return `true` if every step succeeded
     */
    fun patchAndInstall(config: ApkInjectionConfig): Boolean {
        val workDir      = File(config.workDir).also { it.mkdirs() }
        val decompileDir = File(workDir, "decompiled")
        val unsignedApk  = File(workDir, "patched-unsigned.apk")
        val signedApk    = File(workDir, "patched.apk")

        return decompile(config.originalApkPath, decompileDir, config.apktoolPath)
            && injectHook(decompileDir, config.packageName)
            && rebuild(decompileDir, unsignedApk, config.apktoolPath)
            && sign(unsignedApk, signedApk, config)
            && installPatched(signedApk.absolutePath)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Restore-original pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Restores the original Play-Store APK **without losing any save data**.
     *
     * ## Step-by-step
     *
     * 1. **Backup saves** – copies `/data/data/<pkg>/` to external storage
     *    via `run-as <pkg>` (works because the currently-installed patched APK
     *    has `android:debuggable="true"`).
     * 2. **Write restore script** – leaves a shell script and
     *    `.restore_pending` marker in external storage.  The injected smali
     *    bootstrap in the *next* patched-APK install will execute this script
     *    on its first `Application.onCreate()`, copying the backup back into
     *    `/data/data/<pkg>/` before the game reads its save files.
     * 3. **Uninstall patched APK** – `pm uninstall <pkg>`.  Android wipes
     *    `/data/data/<pkg>/` here, but saves are safe in the external backup.
     * 4. **Install original APK** – `pm install <original.apk>`.  The game
     *    now runs exactly as it would from the Play Store.  Save data will be
     *    absent from `/data/data/` until the user launches through the
     *    Android-Modder launcher again (which re-patches and triggers the
     *    restore on first startup).
     *
     * @param config injection config; [ApkInjectionConfig.originalApkPath]
     *               must point to the unmodified APK that should be reinstalled
     * @return `true` if every step succeeded
     */
    fun restoreOriginalApk(config: ApkInjectionConfig): Boolean {
        val pkg = config.packageName

        // 1. Back up current saves before anything is uninstalled
        if (!saveBackup.backupToExternal(pkg)) return false

        // 2. Leave restore script so the next patched launch auto-restores
        if (!saveBackup.writeRestoreScript(pkg)) return false

        // 3. Uninstall patched APK (data wiped – saves are in external backup)
        if (!uninstall(pkg)) return false

        // 4. Reinstall the original Play-Store APK
        return installOriginal(config.originalApkPath)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 1 – Decompile
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decompiles [apkPath] into [outDir] using `apktool d -f`.
     * An existing [outDir] is deleted first so repeated runs always start fresh.
     */
    internal fun decompile(apkPath: String, outDir: File, apktool: String = "apktool"): Boolean {
        outDir.deleteRecursively()
        return shell.execute(
            "$apktool d '$apkPath' -o '${outDir.absolutePath}' -f",
            timeoutMs = 120_000L
        ).success
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 2 – Inject hook + enable debuggable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injects the mod-loader smali class and updates `AndroidManifest.xml` in
     * the [decompileDir] produced by `apktool d`.
     *
     * Changes applied:
     * - Writes `smali/com/androidmodder/ModLoaderHook.smali`.
     * - Replaces (or inserts) `android:name` in `<application>` so the hook
     *   class is the Application that Android instantiates.
     * - Sets `android:debuggable="true"` so that `run-as <pkg>` works for the
     *   save-backup step in [restoreOriginalApk].
     */
    internal fun injectHook(decompileDir: File, packageName: String): Boolean {
        val manifest = File(decompileDir, "AndroidManifest.xml")
        if (!manifest.exists()) return false

        val originalXml = manifest.readText()
        val superClass  = parseApplicationClass(originalXml)

        // Write smali hook
        val smaliDir = File(decompileDir, "smali/com/androidmodder").also { it.mkdirs() }
        File(smaliDir, "ModLoaderHook.smali").writeText(buildSmali(packageName, superClass))

        // Patch manifest
        val patchedXml = originalXml
            .let { patchManifestApplicationName(it, HOOK_CLASS_DOTTED) }
            .let { enableDebuggable(it) }
        manifest.writeText(patchedXml)
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 3 – Rebuild
    // ─────────────────────────────────────────────────────────────────────────

    /** Rebuilds [decompileDir] into [outApk] using `apktool b`. */
    internal fun rebuild(decompileDir: File, outApk: File, apktool: String = "apktool"): Boolean =
        shell.execute(
            "$apktool b '${decompileDir.absolutePath}' -o '${outApk.absolutePath}'",
            timeoutMs = 120_000L
        ).success

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 4 – Sign
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Signs [unsignedApk] → [signedApk] using `apksigner`.
     * `--min-sdk-version 21` avoids legacy v1-only signing.
     */
    internal fun sign(unsignedApk: File, signedApk: File, config: ApkInjectionConfig): Boolean {
        val cmd = buildString {
            append("${config.apksignerPath} sign ")
            append("--ks '${config.keystorePath}' ")
            append("--ks-pass pass:${config.keystorePassword} ")
            append("--ks-key-alias '${config.keyAlias}' ")
            append("--key-pass pass:${config.keyPassword} ")
            append("--min-sdk-version 21 ")
            append("--out '${signedApk.absolutePath}' ")
            append("'${unsignedApk.absolutePath}'")
        }
        return shell.execute(cmd, timeoutMs = 60_000L).success
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Install / uninstall
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs the patched APK using `pm install -r` (replace, keep data).
     *
     * If the currently-installed package has a **different** signature Android
     * will reject the `-r` install; [uninstall] (after [SaveBackupService
     * .backupToExternal]) must be called first.
     */
    internal fun installPatched(apkPath: String): Boolean =
        shell.execute("pm install -r '$apkPath'", timeoutMs = 60_000L).success

    /**
     * Installs the original APK using `pm install` (no `-r`).
     * Used after [uninstall] when restoring the Play-Store version.
     */
    internal fun installOriginal(apkPath: String): Boolean =
        shell.execute("pm install '$apkPath'", timeoutMs = 60_000L).success

    /**
     * Uninstalls [packageName].
     *
     * **Warning**: this wipes all `/data/data/<pkg>/` data.  Always call
     * [SaveBackupService.backupToExternal] first.
     */
    internal fun uninstall(packageName: String): Boolean =
        shell.execute("pm uninstall '$packageName'", timeoutMs = 30_000L).success

    // ─────────────────────────────────────────────────────────────────────────
    //  Manifest helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the existing `android:name` value from the `<application>` tag,
     * returning the slash-separated smali form (e.g. `"com/example/MyApp"`),
     * or `null` if no custom Application class is declared.
     */
    internal fun parseApplicationClass(manifestXml: String): String? {
        val regex = Regex("""<application[^>]+android:name="([^"]+)"""")
        val match = regex.find(manifestXml) ?: return null
        return match.groupValues[1]
            .removePrefix(".")
            .replace('.', '/')
    }

    /**
     * Replaces (or inserts) the `android:name` attribute in the `<application>`
     * tag with [dottedName] (e.g. `"com.androidmodder.ModLoaderHook"`).
     */
    internal fun patchManifestApplicationName(manifestXml: String, dottedName: String): String {
        val existing = Regex("""(<application[^>]+android:name=")[^"]+"""")
        return if (existing.containsMatchIn(manifestXml)) {
            existing.replace(manifestXml) { mr -> "${mr.groupValues[1]}$dottedName\"" }
        } else {
            manifestXml.replace("<application", "<application android:name=\"$dottedName\"")
        }
    }

    /**
     * Ensures `android:debuggable="true"` is set in the `<application>` tag.
     *
     * Required so that `run-as <pkg>` works when [SaveBackupService
     * .backupToExternal] is called before uninstalling the patched APK.
     */
    internal fun enableDebuggable(manifestXml: String): String = when {
        manifestXml.contains("""android:debuggable="true"""") -> manifestXml
        manifestXml.contains("android:debuggable=") ->
            manifestXml.replace(
                Regex("""android:debuggable="[^"]+""""),
                """android:debuggable="true""""
            )
        else ->
            manifestXml.replace("<application", """<application android:debuggable="true"""")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Smali generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates the smali source for `ModLoaderHook.smali` by substituting
     * [SMALI_TEMPLATE] placeholders.
     *
     * @param packageName game's Android package name
     * @param superClass  slash-form class of the original Application subclass,
     *                    or `null` → defaults to `android/app/Application`
     */
    internal fun buildSmali(packageName: String, superClass: String?): String =
        SMALI_TEMPLATE
            .replace("@@SUPER@@", superClass ?: "android/app/Application")
            .replace("@@PACKAGE@@", packageName)

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Dotted class name used in `AndroidManifest.xml`. */
        const val HOOK_CLASS_DOTTED     = "com.androidmodder.ModLoaderHook"
        /** Slash-separated descriptor used inside smali source. */
        const val HOOK_CLASS_DESCRIPTOR = "com/androidmodder/ModLoaderHook"

        /**
         * Smali template for the mod-loader bootstrap class.
         *
         * Placeholders:
         * - `@@SUPER@@`   – slash-form superclass (e.g. `android/app/Application`)
         * - `@@PACKAGE@@` – game's Android package name
         *
         * ## Lifecycle (inside the game process on every `Application.onCreate`)
         *
         * 1. `restoreSavesIfNeeded()` – if `.restore_pending` marker exists in
         *    external storage, executes `restore_saves.sh` (copies the external
         *    backup back into `/data/data/<pkg>/`).  Runs BEFORE the game sees
         *    its save files.
         * 2. `applyPendingMods()` – if `.launcher_session` token exists, executes
         *    `mod_launcher.sh` (applies mod patches, backs up post-mod saves,
         *    deletes the token).
         * 3. Delegates to the original Application's `onCreate()`.
         *
         * Without either file present both methods are complete no-ops.
         */
        val SMALI_TEMPLATE: String = """
.class public Lcom/androidmodder/ModLoaderHook;
.super L@@SUPER@@;
.source "ModLoaderHook.java"

# Android-Modder bootstrap – auto-generated, do not edit.
# Package: @@PACKAGE@@
#
# Lifecycle on every Application.onCreate():
#   1. restoreSavesIfNeeded() – copies external backup → /data/data/@@PACKAGE@@/ if .restore_pending exists
#   2. applyPendingMods()     – runs mod_launcher.sh if .launcher_session token exists
#   3. Delegates to L@@SUPER@@;->onCreate()V
#
# Without either file both methods are complete no-ops.

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, L@@SUPER@@;-><init>()V
    return-void
.end method

.method public onCreate()V
    .locals 0
    invoke-virtual {p0}, Lcom/androidmodder/ModLoaderHook;->restoreSavesIfNeeded()V
    invoke-virtual {p0}, Lcom/androidmodder/ModLoaderHook;->applyPendingMods()V
    invoke-super {p0}, L@@SUPER@@;->onCreate()V
    return-void
.end method

# ── restoreSavesIfNeeded ──────────────────────────────────────────────────────
# Executes restore_saves.sh if .restore_pending marker is present.
# This copies saves from the external backup back into /data/data/@@PACKAGE@@/
# before the game's own code initialises – zero save loss after APK restore.

.method private restoreSavesIfNeeded()V
    .locals 4
    const-string v0, "/sdcard/Android/data/@@PACKAGE@@/saves_backup/.restore_pending"
    new-instance v1, Ljava/io/File;
    invoke-direct {v1, v0}, Ljava/io/File;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/io/File;->exists()Z
    move-result v2
    if-eqz v2, :no_restore
    :try_restore_0
    invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
    move-result-object v2
    const-string v3, "sh /sdcard/Android/data/@@PACKAGE@@/saves_backup/restore_saves.sh"
    invoke-virtual {v2, v3}, Ljava/lang/Runtime;->exec(Ljava/lang/String;)Ljava/lang/Process;
    move-result-object v2
    invoke-virtual {v2}, Ljava/lang/Process;->waitFor()I
    move-result v2
    :try_restore_1
    .catch Ljava/lang/Exception; {:try_restore_0 .. :try_restore_1} :restore_ex
    goto :no_restore
    :restore_ex
    move-exception v2
    :no_restore
    return-void
.end method

# ── applyPendingMods ──────────────────────────────────────────────────────────
# Executes mod_launcher.sh if .launcher_session token is present.
# The script applies patches, backs up post-mod saves, and deletes the token.

.method private applyPendingMods()V
    .locals 4
    const-string v0, "/sdcard/Android/data/@@PACKAGE@@/files/.launcher_session"
    new-instance v1, Ljava/io/File;
    invoke-direct {v1, v0}, Ljava/io/File;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/io/File;->exists()Z
    move-result v2
    if-eqz v2, :no_session
    :try_mods_0
    invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
    move-result-object v2
    const-string v3, "sh /sdcard/Android/data/@@PACKAGE@@/files/mod_launcher.sh"
    invoke-virtual {v2, v3}, Ljava/lang/Runtime;->exec(Ljava/lang/String;)Ljava/lang/Process;
    move-result-object v2
    invoke-virtual {v2}, Ljava/lang/Process;->waitFor()I
    move-result v2
    :try_mods_1
    .catch Ljava/lang/Exception; {:try_mods_0 .. :try_mods_1} :mods_ex
    goto :no_session
    :mods_ex
    move-exception v2
    :no_session
    return-void
.end method
        """.trimIndent()
    }
}
