package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.GameLaunchConfig
import com.smapifan.androidmodder.model.SaveDataAction
import com.smapifan.androidmodder.model.TriggerMode
import java.nio.file.Path

/**
 * Orchestrates the full cheat/mod launch cycle for a game.
 *
 * ## Launch cycle
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  1. PRE-LAUNCH                                                           │
 * │     • exportInternalWithRoot()  (ROOT – /data/data/<pkg>/)              │
 * │     • exportExternalData()      (no root – /sdcard/Android/data/<pkg>/) │
 * │     • All matching cheats from [cheats] applied to workspace            │
 * │     • All matching *.mod files with triggerMode=ON_LAUNCH applied       │
 * │       (mods with ON_DEMAND / ON_AUTOSAVE are deferred to overlay)       │
 * │     • Per-mod SaveDataAction.IMPORT: workspace → external storage       │
 * │     • Optional extra preHooks                                            │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2. GAME LAUNCH                                                          │
 * │     • `am start -n <package>/<activity>` via shell                      │
 * │     • Game runs in its **normal sandbox**, completely unmodified        │
 * │     • Game reads the (now-patched) save files                           │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2b. OVERLAY SESSION  (only when overlayService is provided)            │
 * │     • ModOverlayService session started                                  │
 * │     • ON_AUTOSAVE mods polled at regular intervals                      │
 * │     • ON_DEMAND buttons available to the user via overlay HUD           │
 * │     • Blocks until game process exits                                    │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  3. POST-EXIT  (importAfterExit = true)                                 │
 * │     • Optional postHooks                                                 │
 * │     • importInternalWithRoot()  (ROOT required)                         │
 * │     • importExternalData()      (no root)                               │
 * └──────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Cheats and ON_LAUNCH mods are applied **automatically** – no wiring needed.
 * The real game binary is **never patched**.
 *
 * @param cheats           all known cheat definitions (only those whose
 *                         [CheatDefinition.appName] matches are applied)
 * @param workspaceService manages workspace directories and file copies
 * @param cheatApplier     applies individual cheat operations to save files
 * @param modLoader        discovers and applies `*.mod` files
 * @param shell            executes shell commands (and root commands via `su`)
 * @param overlayService   optional overlay coordinator; when supplied the launcher
 *                         waits for the game to exit via process monitoring
 *                         before entering the post-exit phase
 */
