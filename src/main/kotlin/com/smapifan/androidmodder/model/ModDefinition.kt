package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * A single patch entry inside a [ModDefinition].
 * Mirrors the structure of [CheatDefinition] so that mods can be applied
 * with the same [com.smapifan.androidmodder.service.CheatApplier] logic –
 * no external mod API required.
 */
@Serializable
data class ModPatch(
    /** Field in the save file to modify, e.g. "coins". */
    val field: String,
    /** Operation to apply. */
    val operation: CheatOperation,
    /** Amount for the operation. */
    val amount: Long
)

/**
 * Represents a user-supplied mod file (`*.mod`).
 *
 * A mod is a JSON file placed by the user in their workspace directory.
 * The app is the shell – it ships no mods. Anyone can create a `.mod`
 * file for any game without a special API.
 *
 * Example `.mod` file:
 * ```json
 * {
 *   "name": "InfiniteCoins",
 *   "gameId": "MergeDragons",
 *   "description": "Adds 10 000 coins and sets gems to 999",
 *   "patches": [
 *     { "field": "coins", "operation": "ADD",  "amount": 10000 },
 *     { "field": "gems",  "operation": "SET",  "amount": 999   }
 *   ]
 * }
 * ```
 */
@Serializable
data class ModDefinition(
    val name: String,
    val gameId: String,
    val description: String = "",
    val patches: List<ModPatch> = emptyList()
)
