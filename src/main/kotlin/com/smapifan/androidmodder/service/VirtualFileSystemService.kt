// Manages the in-app virtual filesystem where each guest app gets its own isolated sandboxed data directory.
// Verwaltet das In-App-virtuelle Dateisystem, in dem jede Gast-App ein eigenes isoliertes Sandbox-Datenverzeichnis erhält.

package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.VirtualFsEntry
import java.io.File

// ═════════════════════════════════════════════════════════════════════════════
//  VirtualFileSystemService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Manages the in-app virtual filesystem.
 *
 * Every guest app gets a private virtual data directory inside Android-Modder's
 * own sandbox:
 *
 * ```
 * <appFilesRoot>/<packageName>/   ← virtual data root for <packageName>
 * ```
 *
 * Because Android-Modder owns the host directory, it can read and write there
 * **without root** and **without `run-as`**.  The launcher redirects game save
 * data into this tree during the export phase and reads it back during import,
 * giving the app full "root-like" control over guest app data within its own
 * sandbox.
 *
 * ## Path layout
 *
 * ```
 * <appFilesRoot>/
 *   com.gram.mergedragons/
 *     files/
 *       save.dat
 *   com.kiloo.subwaysurf/
 *     files/
 *       playerData.dat
 * ```
 *
 * ## No root required
 *
 * All operations are plain Java/Kotlin file I/O on the host app's own files
 * directory – no `run-as`, no `su`, no shell commands.
 *
 * @param appFilesRoot root of Android-Modder's own `files/` directory,
 *                     default `/data/data/com.smapifan.androidmodder/files`
 */