class GameLauncherService(
    private val cheats: List<CheatDefinition> = emptyList(),
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val cheatApplier: CheatApplier = CheatApplier(),
    private val modLoader: ModLoader = ModLoader(),
    private val shell: ShellExecutor = ShellExecutor(),
    private val overlayService: ModOverlayService? = null
) {

    /**
     * Runs the full launch cycle for [config].
     *
     * ### Mod trigger modes
     * - **ON_LAUNCH** mods are applied during pre-launch (step 1), same as cheats.
     * - **ON_AUTOSAVE** and **ON_DEMAND** mods are handed off to [overlayService]
     *   and are applied while the game is running (step 2b).  If no
     *   [overlayService] is configured these mods are silently skipped.
     *
     * ### saveDataAction wiring
     * After a mod's ON_LAUNCH patches are applied, if the mod declares
     * [SaveDataAction.IMPORT] the modified workspace data is immediately pushed
     * to external storage so the game finds the new values the moment it starts.
     *
     * @param workspace  workspace root directory
     * @param config     launch parameters (package name, shell command, root flag, …)
     * @param preHooks   optional callbacks run after auto-cheats/mods but before launch
     * @param postHooks  optional callbacks run after game exit, before import
     * @return the [ShellResult] of the `am start` command
     */
    fun launch(
        workspace: Path,
        config: GameLaunchConfig,
        preHooks: List<() -> Unit> = emptyList(),
        postHooks: List<() -> Unit> = emptyList()
    ): ShellResult {
        val sdcard = java.nio.file.Path.of(config.externalStorageRoot)
        val appDir = workspaceService.appWorkspace(workspace, config.packageName)

        // ── 1. PRE-LAUNCH: export data to workspace ───────────────────────────
        if (config.useRootForData) {
            exportInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
        }
        workspaceService.exportExternalData(workspace, sdcard, config.packageName)

        // ── 1b. AUTO-APPLY CHEATS ────────────────────────────────────────────
        cheats
            .filter { it.appName == config.packageName }
            .forEach { cheat -> runCatching { cheatApplier.apply(appDir, cheat) } }

        // ── 1c. AUTO-APPLY ON_LAUNCH MODS from workspace ─────────────────────
        // Mods with other trigger modes are handled by the overlay session (step 2b).
        val allActiveMods = workspaceService.listMods(workspace).mapNotNull { modPath ->
            runCatching { modLoader.load(modPath) }.getOrNull()
                ?.takeIf { it.gameId == config.packageName }
        }

        allActiveMods
            .filter { it.triggerMode == TriggerMode.ON_LAUNCH }
            .forEach { mod ->
                runCatching { modLoader.applyMod(mod, appDir) }
                    .onSuccess {
                        // Honour per-mod IMPORT directive: push to device before launch
                        if (mod.saveDataAction == SaveDataAction.IMPORT) {
                            runCatching {
                                workspaceService.importExternalData(workspace, sdcard, config.packageName)
                            }
                        }
                    }
            }

        // ── 1d. OPTIONAL EXTRA PRE-HOOKS ────────────────────────────────────
        preHooks.forEach { it() }

        // ── 2. LAUNCH GAME ───────────────────────────────────────────────────
        val launchResult = shell.execute(config.launchCommand)

        // ── 2b. OVERLAY SESSION (optional) ───────────────────────────────────
        // Start the overlay session for ON_DEMAND / ON_AUTOSAVE mods and block
        // until the game process exits.  Skipped entirely when overlayService
        // is null (preserves the original synchronous behaviour for tests and
        // callers that do not need process monitoring).
        if (overlayService != null) {
            val overlayMods = allActiveMods.filter {
                it.triggerMode == TriggerMode.ON_DEMAND ||
                it.triggerMode == TriggerMode.ON_AUTOSAVE
            }
            val session = overlayService.startSession(
                mods               = overlayMods,
                appWorkspaceDir    = appDir,
                packageName        = config.packageName,
                workspace          = workspace,
                externalStorageRoot = sdcard
            )
            try {
                // Block until the game process terminates
                session.waitForGameExit()
            } finally {
                session.stop()
            }
        }

        // ── 3. POST-EXIT: import data back to device ─────────────────────────
        if (config.importAfterExit) {
            postHooks.forEach { it() }

            if (config.useRootForData) {
                importInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
            }
            workspaceService.importExternalData(workspace, sdcard, config.packageName)
        }

        return launchResult
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Root shell helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies `/data/data/<pkg>/` and `/data/<pkg>/` into the workspace using
     * a root shell command (`su -c "cp -r ..."`).
     *
     * The workspace mirror structure is:
     * - `/data/data/<pkg>/`  → `<workspace>/<pkg>/internal/data/data/<pkg>/`
     * - `/data/<pkg>/`       → `<workspace>/<pkg>/internal/data/<pkg>/`
     */
    internal fun exportInternalWithRoot(packageName: String, deviceDataRoot: String, workspace: Path) {
        val appDest = workspaceService.appWorkspace(workspace, packageName)

        val primaryDest = appDest.resolve("internal").resolve("data").resolve("data").resolve(packageName)
        primaryDest.toFile().mkdirs()
        shell.execute("cp -r $deviceDataRoot/data/$packageName/. $primaryDest/", asRoot = true)

        val secondaryDest = appDest.resolve("internal").resolve("data").resolve(packageName)
        secondaryDest.toFile().mkdirs()
        shell.execute("cp -r $deviceDataRoot/$packageName/. $secondaryDest/", asRoot = true)
    }

    /**
     * Copies workspace internal data back to `/data/data/<pkg>/` and `/data/<pkg>/`
     * using a root shell command.  Reverses [exportInternalWithRoot].
     */
    internal fun importInternalWithRoot(packageName: String, deviceDataRoot: String, workspace: Path) {
        val appSrc = workspaceService.appWorkspace(workspace, packageName)

        val primarySrc = appSrc.resolve("internal").resolve("data").resolve("data").resolve(packageName)
        if (primarySrc.toFile().isDirectory) {
            shell.execute("cp -r $primarySrc/. $deviceDataRoot/data/$packageName/", asRoot = true)
        }

        val secondarySrc = appSrc.resolve("internal").resolve("data").resolve(packageName)
        if (secondarySrc.toFile().isDirectory) {
            shell.execute("cp -r $secondarySrc/. $deviceDataRoot/$packageName/", asRoot = true)
        }
    }
}
