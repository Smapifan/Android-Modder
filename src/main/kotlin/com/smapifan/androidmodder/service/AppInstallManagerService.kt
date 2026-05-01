package com.smapifan.androidmodder.service

import android.content.pm.PackageManager
import java.io.File
import java.io.InputStream

// ═════════════════════════════════════════════════════════════════════════════
//  AppInstallManagerService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Installs APK files into Android-Modder's own virtual sandbox, mirroring
 * Android's standard data-directory layout without requiring root.
 *
 * ## Virtual directory layout
 *
 * ```
 * <filesRoot>/
 *   apps/
 *     <packageId>.apk           ← original APK archive
 *   <packageId>/
 *     data/
 *       data/
 *         <packageId>/
 *           files/              ← standard internal files (like /data/data/<pkg>/files/)
 *           cache/              ← cache
 *           databases/          ← SQLite databases
 *           shared_prefs/       ← SharedPreferences XML files
 *     mods/                     ← mod-layer directories
 *       <modName>/
 *         ...
 * ```
 *
 * Because all paths are inside Android-Modder's own `files/` directory, no
 * root access is required for any operation.
 *
 * @param context      Android application context; used only to resolve the
 *                     default [filesRoot] and to call [PackageManager].
 *                     Pass `null` in JVM unit tests (Context-dependent methods
 *                     like [resolvePackageId] and [appLabel] will throw if called
 *                     without a valid context).
 * @param filesRoot    root of Android-Modder's `files/` directory;
 *                     defaults to `context.filesDir.absolutePath`
 */
