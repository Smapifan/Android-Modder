package com.smapifan.androidmodder

import com.smapifan.androidmodder.service.AppCatalogService
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

    val catalogPath = Path.of("src/main/resources/AppCatalog.json")
    val catalogService = AppCatalogService()
    val allApps = if (catalogPath.exists()) {
        catalogService.parse(catalogPath.readText())
    } else {
        emptyList()
    }

    // Example: show apps suitable for a 10-year-old (Family Link controls the actual install)
    val userAge = 10
    val ageFilteredApps = catalogService.filterByAge(allApps, userAge)

    println("Android-Modder starter")
    println("Workspace: $workspace")
    println("Loaded cheat definitions: ${cheats.size}")
    println("Detected extensions: ${service.listExtensions(workspace).size}")
    println()
    println("App catalog (${allApps.size} total, ${ageFilteredApps.size} suitable for users aged $userAge):")
    ageFilteredApps.forEach { app ->
        println("  [${app.category}] ${app.name}  ->  ${catalogService.playStoreUrl(app)}")
    }
    println()
    println("Note: Installation opens the official Play Store. Family Link approval remains active.")
}
