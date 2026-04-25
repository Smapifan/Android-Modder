package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.DataAccessStrategy
import com.smapifan.androidmodder.model.GameLaunchConfig
import com.smapifan.androidmodder.model.ModDefinition
import com.smapifan.androidmodder.model.SaveDataAction
import com.smapifan.androidmodder.model.TriggerMode
import java.nio.file.Path

/**
 * Orchestrates the full cheat/mod wrapper launch cycle for any Android app.
 *
 * ## Design principle: the APK is NEVER modified
 *
 * Android-Modder is a pure **wrapper** around the original game.  The
 * installed APK keeps its original Play-Store signature and is launched
 * unmodified via `am start`.  Cheats and mods are applied exclusively to
 * copies of the game's save files inside the local workspace – no binary
 * patching, no smali injection, no `pm uninstall + pm install` cycle.
 *
 * ## Launch cycle
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  1. PRE-LAUNCH                                                           │
 * │     • Export save data → workspace  (strategy-dependent, see below)     │
 * │     • All matching cheats from [cheats] applied to workspace copy       │
 * │     • All ON_LAUNCH *.mod files for the package applied to workspace    │
 * │       (ON_DEMAND / ON_AUTOSAVE mods deferred to the overlay session)    │
 * │     • Per-mod SaveDataAction.IMPORT: push workspace data back to device │
 * │     • Optional caller-supplied preHooks                                  │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2. GAME LAUNCH                                                          │
 * │     • `am start -n <package>/<activity>` via shell                      │
 * │     • Game runs in its own normal sandbox – completely unmodified       │
 * │     • Game reads the (now-patched) save files from workspace            │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2b. OVERLAY SESSION  (only when overlayService is provided)            │
 * │     • ModOverlayService session started                                  │
 * │     • ON_AUTOSAVE mods polled at [autosaveIntervalMs]                   │
 * │     • ON_DEMAND buttons available via the floating HUD                  │
 * │     • For PROCESS_MEMORY strategy: live memory patching applied here    │
 * │     • Blocks until the game process exits                               │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  3. POST-EXIT  (importAfterExit = true)                                 │
 * │     • Optional caller-supplied postHooks                                │
 * │     • Import workspace → device  (strategy-dependent)                  │
 * └──────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Data-access strategies
 *
 * | Strategy          | Export/import how?              | Root? |
 * |-------------------|---------------------------------|-------|
 * | EXTERNAL_STORAGE  | WorkspaceService file copy      | No    |
 * | RUN_AS            | `run-as <pkg>` shell commands   | No*   |
 * | ROOT              | `su -c "cp …"` shell commands   | Yes   |
 * | PROCESS_MEMORY    | /proc/<pid>/mem (live injection)| Yes** |
 *
 * *  RUN_AS requires the app to be `android:debuggable="true"`.
 * ** PROCESS_MEMORY requires root or ptrace capability.
 *
 * Every package name is supported – there is no curated allow-list.  The
 * launcher works with any app the user points it at.
 *
 * @param cheats           all known cheat definitions (filtered to matching package at launch)
 * @param workspaceService manages workspace directories and file copies
 * @param cheatApplier     applies individual cheat operations to save files
 * @param modLoader        discovers and applies `*.mod` files
 * @param shell            executes shell commands on the device
 * @param overlayService   optional overlay coordinator; enables process monitoring
 *                         and deferred ON_DEMAND/ON_AUTOSAVE mods
 * @param processMemory    optional live-memory injection service; used when
 *                         [DataAccessStrategy.PROCESS_MEMORY] is selected
 */
