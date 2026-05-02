// JSON drop-in configuration container holding a list of code patch definitions for a specific game.
// JSON-Konfigurationscontainer mit einer Liste von Code-Patch-Definitionen für ein bestimmtes Spiel.

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * JSON drop-in format for a list of [CodePatchDefinition] entries.
 *
 * Users can place `*.codepatch` files in the workspace and the launcher
 * discovers/applies them automatically before game launch.
 */
@Serializable
data class CodePatchConfig(
    val name: String,
    val gameId: String,
    val description: String = "",
    val targetFiles: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val patches: List<CodePatchDefinition> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "CodePatchConfig.name must not be blank" }
        require(gameId.isNotBlank()) { "CodePatchConfig.gameId must not be blank" }
        require(patches.isNotEmpty()) { "CodePatchConfig.patches must not be empty" }
        require(targetFiles.none { it.isBlank() }) { "CodePatchConfig.targetFiles must not contain blank entries" }
        require(extensions.none { it.isBlank() || it == "*" || it == ".*" }) {
            "CodePatchConfig.extensions must not contain blank or wildcard-only entries"
        }
    }
}
