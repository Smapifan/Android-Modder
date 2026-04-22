package com.smapifan.androidmodder.model

/**
 * A single entry in the in-app file browser / file manager.
 *
 * Entries are produced by [com.smapifan.androidmodder.service.InAppBrowserService]
 * and represent files or directories inside a guest app's virtual data directory.
 *
 * @param name        file or directory name (last path segment)
 * @param virtualPath path relative to the package's virtual data root,
 *                    e.g. `files/save.dat` or `files`
 * @param isDirectory `true` for directories, `false` for regular files
 * @param sizeBytes   file size in bytes; `0` for directories
 * @param packageName the guest app package this entry belongs to,
 *                    e.g. `"com.gram.mergedragons"`
 */
data class BrowserEntry(
    val name: String,
    val virtualPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val packageName: String
)
