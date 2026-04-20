package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.GameLaunchConfig
import java.nio.file.Path

/**
 * Orchestrates the full cheat/mod launch cycle for a game.
 *
 * ## How it works
 *
 * Android-Modder acts as a **launcher**: instead of opening the game directly,
 * the user launches it through this app. The sequence is:
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  1. PRE-LAUNCH                                           │
 * │     • exportAppData()      (ROOT – /data/data/<pkg>/)    │
 * │     • exportExternalData() (no root – /sdcard/…)         │
 * │     • ALL matching cheats from [cheats] applied          │
 * │     • ALL matching *.mod files from workspace applied    │
 * │     • optional extra preHooks run                        │
 * ├──────────────────────────────────────────────────────────┤
 * │  2. GAME LAUNCH                                          │
 * │     • `am start -n <package>/<activity>` via shell       │
 * │     • game runs in its normal sandbox, unmodified        │
 * │     • game reads the (now-modified) save files           │
 * ├──────────────────────────────────────────────────────────┤
 * │  3. POST-EXIT  (importAfterExit = true)                  │
 * │     • optional postHooks run                             │
 * │     • importAppData()       (ROOT required)              │
 * │     • importExternalData()  (no root)                    │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * Cheats and mods are applied **automatically** – no manual wiring required.
 * The game binary is **never patched**.
 *
 * @param cheats         all known cheat definitions (e.g. loaded from Cheats.json);
 *                       only cheats whose [CheatDefinition.appName] matches the
 *                       launched game's package name are applied
 * @param workspaceService manages workspace directories and file copies
 * @param cheatApplier   applies individual cheat operations to save files
 * @param modLoader      discovers and applies *.mod files
 * @param shell          executes shell commands (and root commands via `su`)
 */
class GameLauncherService(
    private val cheats: List<CheatDefinition> = emptyList(),
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val cheatApplier: CheatApplier = CheatApplier(),
    private val modLoader: ModLoader = ModLoader(),
    private val shell: ShellExecutor = ShellExecutor()
) {

    /**
     * Runs the full launch cycle for [config].
     *
     * Cheats and mods are applied automatically before the game starts:
     * - All entries in [cheats] whose `appName` equals [GameLaunchConfig.packageName]
     * - All `*.mod` files in [workspace] whose `gameId` equals [GameLaunchConfig.packageName]
     *
     * @param workspace  the workspace root directory
     * @param config     launch configuration (package, command, root flag, …)
     * @param preHooks   optional extra callbacks invoked after auto-cheats/mods but before launch
     * @param postHooks  optional callbacks invoked after the game exits (before import)
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

        // ── 1. PRE-LAUNCH: export ─────────────────────────────────────────
        if (config.useRootForData) {
            exportInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
        }
        workspaceService.exportExternalData(workspace, sdcard, config.packageName)

        // ── 1b. AUTO-APPLY CHEATS ─────────────────────────────────────────
        val matchingCheats = cheats.filter { it.appName == config.packageName }
        matchingCheats.forEach { cheat ->
            runCatching { cheatApplier.apply(appDir, cheat) }
        }

        // ── 1c. AUTO-APPLY MODS from workspace ────────────────────────────
        workspaceService.listMods(workspace).forEach { modPath ->
            runCatching {
                val mod = modLoader.load(modPath)
                if (mod.gameId == config.packageName) {
                    modLoader.applyMod(mod, appDir)
                }
            }
        }

        // ── 1d. OPTIONAL EXTRA PRE-HOOKS ──────────────────────────────────
        preHooks.forEach { it() }

        // ── 2. LAUNCH GAME ────────────────────────────────────────────────
        val launchResult = shell.execute(config.launchCommand)

        // ── 3. POST-EXIT: import ──────────────────────────────────────────
        if (config.importAfterExit) {
            postHooks.forEach { it() }

            if (config.useRootForData) {
                importInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
            }
            workspaceService.importExternalData(workspace, sdcard, config.packageName)
        }

        return launchResult
    }

    // --- root shell helpers -----------------------------------------------

    /**
     * Copies `/data/data/<pkg>/` and `/data/<pkg>/` into the workspace using
     * a root shell command (`su -c "cp -r ..."`).
     */
    internal fun exportInternalWithRoot(packageName: String, deviceDataRoot: String, workspace: Path) {
        val appDest = workspaceService.appWorkspace(workspace, packageName)

        // /data/data/<pkg>/ → <workspace>/<pkg>/internal/data/data/<pkg>/
        val primaryDest = appDest.resolve("internal").resolve("data").resolve("data").resolve(packageName)
        primaryDest.toFile().mkdirs()
        shell.execute("cp -r $deviceDataRoot/data/$packageName/. $primaryDest/", asRoot = true)

        // /data/<pkg>/ → <workspace>/<pkg>/internal/data/<pkg>/
        val secondaryDest = appDest.resolve("internal").resolve("data").resolve(packageName)
        secondaryDest.toFile().mkdirs()
        shell.execute("cp -r $deviceDataRoot/$packageName/. $secondaryDest/", asRoot = true)
    }

    /**
     * Copies workspace internal data back to `/data/data/<pkg>/` and `/data/<pkg>/`
     * using a root shell command.
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
