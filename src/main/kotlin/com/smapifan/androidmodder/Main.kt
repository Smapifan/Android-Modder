package com.smapifan.androidmodder

import com.smapifan.androidmodder.service.CheatsConfigParser
import com.smapifan.androidmodder.service.ModWorkspaceService
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val workspaceArg = args.firstOrNull() ?: "./workspace"
    val workspace = Path.of(workspaceArg)

    val service = ModWorkspaceService()
    service.ensureWorkspace(workspace)

    val cheatsPath = Path.of("src/main/resources/Cheats.json")
    val cheats = if (cheatsPath.exists()) {
        CheatsConfigParser().parse(cheatsPath.readText())
    } else {
        emptyList()
    }

    println("Android-Modder starter")
    println("Workspace: $workspace")
    println("Loaded cheat definitions: ${cheats.size}")
    println("Detected extensions: ${service.listExtensions(workspace).size}")
    println("Note: This project intentionally supports file-based workflows only.")
}
