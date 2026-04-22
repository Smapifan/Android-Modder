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
 * <appFilesRoot>/vdata/<packageName>/   ← virtual data root for <packageName>
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
 *   vdata/
 *     com.gram.mergedragons/
 *       files/
 *         save.dat
 *     com.kiloo.subwaysurf/
 *       files/
 *         playerData.dat
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

        /** Sub-directory name inside [appFilesRoot] that holds all virtual data. */
        const val VIRTUAL_DATA_SUBDIR = "vdata"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the virtual data root for [packageName]:
     * `<appFilesRoot>/vdata/<packageName>/`
     */
    fun virtualDataRoot(packageName: String): String =
        "$appFilesRoot/$VIRTUAL_DATA_SUBDIR/$packageName"

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
        val root = File("$appFilesRoot/$VIRTUAL_DATA_SUBDIR")
        if (!root.isDirectory) return emptyList()
        return (root.list()?.toList() ?: emptyList()).sorted()
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
