package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.CheatOperation
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
 * the copy of the save file that the user exported into their workspace.
 *
 * Example save file content:
 * ```
 * coins=500
 * gems=10
 * lives=3
 * ```
 *
 * A cheat "ADD 1000 to coins" would result in:
 * ```
 * coins=1500
 * gems=10
 * lives=3
 * ```
 */
class CheatApplier {

    /**
     * Applies [cheat] to the save file at [saveFilePath] and returns the
     * new field value after the operation.
     *
     * @throws IllegalArgumentException if [saveFilePath] does not exist.
     */
    fun apply(saveFilePath: Path, cheat: CheatDefinition): Long {
        require(saveFilePath.isRegularFile()) {
            "Save file not found in workspace: $saveFilePath"
        }

        val fields = readFields(saveFilePath)
        val current = fields[cheat.field]?.toLongOrNull() ?: 0L
        val newValue = when (cheat.operation) {
            CheatOperation.ADD      -> current + cheat.amount
            CheatOperation.SUBTRACT -> maxOf(0L, current - cheat.amount)
            CheatOperation.SET      -> cheat.amount
        }
        fields[cheat.field] = newValue.toString()
        writeFields(saveFilePath, fields)
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
}
