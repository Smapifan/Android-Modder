package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.ModDefinition
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Loads and applies user-supplied mod files (`*.mod`) from the workspace.
 *
 * A `.mod` file is a plain JSON file that anyone can create for any game –
 * no special mod API is needed. The app is the shell; mods are community
 * content placed by the user in their chosen workspace directory.
 *
 * Mod patches are applied using the same file-based [CheatApplier] logic as
 * built-in cheats: only the save file copy in the workspace is ever modified.
 * The real game is **never** patched.
 */
class ModLoader(private val cheatApplier: CheatApplier = CheatApplier()) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a `.mod` JSON file at [modPath] and returns the [ModDefinition].
     */
    fun load(modPath: Path): ModDefinition =
        json.decodeFromString<ModDefinition>(modPath.readText())

    /**
     * Applies all patches of [mod] to save files inside [appWorkspaceDir].
     *
     * Each patch is treated like a cheat: [CheatApplier] searches the whole
     * workspace directory tree for the named field and applies the operation.
     *
     * Returns a map of field name → new value for every patch applied.
     */
    fun applyMod(mod: ModDefinition, appWorkspaceDir: Path): Map<String, Long> {
        val results = LinkedHashMap<String, Long>()
        mod.patches.forEach { patch ->
            val syntheticCheat = CheatDefinition(
                appName   = mod.gameId,
                field     = patch.field,
                operation = patch.operation,
                amount    = patch.amount
            )
            results[patch.field] = cheatApplier.apply(appWorkspaceDir, syntheticCheat)
        }
        return results
    }
}