class VirtualFileSystemService(
    val appFilesRoot: String = DEFAULT_APP_FILES_ROOT
) {

    companion object {
        const val DEFAULT_APP_FILES_ROOT = "/data/data/com.smapifan.androidmodder/files"

        /**
         * Legacy sub-directory name from older builds.
         *
         * New builds store package directories directly under [appFilesRoot] so
         * paths match `/data/data/<this-app>/files/<pkg>/...` as requested.
         */
        const val LEGACY_VIRTUAL_DATA_SUBDIR = "vdata"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the virtual data root for [packageName]:
     * `<appFilesRoot>/<packageName>/`
     */
    fun virtualDataRoot(packageName: String): String =
        "$appFilesRoot/$packageName"

    // ─────────────────────────────────────────────────────────────────────────
    //  Setup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ensures the virtual data directory for [packageName] exists.
     *
     * @return `true` if the directory exists or was created successfully
     */
    fun ensureVirtualDir(packageName: String): Boolean {
        val dir = File(virtualDataRoot(packageName))
        return dir.exists() || dir.mkdirs()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Catalog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists all guest packages that currently have a virtual data directory.
     *
     * @return sorted list of package names
     */
    fun listInstalledPackages(): List<String> {
        val primaryRoot = File(appFilesRoot)
        val legacyRoot = File("$appFilesRoot/$LEGACY_VIRTUAL_DATA_SUBDIR")

        val directPackages = primaryRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()

        val legacyPackages = legacyRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()

        return (directPackages + legacyPackages)
            .filter { it.contains('.') }
            .distinct()
            .sorted()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Directory listing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists entries at [virtualPath] inside [packageName]'s virtual data directory.
     *
     * [virtualPath] is relative to the package root, e.g.:
     * - `""` or `"/"` → lists the virtual root itself
     * - `"files/"` → lists the `files/` sub-directory
     *
     * Returns an empty list if [virtualPath] does not exist or is not a directory.
     */
    fun list(packageName: String, virtualPath: String = ""): List<VirtualFsEntry> {
        val base   = File(virtualDataRoot(packageName))
        val target = if (virtualPath.isBlank() || virtualPath == "/") {
            base
        } else {
            File(base, virtualPath.trimStart('/'))
        }
        if (!target.isDirectory) return emptyList()
        return target.listFiles()?.map { f ->
            VirtualFsEntry(
                name        = f.name,
                path        = f.relativeTo(base).path,
                isDirectory = f.isDirectory,
                sizeBytes   = if (f.isFile) f.length() else 0L
            )
        } ?: emptyList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  File I/O
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads [virtualPath] inside [packageName]'s virtual root as raw bytes.
     *
     * @return file content, or `null` if the path does not exist or is a directory
     */
    fun readFile(packageName: String, virtualPath: String): ByteArray? {
        val file = File(virtualDataRoot(packageName), virtualPath.trimStart('/'))
        return if (file.isFile) file.readBytes() else null
    }

    /**
     * Writes [content] to [virtualPath] inside [packageName]'s virtual root.
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeFile(packageName: String, virtualPath: String, content: ByteArray): Boolean =
        runCatching {
            val file = File(virtualDataRoot(packageName), virtualPath.trimStart('/'))
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            true
        }.getOrDefault(false)

    /**
     * Deletes [virtualPath] (file or directory tree) inside [packageName]'s virtual root.
     *
     * @return `true` if the path no longer exists after the call
     */
    fun delete(packageName: String, virtualPath: String): Boolean {
        val file = File(virtualDataRoot(packageName), virtualPath.trimStart('/'))
        return file.deleteRecursively()
    }

    /**
     * Returns `true` if [virtualPath] exists inside [packageName]'s virtual root.
     */
    fun exists(packageName: String, virtualPath: String): Boolean =
        File(virtualDataRoot(packageName), virtualPath.trimStart('/')).exists()

    // ─────────────────────────────────────────────────────────────────────────
    //  Bulk import / export
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    //  System-path directories  (VIRTUAL_FS strategy)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the path that mirrors `/data/data/<packageName>/` inside the
     * app's own sandbox:
     * `<appFilesRoot>/data/data/<packageName>`
     *
     * Used exclusively by the [com.smapifan.androidmodder.model.DataAccessStrategy.VIRTUAL_FS]
     * strategy so all game data stays within Android-Modder's private files directory.
     */
    fun virtualDataDataRoot(packageName: String): String =
        "$appFilesRoot/data/data/$packageName"

    /**
     * Returns the path that mirrors `/data/<packageName>/` inside the app's
     * own sandbox:
     * `<appFilesRoot>/data/<packageName>`
     *
     * Used exclusively by the [com.smapifan.androidmodder.model.DataAccessStrategy.VIRTUAL_FS]
     * strategy so all game data stays within Android-Modder's private files directory.
     */
    fun virtualDataRootForApp(packageName: String): String =
        "$appFilesRoot/data/$packageName"

    /**
     * Ensures both virtual system directories for [packageName] exist:
     * - `<appFilesRoot>/data/data/<packageName>/`
     * - `<appFilesRoot>/data/<packageName>/`
     *
     * @return `true` if both directories exist or were created successfully
     */
    fun ensureVirtualSystemDirs(packageName: String): Boolean {
        val dataDataDir = File(virtualDataDataRoot(packageName))
        val dataDir     = File(virtualDataRootForApp(packageName))
        return (dataDataDir.exists() || dataDataDir.mkdirs()) &&
               (dataDir.exists() || dataDir.mkdirs())
    }

    /**
     * Lists all guest packages that have a virtual `data/data/` directory.
     *
     * Scans `<appFilesRoot>/data/data/` and returns the package names found
     * there (directories whose names contain a dot).
     *
     * @return sorted list of package names
     */
    fun listSystemPackages(): List<String> =
        File("$appFilesRoot/data/data").listFiles()
            ?.filter { it.isDirectory && it.name.contains('.') }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    //  System-path I/O  (paths relative to appFilesRoot)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists entries at [systemPath] relative to [appFilesRoot].
     *
     * [systemPath] mirrors a real Android path, e.g.:
     * - `""` or `"/"` → lists [appFilesRoot] itself
     * - `"data/data"` → lists `<appFilesRoot>/data/data/`
     * - `"data/data/com.example.pkg"` → lists the package directory
     * - `"data/data/com.example.pkg/files"` → lists the `files/` sub-directory
     *
     * @return list of [VirtualFsEntry] items; empty if path does not exist or
     *         is not a directory
     */
    fun listAtSystemPath(systemPath: String): List<VirtualFsEntry> {
        val target = resolveSystemPath(systemPath)
        if (!target.isDirectory) return emptyList()
        val root = File(appFilesRoot)
        return target.listFiles()?.map { f ->
            VirtualFsEntry(
                name        = f.name,
                path        = f.relativeTo(root).path,
                isDirectory = f.isDirectory,
                sizeBytes   = if (f.isFile) f.length() else 0L
            )
        } ?: emptyList()
    }

    /**
     * Reads the file at [systemPath] (relative to [appFilesRoot]) as raw bytes.
     *
     * @return file content, or `null` if the path does not exist or is a directory
     */
    fun readSystemFile(systemPath: String): ByteArray? {
        val file = resolveSystemPath(systemPath)
        return if (file.isFile) file.readBytes() else null
    }

    /**
     * Writes [content] to [systemPath] (relative to [appFilesRoot]).
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeSystemFile(systemPath: String, content: ByteArray): Boolean =
        runCatching {
            val file = resolveSystemPath(systemPath)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            true
        }.getOrDefault(false)

    /**
     * Deletes the file or directory tree at [systemPath] (relative to [appFilesRoot]).
     *
     * @return `true` if the path no longer exists after the call
     */
    fun deleteAtSystemPath(systemPath: String): Boolean =
        resolveSystemPath(systemPath).deleteRecursively()

    /**
     * Returns `true` if [systemPath] (relative to [appFilesRoot]) exists.
     */
    fun existsAtSystemPath(systemPath: String): Boolean =
        resolveSystemPath(systemPath).exists()

    // ─────────────────────────────────────────────────────────────────────────
    //  System-path helper
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves [systemPath] against [appFilesRoot].
     *
     * An empty string or `"/"` resolves to [appFilesRoot] itself; any other
     * value is treated as a path segment relative to [appFilesRoot].
     */
    private fun resolveSystemPath(systemPath: String): File =
        if (systemPath.isBlank() || systemPath == "/") {
            File(appFilesRoot)
        } else {
            File(appFilesRoot, systemPath.trimStart('/'))
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Bulk import / export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies all files from [sourceDir] into the virtual data directory for
     * [packageName].
     *
     * Used during the **export** phase to pull game save data from the device
     * into the virtual filesystem (no root needed – the game copies its own data
     * to external storage first, or `run-as` is used, then this call ingests it).
     *
     * @return `true` if the copy completed without errors
     */
    fun importFromDirectory(packageName: String, sourceDir: File): Boolean {
        if (!sourceDir.isDirectory) return false
        val dest = File(virtualDataRoot(packageName))
        return runCatching {
            sourceDir.copyRecursively(dest, overwrite = true)
            true
        }.getOrDefault(false)
    }

    /**
     * Copies all files from the virtual data directory for [packageName] into
     * [destDir].
     *
     * Used during the **import** phase to push patched save data back to the
     * game's accessible storage.
     *
     * @return `true` if the copy completed without errors
     */
    fun exportToDirectory(packageName: String, destDir: File): Boolean {
        val src = File(virtualDataRoot(packageName))
        if (!src.isDirectory) return false
        return runCatching {
            src.copyRecursively(destDir, overwrite = true)
            true
        }.getOrDefault(false)
    }
}
