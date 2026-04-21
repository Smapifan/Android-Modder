package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.CheatOperation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Applies cheat operations to save files stored in the workspace.
 *
 * Save files are treated as **key=value text files** (one entry per line),
 * which covers the most common save formats used by mobile games.
 *
 * No game binary is ever read or modified – all edits happen exclusively on
 * the copy of the app data that the user exported into their workspace via
 * [ModWorkspaceService.exportAppData].
 *
 * The workspace mirrors the Android data directory structure:
 * ```
 * <workspace>/<appName>/
 *   data/data/<appName>/   ← from /data/data/<appName>/
 *   data/<appName>/        ← from /data/<appName>/
 * ```
 *
 * [apply] searches the whole workspace tree for the field automatically –
 * no save file path needs to be specified.
 *
 * Example save file content:
 * ```
 * coins=500
 * gems=10
 * lives=3
 * ```
 * Cheat "ADD 1000 to coins" → `coins=1500`
 */
class CheatApplier {

    /**
     * Applies [cheat] to the first file inside [appWorkspaceDir] that contains
     * the named field. If the field is not yet present in any file, it is added
     * to the first available save file starting from 0.
     *
     * @throws IllegalArgumentException if [appWorkspaceDir] is not a directory.
     * @throws IllegalStateException    if no save files exist in the workspace at all.
     */
    fun apply(appWorkspaceDir: Path, cheat: CheatDefinition): Long {
        require(Files.isDirectory(appWorkspaceDir)) {
            "App workspace directory does not exist: $appWorkspaceDir"
        }

        // Prefer a file that already contains the field; fall back to any file.
        val saveFile = findFileWithField(appWorkspaceDir, cheat.field)
            ?: findAnyFile(appWorkspaceDir)
            ?: throw IllegalStateException(
                "No save files found in workspace for '${cheat.appName}'. " +
                "Export the app data first with exportAppData() or exportExternalData()."
            )

        val fields = readFields(saveFile)
        val current = fields[cheat.field]?.toLongOrNull() ?: 0L
        val newValue = when (cheat.operation) {
            CheatOperation.ADD      -> current + cheat.amount
            CheatOperation.SUBTRACT -> maxOf(0L, current - cheat.amount)
            CheatOperation.SET      -> cheat.amount
        }
        fields[cheat.field] = newValue.toString()
        writeFields(saveFile, fields)
        return newValue
    }

    // --- save file I/O ----------------------------------------------------

    internal fun readFields(path: Path): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        path.readText().lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx < 0) return@forEach
            val key   = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            map[key] = value
        }
        return map
    }

    internal fun writeFields(path: Path, fields: Map<String, String>) {
        val content = fields.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        path.writeText(content)
    }

    /**
     * Recursively searches [dir] for the first regular text file that
     * contains a key=value line whose key matches [field].
     */
    internal fun findFileWithField(dir: Path, field: String): Path? {
        Files.walk(dir).use { stream ->
            return stream
                .toList()
                .filter { it.isRegularFile() }
                .sortedBy { it.toString() }
                .firstOrNull { path ->
                    runCatching { readFields(path).containsKey(field) }.getOrDefault(false)
                }
        }
    }

    /** Returns the first regular file found anywhere under [dir], or null if none exists. */
    internal fun findAnyFile(dir: Path): Path? {
        Files.walk(dir).use { stream ->
            return stream
                .toList()
                .filter { it.isRegularFile() }
                .sortedBy { it.toString() }
                .firstOrNull()
        }
    }
}
