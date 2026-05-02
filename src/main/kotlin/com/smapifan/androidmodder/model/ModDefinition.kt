// Data class representing a user-supplied mod file with field-level patches and floating overlay actions.
// Datenklasse, die eine benutzerdefinierte Mod-Datei mit Feld-Patches und schwebenden Overlay-Aktionen repräsentiert.

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * A single patch entry inside a [ModDefinition].
 *
 * Mirrors the structure of [CheatDefinition] so that mods are applied with the
 * same [com.smapifan.androidmodder.service.CheatApplier] logic.  No external
 * mod API is required – the format is plain JSON.
 *
 * @see SaveDataAction for the companion save-data export/import concept.
 */
@Serializable
data class ModPatch(
    /** Field inside the save file to modify, e.g. `"coins"`. */
    val field: String,
    /** Arithmetic operation to perform on the current field value. */
    val operation: CheatOperation,
    /** Operand for the operation (e.g. 10 000 for ADD 10 000 coins). */
    val amount: Long
)

/**
 * Represents a user-supplied mod file (`*.mod`).
 *
 * A mod is a plain JSON file that anyone can write for any game – no special
 * API or code-signing is needed.  The app is the *shell*; mods are community
 * content that users drop into their workspace directory.
 *
 * ## Extended format example
 * ```json
 * {
 *   "name":        "InfiniteCoins",
 *   "gameId":      "com.gram.mergedragons",
 *   "description": "Overlay-Mod: Adds coins on button press",
 *   "triggerMode": "ON_DEMAND",
 *   "saveDataAction": "IMPORT",
 *   "patches": [
 *     { "field": "coins", "operation": "ADD", "amount": 10000 },
 *     { "field": "gems",  "operation": "SET", "amount": 999   }
 *   ],
 *   "overlayActions": [
 *     { "label": "+10k Coins", "patchFields": ["coins"] },
 *     { "label": "Max Gems",   "patchFields": ["gems"]  }
 *   ]
 * }
 * ```
 *
 * @param name           Human-readable mod name.
 * @param gameId         Android package name this mod targets, e.g. `"com.gram.mergedragons"`.
 * @param description    Optional description shown in the UI.
 * @param triggerMode    Controls *when* patches fire (default: [TriggerMode.ON_LAUNCH]).
 * @param saveDataAction Optional directive to import or export save data as part of this
 *                       mod's lifecycle.  `null` means the launcher's default behaviour
 *                       (governed by [com.smapifan.androidmodder.model.GameLaunchConfig])
 *                       applies.
 * @param patches        List of field-level patch operations to apply.
 * @param overlayActions Buttons to display in the floating overlay HUD when
 *                       [triggerMode] is [TriggerMode.ON_DEMAND].
 */
@Serializable
data class ModDefinition(
    val name: String,
    val gameId: String,
    val description: String = "",

    /**
     * When to apply this mod's patches.
     * - [TriggerMode.ON_LAUNCH]   – applied once before the game starts (default)
     * - [TriggerMode.ON_DEMAND]   – applied when user taps an overlay button
     * - [TriggerMode.ON_AUTOSAVE] – re-applied periodically while game runs
     */
    val triggerMode: TriggerMode = TriggerMode.ON_LAUNCH,

    /**
     * Optional save-data directive for this mod.
     * - [SaveDataAction.IMPORT] – push workspace data → external storage after patches
     * - [SaveDataAction.EXPORT] – pull data from external storage → workspace before patches
     * - `null` – defer to [com.smapifan.androidmodder.model.GameLaunchConfig] (default)
     */
    val saveDataAction: SaveDataAction? = null,

    /** Field-level patches that this mod applies to the save files. */
    val patches: List<ModPatch> = emptyList(),

    /**
     * Overlay HUD buttons for [TriggerMode.ON_DEMAND] mods.
     * Each action activates a subset of [patches].
     * Ignored when [triggerMode] is not [TriggerMode.ON_DEMAND].
     */
    val overlayActions: List<OverlayAction> = emptyList()
)
