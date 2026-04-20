package com.smapifan.androidmodder

import com.smapifan.androidmodder.model.TriggerMode
import com.smapifan.androidmodder.service.AppCatalogService
import com.smapifan.androidmodder.service.CheatsConfigParser
import com.smapifan.androidmodder.service.I18nService
import com.smapifan.androidmodder.service.ModLoader
import com.smapifan.androidmodder.service.ModWorkspaceService
import java.nio.file.Path
import kotlin.io.path.exists
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

    // ── workspace ────────────────────────────────────────────────────────────
    val workspaceArg = args.firstOrNull() ?: "./workspace"
    val workspace    = Path.of(workspaceArg)

    val workspaceService = ModWorkspaceService()
    workspaceService.ensureWorkspace(workspace)

    // ── cheats ───────────────────────────────────────────────────────────────
    val cheatsPath = Path.of("src/main/resources/Cheats.json")
    val cheats = if (cheatsPath.exists()) {
        CheatsConfigParser().parse(cheatsPath.readText())
    } else {
        emptyList()
    }

    // ── app catalog ──────────────────────────────────────────────────────────
    val catalogPath    = Path.of("src/main/resources/AppCatalog.json")
    val catalogService = AppCatalogService()
    val allApps = if (catalogPath.exists()) {
        catalogService.parse(catalogPath.readText())
    } else {
        emptyList()
    }

    val userAge       = 10
    val ageFilteredApps = catalogService.filterByAge(allApps, userAge)

    // ── mods summary ────────────────────────────────────────────────────────
    val modLoader = ModLoader()
    val detectedMods = workspaceService.listMods(workspace).mapNotNull { path ->
        runCatching { modLoader.load(path) }.getOrNull()
    }
    val onLaunchMods    = detectedMods.filter { it.triggerMode == TriggerMode.ON_LAUNCH }
    val onDemandMods    = detectedMods.filter { it.triggerMode == TriggerMode.ON_DEMAND }
    val onAutosaveMods  = detectedMods.filter { it.triggerMode == TriggerMode.ON_AUTOSAVE }

    // ─────────────────────────────────────────────────────────────────────────
    //  Banner
    // ─────────────────────────────────────────────────────────────────────────
    println()
    println("${MAGENTA}${BOLD}  ╔══════════════════════════════════════╗${RESET}")
    println("${MAGENTA}${BOLD}  ║       Android-Modder  🎮             ║${RESET}")
    println("${MAGENTA}${BOLD}  ║  Overlay-Wrapper · No Root Required  ║${RESET}")
    println("${MAGENTA}${BOLD}  ╚══════════════════════════════════════╝${RESET}")

    // ─────────────────────────────────────────────────────────────────────────
    //  Workspace & config summary
    // ─────────────────────────────────────────────────────────────────────────
    header("${i18n.get("app.workspace")} & Configuration")
    row(i18n.get("app.workspace"),            workspace)
    row(i18n.get("app.cheats.loaded"),         cheats.size)
    row(i18n.get("app.extensions.detected"),   workspaceService.listExtensions(workspace).size)
    row(i18n.get("app.mods.detected"),         detectedMods.size)

    // ─────────────────────────────────────────────────────────────────────────
    //  Mod breakdown by trigger mode
    // ─────────────────────────────────────────────────────────────────────────
    if (detectedMods.isNotEmpty()) {
        header("Active Mods")

        if (onLaunchMods.isNotEmpty()) {
            println("  ${GREEN}${BOLD}${i18n.get("overlay.trigger.on_launch")}${RESET}")
            onLaunchMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}  – ${mod.description.ifBlank { "no description" }}")
            }
        }

        if (onDemandMods.isNotEmpty()) {
            println()
            println("  ${BLUE}${BOLD}${i18n.get("overlay.trigger.on_demand")}${RESET}")
            onDemandMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}", accent = BLUE)
                mod.overlayActions.forEach { action ->
                    println("      ${DIM}▸  ${action.label}  →  fields: ${action.patchFields.joinToString()}${RESET}")
                }
            }
        }

        if (onAutosaveMods.isNotEmpty()) {
            println()
            println("  ${YELLOW}${BOLD}${i18n.get("overlay.trigger.on_autosave")}${RESET}")
            onAutosaveMods.forEach { mod ->
                bullet("${mod.name}  ${DIM}(${mod.gameId})${RESET}", accent = YELLOW)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  How it works
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
    //  App catalog
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
