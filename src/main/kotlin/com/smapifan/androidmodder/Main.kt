package com.smapifan.androidmodder

import com.smapifan.androidmodder.model.TriggerMode
import com.smapifan.androidmodder.service.AppCatalogService
import com.smapifan.androidmodder.service.CheatsConfigParser
import com.smapifan.androidmodder.service.I18nService
import com.smapifan.androidmodder.service.ModLoader
import com.smapifan.androidmodder.service.ModWorkspaceService
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

// ═════════════════════════════════════════════════════════════════════════════
//  ANSI colour helpers  (gracefully degrades on terminals without colour support)
// ═════════════════════════════════════════════════════════════════════════════

/** Returns an ANSI escape sequence, or an empty string when colours are disabled. */
private val colorsEnabled: Boolean = System.getenv("NO_COLOR") == null &&
    (System.console() != null || System.getenv("TERM") != null)

private fun ansi(code: String): String = if (colorsEnabled) "\u001B[${code}m" else ""

private val RESET   = ansi("0")
private val BOLD    = ansi("1")
private val DIM     = ansi("2")
private val CYAN    = ansi("36")
private val GREEN   = ansi("32")
private val YELLOW  = ansi("33")
private val BLUE    = ansi("34")
private val MAGENTA = ansi("35")
private val RED     = ansi("31")

// ─── printing helpers ───────────────────────────────────────────────────────

/** Prints a section header with a horizontal rule. */
private fun header(title: String) {
    val rule = "─".repeat(60)
    println()
    println("${CYAN}${BOLD}$rule${RESET}")
    println("${CYAN}${BOLD}  $title${RESET}")
    println("${CYAN}${BOLD}$rule${RESET}")
}

/** Prints a labelled key/value row. */
private fun row(label: String, value: Any, accent: String = GREEN) =
    println("  ${BOLD}${label}${RESET}  ${accent}${value}${RESET}")

/** Prints a bullet list item. */
private fun bullet(text: String, indent: Int = 4, accent: String = RESET) =
    println("${" ".repeat(indent)}${YELLOW}•${RESET}  ${accent}${text}${RESET}")

/** Prints a dimmed note line. */
private fun note(text: String) = println("  ${DIM}${text}${RESET}")

// ═════════════════════════════════════════════════════════════════════════════
//  Main entry point
// ═════════════════════════════════════════════════════════════════════════════

