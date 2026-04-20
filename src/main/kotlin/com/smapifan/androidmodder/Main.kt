package com.smapifan.androidmodder

import com.smapifan.androidmodder.service.AppCatalogService
import com.smapifan.androidmodder.service.CheatsConfigParser
import com.smapifan.androidmodder.service.I18nService
import com.smapifan.androidmodder.service.ModWorkspaceService
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val i18n = I18nService()

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

    println(i18n.get("app.title"))
    println("${i18n.get("app.workspace")}: $workspace")
    println("${i18n.get("app.cheats.loaded")}: ${cheats.size}")
    println("${i18n.get("app.extensions.detected")}: ${service.listExtensions(workspace).size}")
    println("${i18n.get("app.mods.detected")}: ${service.listMods(workspace).size}")
    println()
    println(i18n.format("app.catalog.title", allApps.size, ageFilteredApps.size, userAge))
    ageFilteredApps.forEach { app ->
        println("  [${app.category}] ${app.name}  ->  ${catalogService.playStoreUrl(app)}")
    }
    println()
    println(i18n.get("app.catalog.install.note"))
    println(i18n.get("mod.shell.note"))
}
