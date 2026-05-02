// Enum of arithmetic operations a cheat can apply to a save-file field (ADD, SUBTRACT, SET).
// Aufzählung der arithmetischen Operationen, die ein Cheat auf ein Spielstand-Feld anwenden kann (ADD, SUBTRACT, SET).

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/** The kind of operation a cheat performs on a named save-file field. */
@Serializable
enum class CheatOperation {
    /** Adds [CheatDefinition.amount] to the current field value. */
    ADD,

    /** Subtracts [CheatDefinition.amount] from the current field value (floor: 0). */
    SUBTRACT,

    /** Replaces the current field value with [CheatDefinition.amount] exactly. */
    SET
}
