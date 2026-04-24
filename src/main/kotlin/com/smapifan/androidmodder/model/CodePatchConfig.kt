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
)