class AppInstallManagerService(
    private val context: android.content.Context?,
    val filesRoot: String = requireNotNull(context) {
        "context must be non-null when filesRoot is not explicitly provided"
    }.filesDir.absolutePath
) {

    /**
     * Constructor for JVM unit tests that don't have an Android [Context].
     *
     * Only filesystem operations ([installApk], [listInstalledPackages], etc.)
     * are available.  Calling [resolvePackageId] or [appLabel] will throw.
     */
    constructor(filesRoot: String) : this(context = null, filesRoot = filesRoot)

    // ─────────────────────────────────────────────────────────────────────────
    //  Standard sub-directory names
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val APPS_DIR        = "apps"
        const val MODS_DIR        = "mods"
        const val DATA_DATA_DIR   = "data/data"
        val STANDARD_SUBDIRS      = listOf("files", "cache", "databases", "shared_prefs")

        /**
         * Marker file written inside each virtual package directory on install.
         * Its presence is used by [listInstalledPackages] to reliably identify
         * directories that were created by this service (rather than relying on
         * a fragile dot-in-name heuristic).
         */
        const val INSTALL_MARKER  = ".androidmodder_install"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Absolute path to the APK archive for [packageId]. */
    fun apkPath(packageId: String): String =
        "$filesRoot/$APPS_DIR/$packageId.apk"

    /**
     * Virtual data root for [packageId]:
     * `<filesRoot>/<packageId>/data/data/<packageId>`
     */
    fun dataDataRoot(packageId: String): String =
        "$filesRoot/$packageId/$DATA_DATA_DIR/$packageId"

    /**
     * Mod-layers root for [packageId]:
     * `<filesRoot>/<packageId>/mods`
     */
    fun modsRoot(packageId: String): String =
        "$filesRoot/$packageId/$MODS_DIR"

    // ─────────────────────────────────────────────────────────────────────────
    //  Installation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs an APK stream into the virtual sandbox.
     *
     * 1. Copies the APK bytes to `<filesRoot>/apps/<packageId>.apk`.
     * 2. Creates the standard directory structure under
     *    `<filesRoot>/<packageId>/data/data/<packageId>/`.
     * 3. Creates the mod-layers directory `<filesRoot>/<packageId>/mods/`.
     *
     * @param packageId  Android package name, e.g. `"com.example.game"`
     * @param apkStream  raw bytes of the APK file
     * @return `true` on success
     */
    fun installApk(packageId: String, apkStream: InputStream): Boolean =
        runCatching {
            // 1. Store APK
            val apkFile = File(apkPath(packageId))
            apkFile.parentFile?.mkdirs()
            apkFile.outputStream().use { apkStream.copyTo(it) }

            // 2. Create standard data directories
            val dataRoot = File(dataDataRoot(packageId))
            for (sub in STANDARD_SUBDIRS) {
                File(dataRoot, sub).mkdirs()
            }

            // 3. Create mod-layers directory
            File(modsRoot(packageId)).mkdirs()

            // 4. Write install marker so listInstalledPackages can reliably
            //    identify directories created by this service.
            File("$filesRoot/$packageId", INSTALL_MARKER).writeText(packageId)

            true
        }.getOrDefault(false)

    /**
     * Installs an APK from a [File] on disk.  Parses the package name via
     * [PackageManager] so callers don't need to know it in advance.
     *
     * @param apkFile  the APK file to install
     * @return the package name on success, `null` on failure
     */
    fun installApkFile(apkFile: File): String? {
        val packageId = resolvePackageId(apkFile) ?: return null
        val ok = apkFile.inputStream().use { installApk(packageId, it) }
        return if (ok) packageId else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Mod layers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new named mod-layer directory for [packageId].
     *
     * @param packageId  the target package
     * @param modName    name for the mod layer, e.g. `"InfiniteCoins"`
     * @return `true` if the directory was created (or already existed)
     */
    fun createModLayer(packageId: String, modName: String): Boolean =
        File(modsRoot(packageId), modName).mkdirs()

    /**
     * Lists all mod-layer names for [packageId].
     *
     * @return sorted list of mod-layer names, or an empty list if none exist
     */
    fun listModLayers(packageId: String): List<String> =
        File(modsRoot(packageId))
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /**
     * Deletes a named mod-layer directory and all its contents.
     *
     * @return `true` if the layer no longer exists after the call
     */
    fun deleteModLayer(packageId: String, modName: String): Boolean =
        File(modsRoot(packageId), modName).deleteRecursively()

    // ─────────────────────────────────────────────────────────────────────────
    //  Catalog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists all package IDs that have been virtually installed.
     *
     * Uses the presence of an [INSTALL_MARKER] file to identify directories
     * created by this service, rather than relying on a heuristic.
     *
     * @return sorted list of package names
     */
    fun listInstalledPackages(): List<String> =
        File(filesRoot)
            .listFiles()
            ?.filter { it.isDirectory && File(it, INSTALL_MARKER).exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /**
     * Returns `true` if [packageId] has a virtual installation
     * (identified by the [INSTALL_MARKER] file).
     */
    fun isInstalled(packageId: String): Boolean =
        File("$filesRoot/$packageId", INSTALL_MARKER).exists()

    /**
     * Uninstalls [packageId] from the virtual sandbox, removing its APK,
     * data directories, and mod layers.
     *
     * @return `true` if the package directory no longer exists
     */
    fun uninstall(packageId: String): Boolean {
        File(apkPath(packageId)).delete()
        return File(filesRoot, packageId).deleteRecursively()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Package info
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the package name from an APK file using [PackageManager].
     *
     * @return the package name, or `null` if the APK cannot be parsed
     */
    @Suppress("DEPRECATION")
    fun resolvePackageId(apkFile: File): String? {
        val pm = requireNotNull(context) { "resolvePackageId requires an Android Context" }.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        return info?.packageName
    }

    /**
     * Returns the app label for an installed virtual package if the APK is
     * still present, or the package name as fallback.
     */
    @Suppress("DEPRECATION")
    fun appLabel(packageId: String): String {
        val apkFile = File(apkPath(packageId))
        if (!apkFile.exists()) return packageId
        return runCatching {
            val pm = requireNotNull(context) { "appLabel requires an Android Context" }.packageManager
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return@runCatching packageId
            info.applicationInfo?.let { ai ->
                ai.sourceDir = apkFile.absolutePath
                ai.publicSourceDir = apkFile.absolutePath
                pm.getApplicationLabel(ai).toString()
            } ?: packageId
        }.getOrDefault(packageId)
    }
}
