// In-app file browser and manager for navigating and modifying guest-app data via the virtual filesystem.
// In-App-Dateibrowser und -Manager zum Navigieren und Ändern von Gast-App-Daten über das virtuelle Dateisystem.

package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.BrowserEntry

// ═════════════════════════════════════════════════════════════════════════════
//  InAppBrowserService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * In-app file browser / manager over the virtual filesystem.
 *
 * Provides a tree-navigable view of each guest app's virtual data directory
 * (managed by [VirtualFileSystemService]).  The browser allows users to:
 *
 * - Navigate directories inside the virtual FS.
 * - Read and edit text / binary files.
 * - Delete files or directory trees.
 * - Get a flat list of all virtualised guest packages.
 * - Render a human-readable tree for display in a terminal or UI.
 *
 * All paths are confined to the host app's own sandbox; no root access is needed.
 *
 * ## Path conventions
 *
 * - An **empty string** or `"/"` refers to the package's virtual root.
 * - All other paths are relative to that root, e.g. `"files/save.dat"` or
 *   `"files"` (leading slash is stripped automatically).
 *
 * @param vfs the [VirtualFileSystemService] that owns the underlying files
 */
class InAppBrowserService(
    private val vfs: VirtualFileSystemService = VirtualFileSystemService()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Navigation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists entries at [virtualPath] inside [packageName]'s virtual data directory.
     *
     * Directories are listed before files; both groups are sorted alphabetically.
     *
     * @param packageName  guest app package name, e.g. `"com.gram.mergedragons"`
     * @param virtualPath  path relative to the package root (default: root)
     * @return sorted list of [BrowserEntry] items; empty if the path does not exist
     */
    fun browse(packageName: String, virtualPath: String = ""): List<BrowserEntry> =
        vfs.list(packageName, virtualPath)
            .map { entry ->
                BrowserEntry(
                    name        = entry.name,
                    virtualPath = entry.path,
                    isDirectory = entry.isDirectory,
                    sizeBytes   = entry.sizeBytes,
                    packageName = packageName
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

    /**
     * Returns a flat list of all virtualised guest packages.
     *
     * @return sorted list of package name strings
     */
    fun listPackages(): List<String> = vfs.listInstalledPackages()

    // ─────────────────────────────────────────────────────────────────────────
    //  File I/O
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the content of a file at [virtualPath] inside [packageName]'s
     * virtual root as a UTF-8 string.
     *
     * @return file content string, or `null` if the file does not exist
     */
    fun readTextFile(packageName: String, virtualPath: String): String? =
        vfs.readFile(packageName, virtualPath)?.decodeToString()

    /**
     * Reads the content of a file at [virtualPath] as raw bytes.
     *
     * @return file content bytes, or `null` if the file does not exist
     */
    fun readBinaryFile(packageName: String, virtualPath: String): ByteArray? =
        vfs.readFile(packageName, virtualPath)

    /**
     * Writes [text] (UTF-8) to [virtualPath] inside [packageName]'s virtual root.
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeTextFile(packageName: String, virtualPath: String, text: String): Boolean =
        vfs.writeFile(packageName, virtualPath, text.toByteArray(Charsets.UTF_8))

    /**
     * Writes [bytes] to [virtualPath] inside [packageName]'s virtual root.
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeBinaryFile(packageName: String, virtualPath: String, bytes: ByteArray): Boolean =
        vfs.writeFile(packageName, virtualPath, bytes)

    /**
     * Deletes [virtualPath] (file or directory tree) from [packageName]'s
     * virtual root.
     *
     * @return `true` if the path no longer exists after the call
     */
    fun delete(packageName: String, virtualPath: String): Boolean =
        vfs.delete(packageName, virtualPath)

    // ─────────────────────────────────────────────────────────────────────────
    //  Display
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a human-readable directory tree for [packageName] starting at
     * [virtualPath].
     *
     * Example output:
     * ```
     * 📁 files
     *   📄 save.dat  (1024 bytes)
     *   📄 config.json  (256 bytes)
     * 📁 shared_prefs
     *   📄 settings.xml  (512 bytes)
     * ```
     *
     * @param packageName guest app package name
     * @param virtualPath starting path (default: root of the virtual data dir)
     * @param indent      current indentation level (used for recursive calls)
     * @return multi-line string representation of the directory tree
     */
    fun buildTree(
        packageName: String,
        virtualPath: String = "",
        indent: Int = 0
    ): String = buildString {
        for (entry in browse(packageName, virtualPath)) {
            val prefix = "  ".repeat(indent) + if (entry.isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 "
            val size   = if (!entry.isDirectory) "  (${entry.sizeBytes} bytes)" else ""
            appendLine("$prefix${entry.name}$size")
            if (entry.isDirectory) {
                append(buildTree(packageName, entry.virtualPath, indent + 1))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  System-path "root browser"  (VIRTUAL_FS — data/ and data/data/ layout)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lists entries at [systemPath] inside the virtual system tree.
     *
     * [systemPath] is a path **relative to the app's files root** that mirrors
     * the real Android directory hierarchy, for example:
     *
     * | systemPath                        | Equivalent real path              |
     * |-----------------------------------|-----------------------------------|
     * | `""` or `"/"`                     | `<appFilesRoot>/`                 |
     * | `"data"`                          | virtual `/data/`                  |
     * | `"data/data"`                     | virtual `/data/data/`             |
     * | `"data/data/com.example.pkg"`     | virtual `/data/data/com.example.pkg/` |
     * | `"data/data/com.example.pkg/files"` | files sub-directory             |
     *
     * Directories are listed before files; both groups are sorted alphabetically.
     *
     * All data is confined to Android-Modder's own sandbox; no root access is
     * needed.
     *
     * @param systemPath path relative to the app files root (default: root)
     * @return sorted list of [BrowserEntry] items; empty if the path does not exist
     */
    fun browseSystemPath(systemPath: String = ""): List<BrowserEntry> =
        vfs.listAtSystemPath(systemPath)
            .map { entry ->
                BrowserEntry(
                    name        = entry.name,
                    virtualPath = entry.path,
                    isDirectory = entry.isDirectory,
                    sizeBytes   = entry.sizeBytes,
                    packageName = extractPackageNameFromSystemPath(entry.path)
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

    /**
     * Returns a flat list of all packages that have a virtual `data/data/`
     * directory in the app sandbox.
     *
     * @return sorted list of package name strings
     */
    fun listSystemPackages(): List<String> = vfs.listSystemPackages()

    /**
     * Reads the content of the file at [systemPath] (relative to the app files
     * root) as a UTF-8 string.
     *
     * @return file content string, or `null` if the file does not exist
     */
    fun readSystemTextFile(systemPath: String): String? =
        vfs.readSystemFile(systemPath)?.decodeToString()

    /**
     * Reads the content of the file at [systemPath] as raw bytes.
     *
     * @return file content bytes, or `null` if the file does not exist
     */
    fun readSystemBinaryFile(systemPath: String): ByteArray? =
        vfs.readSystemFile(systemPath)

    /**
     * Writes [text] (UTF-8) to the file at [systemPath] (relative to the app
     * files root).
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeSystemTextFile(systemPath: String, text: String): Boolean =
        vfs.writeSystemFile(systemPath, text.toByteArray(Charsets.UTF_8))

    /**
     * Writes [bytes] to the file at [systemPath] (relative to the app files root).
     *
     * Parent directories are created automatically.
     *
     * @return `true` if the write succeeded
     */
    fun writeSystemBinaryFile(systemPath: String, bytes: ByteArray): Boolean =
        vfs.writeSystemFile(systemPath, bytes)

    /**
     * Deletes the file or directory tree at [systemPath] (relative to the app
     * files root).
     *
     * @return `true` if the path no longer exists after the call
     */
    fun deleteAtSystemPath(systemPath: String): Boolean =
        vfs.deleteAtSystemPath(systemPath)

    /**
     * Builds a human-readable directory tree for the virtual system tree
     * starting at [systemPath].
     *
     * Example output (starting at `"data/data/com.example.pkg"`):
     * ```
     * 📁 files
     *   📄 save.dat  (1024 bytes)
     * 📁 shared_prefs
     *   📄 prefs.xml  (512 bytes)
     * ```
     *
     * All paths are confined to the app's own sandbox.
     *
     * @param systemPath starting path relative to the app files root (default: root)
     * @param indent     current indentation level (used for recursive calls)
     * @return multi-line string representation of the directory tree
     */
    fun buildSystemTree(systemPath: String = "", indent: Int = 0): String = buildString {
        for (entry in browseSystemPath(systemPath)) {
            val prefix = "  ".repeat(indent) + if (entry.isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 "
            val size   = if (!entry.isDirectory) "  (${entry.sizeBytes} bytes)" else ""
            appendLine("$prefix${entry.name}$size")
            if (entry.isDirectory) {
                append(buildSystemTree(entry.virtualPath, indent + 1))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the guest package name from a system path such as
     * `data/data/com.example.pkg/files` → `"com.example.pkg"`.
     *
     * Returns an empty string for paths that do not contain a package segment.
     */
    private fun extractPackageNameFromSystemPath(systemPath: String): String {
        val parts = systemPath.trimStart('/').split('/')
        return when {
            parts.size >= 3 && parts[0] == "data" && parts[1] == "data" -> parts[2]
            parts.size >= 2 && parts[0] == "data"                       -> parts[1]
            else                                                         -> ""
        }
    }
}
