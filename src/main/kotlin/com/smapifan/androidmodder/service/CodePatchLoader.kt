package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CodePatchConfig
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Finds, loads and applies JSON drop-in `*.codepatch` files from workspace. */
class CodePatchLoader(
    private val codePatcher: CodePatcher = CodePatcher(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    data class LoadedCodePatch(
        val path: Path,
        val config: CodePatchConfig
    )

    data class ApplyReport(
        val filesVisited: Int,
        val filesPatched: Int,
        val errors: List<String> = emptyList()
    )

    fun discover(workspace: Path): List<Path> {
        if (!Files.isDirectory(workspace)) return emptyList()
        Files.walk(workspace).use { stream ->
            return stream
                .filter { it.isRegularFile() && it.extension.equals("codepatch", ignoreCase = true) }
                .sorted()
                .toList()
        }
    }

    fun load(file: Path): CodePatchConfig = json.decodeFromString<CodePatchConfig>(file.readText())

    fun loadAll(workspace: Path): List<LoadedCodePatch> =
        discover(workspace).mapNotNull { path ->
            runCatching { LoadedCodePatch(path, load(path)) }.getOrNull()
        }

    fun applyForGame(workspace: Path, gameId: String, appWorkspace: Path = workspace.resolve(gameId)): ApplyReport {
        var filesVisited = 0
        var filesPatched = 0
        val errors = mutableListOf<String>()

        val configs = loadAll(workspace)
            .map { it.config }
            .filter { it.gameId == gameId }

        configs.forEach { config ->
            config.patches.forEach { patch ->
                if (config.targetFiles.isNotEmpty()) {
                    config.targetFiles.forEach { rel ->
                        val target = workspace.resolve(rel).normalize()
                        if (!target.startsWith(workspace) || !target.isRegularFile()) return@forEach
                        filesVisited++
                        when (val outcome = runCatching { codePatcher.patch(target, patch) }
                            .getOrElse { PatchOutcome.Error(it.message ?: "patch failed") }) {
                            is PatchOutcome.Patched -> filesPatched++
                            is PatchOutcome.Error -> errors += "${target}: ${outcome.message}"
                            else -> Unit
                        }
                    }
                } else {
                    val ext = config.extensions.map { it.removePrefix(".").lowercase() }.toSet()
                    val outcomes = runCatching { codePatcher.patchTree(appWorkspace, patch, ext) }
                        .getOrElse {
                            errors += "${config.name}/${patch.identifier}: ${it.message}"
                            emptyMap()
                        }
                    filesVisited += outcomes.size
                    filesPatched += outcomes.values.count { it is PatchOutcome.Patched }
                    outcomes.forEach { (path, outcome) ->
                        if (outcome is PatchOutcome.Error) errors += "$path: ${outcome.message}"
                    }
                }
            }
        }

        return ApplyReport(filesVisited = filesVisited, filesPatched = filesPatched, errors = errors)
    }
}
