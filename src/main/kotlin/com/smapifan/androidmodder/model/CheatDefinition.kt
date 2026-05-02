// Data class defining a single cheat operation (ADD/SUBTRACT/SET) for a named save-file field.
// Datenklasse, die eine einzelne Cheat-Operation (ADD/SUBTRACT/SET) für ein benanntes Spielstand-Feld definiert.

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Defines a single cheat operation for a specific app.
 *
 * Cheats are purely file-based: they read a named [field] from the app's
 * save file in the workspace, apply an [operation] (ADD / SUBTRACT / SET)
 * with the given [amount], and write the result back.
 *
 * The real game binary is **never** modified.
 *
 * Example cheats:
 * - ADD 1000 to "coins"        → adds 1 000 coins
 * - SUBTRACT 1000 from "coins" → removes 1 000 coins (minimum 0)
 * - SET 9999 to "lives"        → sets lives to exactly 9 999
 */
@Serializable
data class CheatDefinition(
    /** Game / app identifier, e.g. "MergeDragons". */
    val appName: String,
    /** The field name inside the save file to modify, e.g. "coins" or "gems". */
    val field: String,
    /** Whether to add, subtract, or set the value. */
    val operation: CheatOperation,
    /** The amount to apply, e.g. 1000. */
    val amount: Long
)