fun main(args: Array<String>) {
    val i18n = I18nService()

    // ── Parse CLI arguments ──────────────────────────────────────────────────
    // First positional arg = workspace path; flags start with "--"
    val workspaceArg = args.firstOrNull { !it.startsWith("--") } ?: "./workspace"
    val cleanMode    = args.contains("--clean") // flag: remove all mods instead of showing status

    val workspace        = Path.of(workspaceArg)
    val workspaceService = ModWorkspaceService()
    workspaceService.ensureWorkspace(workspace) // create workspace dir if it doesn't exist yet

    // ── Load cheats from per-game JSON files in cheats/ directory ───────────
    val cheatsDir = Path.of("src/main/resources/cheats")
    val parser = CheatsConfigParser()
    val cheats = if (cheatsDir.isDirectory()) {
        cheatsDir.listDirectoryEntries("*.json")
            .filter { it.isRegularFile() }
            .flatMap { parser.parse(it.readText()) }
    } else {
        emptyList()
    }

    // ── Load app catalog (optional file) ────────────────────────────────────
    val catalogPath    = Path.of("src/main/resources/AppCatalog.json")
    val catalogService = AppCatalogService()
    val allApps = if (catalogPath.exists()) {
        catalogService.parse(catalogPath.readText())
    } else {
        emptyList()
    }

    // Filter catalog to apps appropriate for the current user's age
    val userAge         = 10
    val ageFilteredApps = catalogService.filterByAge(allApps, userAge)

    // ── Discover .mod files per game in the workspace ────────────────────────
    val modLoader    = ModLoader()
    val detectedMods = if (workspace.isDirectory()) {
        workspace.listDirectoryEntries()
            .filter { it.isDirectory() }
            .flatMap { gameDir ->
                workspaceService.listModsForApp(workspace, gameDir.fileName.toString())
                    .mapNotNull { path -> runCatching { modLoader.load(path) }.getOrNull() }
            }
    } else {
        emptyList()
    }

    // Split detected mods by trigger mode for display / scheduling purposes
    val onLaunchMods   = detectedMods.filter { it.triggerMode == TriggerMode.ON_LAUNCH }
    val onDemandMods   = detectedMods.filter { it.triggerMode == TriggerMode.ON_DEMAND }
    val onAutosaveMods = detectedMods.filter { it.triggerMode == TriggerMode.ON_AUTOSAVE }

    // ─────────────────────────────────────────────────────────────────────────
    //  Banner
    // ─────────────────────────────────────────────────────────────────────────
    println()
    println("${MAGENTA}${BOLD}  ╔══════════════════════════════════════╗${RESET}")
    println("${MAGENTA}${BOLD}  ║       Android-Modder  🎮             ║${RESET}")
    println("${MAGENTA}${BOLD}  ║  Overlay-Wrapper · No Root Required  ║${RESET}")
    println("${MAGENTA}${BOLD}  ╚══════════════════════════════════════╝${RESET}")

    // ─────────────────────────────────────────────────────────────────────────
    //  --clean mode: delete all .mod files and exit
    //
    //  Because the launcher never patches the APK itself (mods only edit save
    //  files in the workspace), removing the .mod files is all that is needed
    //  to get a fully clean game.  The installed APK keeps its original
    //  Play-Store signature and data directory untouched.
    // ─────────────────────────────────────────────────────────────────────────
    if (cleanMode) {
        header(i18n.get("mod.clean.header"))
        val removed = if (workspace.isDirectory()) {
            workspace.listDirectoryEntries()
                .filter { it.isDirectory() }
                .sumOf { gameDir ->
                    workspaceService.removeModsForApp(workspace, gameDir.fileName.toString())
                }
        } else {
            0
        }
        if (removed == 0) {
            // No .mod files were present — workspace is already clean
            note("${GREEN}✔${RESET}  ${i18n.get("mod.clean.none")}")
        } else {
            // Deleted one or more .mod files; game runs unmodified next launch
            note("${GREEN}✔${RESET}  ${i18n.format("mod.clean.removed", removed)}")
        }
        println()
        return // Exit after cleaning — no need to print the full status report
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Workspace & configuration summary
    // ─────────────────────────────────────────────────────────────────────────
    header("${i18n.get("app.workspace")} & Configuration")
    row(i18n.get("app.workspace"),           workspace)
    row(i18n.get("app.cheats.loaded"),        cheats.size)
    row(i18n.get("app.extensions.detected"),  workspaceService.listExtensions(workspace).size)
    row(i18n.get("app.mods.detected"),        detectedMods.size)

    // ─────────────────────────────────────────────────────────────────────────
    //  Active mods breakdown (only shown when at least one .mod file exists)
    // ─────────────────────────────────────────────────────────────────────────
    if (detectedMods.isNotEmpty()) {
        header("Active Mods")

        // ON_LAUNCH: patches are applied once, right before the game starts
        if (onLaunchMods.isNotEmpty()) {
            println("  ${GREEN}${BOLD}${i18n.get("overlay.trigger.on_launch")}${RESET}")
            onLaunchMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}  – ${mod.description.ifBlank { "no description" }}")
            }
        }

        // ON_DEMAND: each mod exposes one or more overlay buttons the user taps
        if (onDemandMods.isNotEmpty()) {
            println()
            println("  ${BLUE}${BOLD}${i18n.get("overlay.trigger.on_demand")}${RESET}")
            onDemandMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}", accent = BLUE)
                // Show the individual actions (buttons) this mod provides
                mod.overlayActions.forEach { action ->
                    println("      ${DIM}▸  ${action.label}  →  fields: ${action.patchFields.joinToString()}${RESET}")
                }
            }
        }

        // ON_AUTOSAVE: patches are re-applied automatically at a fixed interval
        if (onAutosaveMods.isNotEmpty()) {
            println()
            println("  ${YELLOW}${BOLD}${i18n.get("overlay.trigger.on_autosave")}${RESET}")
            onAutosaveMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}", accent = YELLOW)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  How it works (short explanation for new users)
    // ─────────────────────────────────────────────────────────────────────────
    header("How It Works")
    note(i18n.get("cheat.how.it.works"))
    println()
    note("${GREEN}✔${RESET}  ${i18n.get("overlay.no.root.note")}")
    note("${YELLOW}⚠${RESET}  ${i18n.get("export.internal.note")}")
    note("${GREEN}✔${RESET}  ${i18n.get("export.external.note")}")
    println()
    note("${BLUE}ℹ${RESET}  ${i18n.get("overlay.permission.note")}")

    // ─────────────────────────────────────────────────────────────────────────
    //  App catalog (age-filtered list with Play Store links)
    // ─────────────────────────────────────────────────────────────────────────
    header(i18n.format("app.catalog.title", allApps.size, ageFilteredApps.size, userAge))
    if (ageFilteredApps.isEmpty()) {
        note("(no apps in catalog)")
    } else {
        ageFilteredApps.forEach { app ->
            bullet("[${app.category}]  ${app.name}  →  ${catalogService.playStoreUrl(app)}", accent = CYAN)
        }
    }
    println()
    note(i18n.get("app.catalog.install.note"))
    note(i18n.get("mod.shell.note"))

    println()
}