class GameLauncherService(
    private val cheats: List<CheatDefinition> = emptyList(),
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val cheatApplier: CheatApplier = CheatApplier(),
    private val modLoader: ModLoader = ModLoader(),
    private val shell: ShellExecutor = ShellExecutor(),
    private val overlayService: ModOverlayService? = null,
    private val processMemory: ProcessMemoryService? = null,
    private val codePatchLoader: CodePatchLoader = CodePatchLoader()
) {

    /**
     * Runs the full wrapper launch cycle for [config].
     *
     * See the class-level diagram for the precise step order.
     *
     * The APK is **never** modified.  Cheats and mods are applied to the
     * workspace copy of the save files only; the game reads the patched
     * saves when it starts.
     *
     * @param workspace  workspace root directory
     * @param config     launch parameters (package name, shell command, strategy, …)
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
        val sdcard   = java.nio.file.Path.of(config.externalStorageRoot)
        val appDir   = workspaceService.appWorkspace(workspace, config.packageName)
        val strategy = config.effectiveStrategy

        // ── 1. PRE-LAUNCH: export save data to workspace ──────────────────────
        exportData(strategy, config, workspace, sdcard)

        // ── 1b. AUTO-APPLY CHEATS ────────────────────────────────────────────
        // Apply all cheat definitions that match the launched package.
        cheats
            .filter { it.appName == config.packageName }
            .forEach { cheat -> runCatching { cheatApplier.apply(appDir, cheat) } }

        // ── 1c. AUTO-APPLY CODE PATCHES (.codepatch drop-ins) ────────────────
        // Do this before mod-triggered IMPORT sync so patched workspace data
        // is what gets pushed to the device.
        runCatching { codePatchLoader.applyForGame(workspace, config.packageName, appDir) }

        // ── 1d. AUTO-APPLY ON_LAUNCH MODS ────────────────────────────────────
        // Collect mod files from both the workspace root and the app-specific
        // subdirectory, de-duplicate, and apply only ON_LAUNCH mods now.
        // ON_DEMAND and ON_AUTOSAVE mods are handled by the overlay session (step 2b).
        val appSpecificMods = workspaceService.listModsForApp(workspace, config.packageName)
        val legacyRootMods  = workspaceService.listMods(workspace)
        val seenNormalizedPaths = linkedSetOf<String>()
        val allActiveMods = (appSpecificMods + legacyRootMods)
            .filter { modPath ->
                seenNormalizedPaths.add(modPath.toAbsolutePath().normalize().toString())
            }
            .mapNotNull { modPath ->
                runCatching { modLoader.load(modPath) }.getOrNull()
                    ?.takeIf { it.gameId == config.packageName }
            }

        allActiveMods
            .filter { it.triggerMode == TriggerMode.ON_LAUNCH }
            .forEach { mod ->
                runCatching { modLoader.applyMod(mod, appDir) }
                    .onSuccess {
                        // Honour per-mod IMPORT directive: push data to device before launch
                        if (mod.saveDataAction == SaveDataAction.IMPORT) {
                            runCatching {
                                workspaceService.importExternalData(workspace, sdcard, config.packageName)
                            }
                        }
                    }
            }

        // ── 1d. AUTO-APPLY CODE PATCHES (.codepatch drop-ins) ────────────────
        runCatching { codePatchLoader.applyForGame(workspace, config.packageName, appDir) }

        // ── 1e. OPTIONAL EXTRA PRE-HOOKS ────────────────────────────────────
        preHooks.forEach { it() }

        // ── 2. LAUNCH GAME ───────────────────────────────────────────────────
        // The game starts in its own normal Android sandbox, completely unmodified.
        // When a containerId is set, the launch command is amended with --user <id>
        // so the game runs inside the isolated Android user (container).
        val effectiveLaunchCommand = buildLaunchCommand(config)
        val launchResult = shell.execute(effectiveLaunchCommand)

        // ── 2b. OVERLAY SESSION + PROCESS-MEMORY INJECTION ───────────────────
        if (overlayService != null || strategy == DataAccessStrategy.PROCESS_MEMORY) {
            runOverlaySession(
                config        = config,
                allActiveMods = allActiveMods,
                appDir        = appDir,
                workspace     = workspace,
                sdcard        = sdcard
            )
        }

        // ── 3. POST-EXIT: import patched data back to device ─────────────────
        if (config.importAfterExit) {
            postHooks.forEach { it() }
            importData(strategy, config, workspace, sdcard)
        }

        return launchResult
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Strategy dispatch: export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports save data from the device to the workspace using the appropriate strategy.
     *
     * - [DataAccessStrategy.EXTERNAL_STORAGE] – copies `/sdcard/Android/data/<pkg>/`
     * - [DataAccessStrategy.RUN_AS]           – uses `run-as <pkg>` to copy `/data/data/<pkg>/`
     * - [DataAccessStrategy.ROOT]             – uses `su` to copy `/data/data/<pkg>/`
     * - [DataAccessStrategy.PROCESS_MEMORY]   – only exports external storage (memory is live)
     */
    private fun exportData(
        strategy: DataAccessStrategy,
        config: GameLaunchConfig,
        workspace: Path,
        sdcard: Path
    ) {
        when (strategy) {
            DataAccessStrategy.EXTERNAL_STORAGE,
            DataAccessStrategy.PROCESS_MEMORY -> {
                // External storage only: no root, accessible to all apps
                workspaceService.exportExternalData(workspace, sdcard, config.packageName)
            }
            DataAccessStrategy.RUN_AS -> {
                // Use run-as to access internal app data without full root
                exportWithRunAs(config.packageName, config.deviceDataRoot, workspace)
                workspaceService.exportExternalData(workspace, sdcard, config.packageName)
            }
            DataAccessStrategy.ROOT -> {
                // Use root for both internal and external
                exportInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
                workspaceService.exportExternalData(workspace, sdcard, config.packageName)
            }
        }
    }

    /**
     * Imports workspace data back to the device using the appropriate strategy.
     *
     * - [DataAccessStrategy.EXTERNAL_STORAGE] – copies workspace back to `/sdcard/…`
     * - [DataAccessStrategy.RUN_AS]           – uses `run-as <pkg>` to write `/data/data/<pkg>/`
     * - [DataAccessStrategy.ROOT]             – uses `su` to write `/data/data/<pkg>/`
     * - [DataAccessStrategy.PROCESS_MEMORY]   – only imports external storage
     */
    private fun importData(
        strategy: DataAccessStrategy,
        config: GameLaunchConfig,
        workspace: Path,
        sdcard: Path
    ) {
        when (strategy) {
            DataAccessStrategy.EXTERNAL_STORAGE,
            DataAccessStrategy.PROCESS_MEMORY -> {
                workspaceService.importExternalData(workspace, sdcard, config.packageName)
            }
            DataAccessStrategy.RUN_AS -> {
                importWithRunAs(config.packageName, config.deviceDataRoot, workspace)
                workspaceService.importExternalData(workspace, sdcard, config.packageName)
            }
            DataAccessStrategy.ROOT -> {
                importInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
                workspaceService.importExternalData(workspace, sdcard, config.packageName)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Overlay session + live memory injection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts an [ModOverlayService.OverlaySession] for ON_DEMAND/ON_AUTOSAVE mods
     * and, if [DataAccessStrategy.PROCESS_MEMORY] is active, applies live memory
     * patches once the game process is found.
     *
     * Blocks until the game process exits (via [ModOverlayService.OverlaySession.waitForGameExit]).
     */
    private fun runOverlaySession(
        config: GameLaunchConfig,
        allActiveMods: List<ModDefinition>,
        appDir: Path,
        workspace: Path,
        sdcard: Path
    ) {
        // ── Live memory injection (PROCESS_MEMORY strategy) ──────────────────
        if (config.effectiveStrategy == DataAccessStrategy.PROCESS_MEMORY && processMemory != null) {
            applyProcessMemoryPatches(allActiveMods, config.packageName)
        }

        // ── Overlay session (ON_DEMAND / ON_AUTOSAVE mods) ───────────────────
        if (overlayService != null) {
            val overlayMods = allActiveMods.filter {
                it.triggerMode == TriggerMode.ON_DEMAND ||
                it.triggerMode == TriggerMode.ON_AUTOSAVE
            }
            val session = overlayService.startSession(
                mods                = overlayMods,
                appWorkspaceDir     = appDir,
                packageName         = config.packageName,
                workspace           = workspace,
                externalStorageRoot = sdcard
            )
            try {
                session.waitForGameExit()
            } finally {
                session.stop()
            }
        }
    }

    /**
     * Applies ON_LAUNCH mod patches directly to the live game process via
     * [ProcessMemoryService.searchAndPatch].
     *
     * Only patches where the search yields exactly one address are applied,
     * preventing accidental corruption of unrelated memory regions.
     */
    private fun applyProcessMemoryPatches(mods: List<ModDefinition>, packageName: String) {
        val pid = processMemory?.findPid(packageName) ?: return
        mods
            .filter { it.triggerMode == TriggerMode.ON_LAUNCH }
            .forEach { mod ->
                mod.patches.forEach { patch ->
                    runCatching {
                        processMemory?.searchAndPatch(
                            pid          = pid,
                            currentValue = patch.amount.toInt(),
                            newValue     = patch.amount.toInt()
                        )
                    }
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RunAs helpers (internal data, no full root, debuggable apps)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports internal app data to the workspace via `run-as <pkg>`.
     *
     * Copies `/data/data/<pkg>/` → `<workspace>/<pkg>/internal/data/data/<pkg>/`.
     * No root required; the target app must be debuggable.
     */
    internal fun exportWithRunAs(packageName: String, deviceDataRoot: String, workspace: Path) {
        val runAs = RunAsExecutor(packageName, shell)
        val dest  = workspaceService.appWorkspace(workspace, packageName)
            .resolve("internal").resolve("data").resolve("data").resolve(packageName)
        dest.toFile().mkdirs()
        runAs.exportDataDir("$deviceDataRoot/data/$packageName", dest.toString())
    }

    /**
     * Imports workspace internal data back via `run-as <pkg>`.
     *
     * Reverses [exportWithRunAs].
     */
    internal fun importWithRunAs(packageName: String, deviceDataRoot: String, workspace: Path) {
        val runAs = RunAsExecutor(packageName, shell)
        val src   = workspaceService.appWorkspace(workspace, packageName)
            .resolve("internal").resolve("data").resolve("data").resolve(packageName)
        if (src.toFile().isDirectory) {
            runAs.importDataDir(src.toString(), "$deviceDataRoot/data/$packageName")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Root shell helpers (internal data, full root required)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies `/data/data/<pkg>/` and `/data/<pkg>/` into the workspace using
     * a root shell command (`su -c "cp -r ..."`).
     *
     * Workspace mirror:
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Clean mods: remove all .mod files so the next launch is unmodified
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Removes all `*.mod` files from [workspace] and returns the number deleted.
     *
     * Because mods only modify save files in the workspace (the original APK is
     * never touched), removing the `.mod` files is sufficient to restore a clean
     * game state: the next launch applies no patches, the installed APK retains
     * its original Play-Store signature, and Play-Store updates work normally.
     *
     * @param workspace workspace root that contains the `.mod` files
     * @return number of `.mod` files deleted (0 if none were present)
     */
    fun cleanMods(workspace: Path): Int =
        workspaceService.removeAllMods(workspace)

    // ─────────────────────────────────────────────────────────────────────────
    //  Container launch command builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the effective shell command used to start the game.
     *
     * When [GameLaunchConfig.containerId] is `null` the command is the plain
     * [GameLaunchConfig.launchCommand] as before.
     *
     * When [GameLaunchConfig.containerId] is set, `--user <id>` is appended
     * directly after the `am start` keyword so that Android routes the launch
     * to the isolated container user:
     *
     * ```
     * "am start -n com.example/.Main"         → base command (no container)
     * "am start --user 11 -n com.example/.Main" → container user 11
     * ```
     *
     * This is done by a simple string replacement so that callers can continue
     * to supply a plain `am start …` command in [GameLaunchConfig.launchCommand]
     * without having to know about the container at command-construction time.
     */
    internal fun buildLaunchCommand(config: GameLaunchConfig): String {
        val id = config.containerId ?: return config.launchCommand
        return config.launchCommand.replaceFirst("am start", "am start --user $id")
    }
}
