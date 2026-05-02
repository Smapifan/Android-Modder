// Represents a single file or directory entry inside the in-app virtual filesystem.
// Repräsentiert einen einzelnen Datei- oder Verzeichniseintrag im virtuellen In-App-Dateisystem.

package com.smapifan.androidmodder.model

/**
 * A single entry (file or directory) inside the in-app virtual filesystem.
 *
 * @param name        file or directory name (last path segment)
 * @param path        path relative to the package's virtual data root,
 *                    e.g. `files/save.dat` or `files`
 * @param isDirectory `true` for directories, `false` for regular files
 * @param sizeBytes   file size in bytes; `0` for directories
 */
data class VirtualFsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)
