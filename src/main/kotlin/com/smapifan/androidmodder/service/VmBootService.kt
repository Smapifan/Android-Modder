package com.smapifan.androidmodder.service

import java.io.InputStream

// ═════════════════════════════════════════════════════════════════════════════
//  VmBootService – one-shot VM initialiser
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Performs the one-time virtual-machine initialisation on first boot.
 *
 * ## What it does
 *
 * On each call to [boot], this service iterates [SystemAppsRegistry.bundled]
 * and installs every [SystemAppEntry.autoInstall] entry that is **not yet
 * present** in the virtual sandbox managed by [AppInstallManagerService].
 * Already-installed apps are skipped, so the boot pass is cheap after the
 * first run.
 *
 * ## Minimal-RAM design
 *
 * - The service holds **no** long-lived service references in fields.
 *   [AppInstallManagerService] is injected and used only during [boot]; the
 *   caller is free to discard the `VmBootService` instance after the call
 *   returns.
 * - APK streams are opened and closed one at a time during installation;
 *   no APK content is ever kept in memory after its [AppInstallManagerService.installApk]
 *   call completes.
 * - [RamEditor], [RamAnalyzer], and [InAppBrowserService] are **never**
 *   instantiated here; they are lazy-constructed on first use by the UI layer.
 *
 * ## Asset loading
 *
 * APK bytes are fetched via the [assetOpener] callback.  On Android this is
 * typically `context.assets::open`; in JVM unit tests a map-backed stub can
 * be supplied instead.
 *
 * ```kotlin
 * // Android usage
 * val bootService = VmBootService(
 *     installer   = AppInstallManagerService(context),
 *     assetOpener = context.assets::open
 * )
 * bootService.boot()
 *
 * // JVM test usage
 * val bootService = VmBootService(
 *     installer   = AppInstallManagerService(filesRoot = tmpDir),
 *     assetOpener = { name -> fakeApkMap[name]?.inputStream() }
 * )
 * ```
 *
 * @param installer    the [AppInstallManagerService] that owns the virtual sandbox
 * @param assetOpener  callback that opens a named asset from `assets/system_apps/<name>`;
 *                     returns `null` when the asset is not present (entry is skipped with a warning)
 */
class VmBootService(
    private val installer: AppInstallManagerService,
    private val assetOpener: (assetFileName: String) -> InputStream?
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Boot
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs all missing [SystemAppsRegistry.bundled] auto-install entries.
     *
     * Already-installed packages are skipped.  APK assets that are absent (e.g.
     * the APK file has not been added to `assets/system_apps/` yet) produce a
     * warning in [BootReport.warnings] but do not abort the pass.
     *
     * @return a [BootReport] summarising which apps were installed, skipped,
     *         or failed.
     */
    fun boot(): BootReport {
        val installed = mutableListOf<String>()
        val skipped   = mutableListOf<String>()
        val failed    = mutableListOf<String>()
        val warnings  = mutableListOf<String>()

        for (entry in SystemAppsRegistry.bundled) {
            if (!entry.autoInstall) {
                skipped += entry.packageId
                continue
            }

            if (installer.isInstalled(entry.packageId)) {
                skipped += entry.packageId
                continue
            }

            val assetPath = "${SYSTEM_APPS_ASSET_DIR}/${entry.assetFileName}"
            val stream    = assetOpener(assetPath)
            if (stream == null) {
                warnings += "Asset not found for ${entry.packageId}: $assetPath"
                failed   += entry.packageId
                continue
            }

            val ok = stream.use { installer.installApk(entry.packageId, it) }
            if (ok) installed += entry.packageId else failed += entry.packageId
        }

        return BootReport(installed = installed, skipped = skipped, failed = failed, warnings = warnings)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  User-supplied APKs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs a user-supplied APK stream and registers it in
     * [SystemAppsRegistry] so it appears in the unified app list.
     *
     * This is the correct path for proprietary apps (GApps, YouTube, etc.)
     * that cannot be bundled in `assets/system_apps/` for copyright reasons.
     *
     * @param packageId  Android package name of the APK.
     * @param label      Human-readable name shown in the app drawer.
     * @param apkStream  raw bytes of the APK to install.
     * @param category   optional category; defaults to [SystemAppCategory.USER_SUPPLIED].
     * @return `true` if the installation succeeded.
     */
    fun installUserApk(
        packageId: String,
        label: String,
        apkStream: InputStream,
        category: SystemAppCategory = SystemAppCategory.USER_SUPPLIED
    ): Boolean {
        val ok = apkStream.use { installer.installApk(packageId, it) }
        if (ok) {
            SystemAppsRegistry.registerUserApk(packageId, label, category)
        }
        return ok
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Asset folder path prefix for bundled system APKs. */
        const val SYSTEM_APPS_ASSET_DIR = "system_apps"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BootReport
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Result of a [VmBootService.boot] pass.
 *
 * @param installed package IDs that were freshly installed during this pass.
 * @param skipped   package IDs that were skipped (already installed or autoInstall=false).
 * @param failed    package IDs whose installation failed (missing asset or I/O error).
 * @param warnings  human-readable warning messages (e.g. missing asset files).
 */
data class BootReport(
    val installed: List<String>,
    val skipped: List<String>,
    val failed: List<String>,
    val warnings: List<String>
) {
    /** `true` if at least one package was freshly installed. */
    val anyInstalled: Boolean get() = installed.isNotEmpty()

    /** `true` if every auto-install entry either succeeded or was already present. */
    val allSucceeded: Boolean get() = failed.isEmpty()
}
