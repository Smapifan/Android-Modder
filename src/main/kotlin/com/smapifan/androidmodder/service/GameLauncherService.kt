package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.ApkInjectionConfig
import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.DataAccessStrategy
import com.smapifan.androidmodder.model.GameLaunchConfig
import com.smapifan.androidmodder.model.ModDefinition
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
 * │     • Export save data → workspace  (strategy-dependent, see below)     │
 * │     • All matching cheats from [cheats] applied to workspace            │
 * │     • All matching *.mod files with triggerMode=ON_LAUNCH applied       │
 * │       (mods with ON_DEMAND / ON_AUTOSAVE deferred to overlay session)   │
 * │     • Per-mod SaveDataAction.IMPORT: workspace → external storage       │
 * │     • Optional extra preHooks                                            │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2. GAME LAUNCH                                                          │
 * │     • `am start -n <package>/<activity>` via shell                      │
 * │     • Game runs in its own normal sandbox – completely unmodified       │
 * │     • Game reads the (now-patched) save files                           │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  2b. OVERLAY SESSION  (only when overlayService is provided)            │
 * │     • ModOverlayService session started                                  │
 * │     • ON_AUTOSAVE mods polled at regular intervals                      │
 * │     • ON_DEMAND buttons available via the floating HUD                  │
 * │     • For PROCESS_MEMORY strategy: live memory patching applied here    │
 * │     • Blocks until game process exits                                    │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  3. POST-EXIT  (importAfterExit = true)                                 │
 * │     • Optional postHooks                                                 │
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
 * The game is **always** started with `am start` – it runs in its own
 * normal sandbox and is **never** patched at the binary level.
 *
 * ## APK-injection code path (optional)
 *
 * When [activationService] is provided, the launcher also writes a
 * per-launch activation token + `mod_launcher.sh` to external storage
 * immediately before `am start`.  The injected smali bootstrap inside
 * the patched APK executes the script at `Application.onCreate()`,
 * applying patches directly to `/data/data/<pkg>/` files (it has access
 * because it runs as the game's own UID – no root required).
 *
 * The [restoreOriginalApk] method uses [apkInjection] to: back up saves
 * via `run-as`, write a restore script, uninstall the patched APK, and
 * reinstall the original Play-Store APK – **all without any save-data loss**.
 *
 * @param cheats            all known cheat definitions (filtered to matching package)
 * @param workspaceService  manages workspace directories and file copies
 * @param cheatApplier      applies individual cheat operations to save files
 * @param modLoader         discovers and applies `*.mod` files
 * @param shell             executes shell commands
 * @param overlayService    optional overlay coordinator; enables process monitoring
 *                          and deferred ON_DEMAND/ON_AUTOSAVE mods
 * @param processMemory     optional live-memory injection service; used when
 *                          [DataAccessStrategy.PROCESS_MEMORY] is selected
 * @param activationService optional: writes per-launch token + `mod_launcher.sh`
 *                          for the APK-injection code path
 * @param apkInjection      optional: manages APK patch pipeline and original-restore
 */
class GameLauncherService(
    private val cheats: List<CheatDefinition> = emptyList(),
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val cheatApplier: CheatApplier = CheatApplier(),
    private val modLoader: ModLoader = ModLoader(),
    private val shell: ShellExecutor = ShellExecutor(),
    private val overlayService: ModOverlayService? = null,
    private val processMemory: ProcessMemoryService? = null,
    private val activationService: LaunchActivationService? = null,
    private val apkInjection: ApkInjectionService? = null
) {

    /**
     * Runs the full launch cycle for [config].
     *
     * See the class-level diagram for the precise step order.
     *
     * When [activationService] is configured an activation token +
     * `mod_launcher.sh` are written to external storage before `am start`.
     * The injected smali bootstrap inside the patched APK executes the script
     * at `Application.onCreate()`, applying ON_LAUNCH mod patches directly to
     * `/data/data/<pkg>/` – no root needed.  After game exit the token is
     * cleared (safety cleanup).
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

        // ── 1. PRE-LAUNCH: export data to workspace ───────────────────────────
        exportData(strategy, config, workspace, sdcard)

        // ── 1b. AUTO-APPLY CHEATS ────────────────────────────────────────────
        cheats
            .filter { it.appName == config.packageName }
            .forEach { cheat -> runCatching { cheatApplier.apply(appDir, cheat) } }

        // ── 1c. AUTO-APPLY ON_LAUNCH MODS ────────────────────────────────────
        // ON_DEMAND and ON_AUTOSAVE mods are handled by the overlay session (step 2b).
        val appSpecificMods = workspaceService.listModsForApp(workspace, config.packageName)
        val legacyRootMods = workspaceService.listMods(workspace)
        val allActiveMods = (appSpecificMods + legacyRootMods)
            .distinctBy { it.toAbsolutePath().normalize().toString() }
            .mapNotNull { modPath ->
            runCatching { modLoader.load(modPath) }.getOrNull()
                ?.takeIf { it.gameId == config.packageName }
        }

        allActiveMods
            .filter { it.triggerMode == TriggerMode.ON_LAUNCH }
            .forEach { mod ->
                runCatching { modLoader.applyMod(mod, appDir) }
                    .onSuccess {
                        // Honour per-mod IMPORT directive: push data before launch
                        if (mod.saveDataAction == SaveDataAction.IMPORT) {
                            runCatching {
                                workspaceService.importExternalData(workspace, sdcard, config.packageName)
                            }
                        }
                    }
            }

        // ── 1d. OPTIONAL EXTRA PRE-HOOKS ────────────────────────────────────
        preHooks.forEach { it() }

        // ── 1e. WRITE APK-INJECTION ACTIVATION TOKEN ─────────────────────────
        // When an activationService is provided the injected smali bootstrap
        // inside the patched APK will execute mod_launcher.sh at startup,
        // applying ON_LAUNCH patches directly to /data/data/<pkg>/ (accessible
        // because the script runs as the game's own UID – no root needed).
        if (activationService != null) {
            val onLaunchMods = allActiveMods.filter { it.triggerMode == TriggerMode.ON_LAUNCH }
            if (onLaunchMods.isNotEmpty()) {
                val instructions = activationService.instructionsFromMods(
                    config.packageName, onLaunchMods, config.deviceDataRoot
                )
                activationService.writeToken(config.packageName, instructions, config.deviceDataRoot)
            }
        }

        // ── 2. LAUNCH GAME ───────────────────────────────────────────────────
        val launchResult = shell.execute(config.launchCommand)

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

        // ── 2c. SAFETY: clear any unconsumed activation token ─────────────────
        // The injected script deletes the token itself; this is a fallback in
        // case the game crashed before Application.onCreate() completed.
        activationService?.clearToken(config.packageName)

        // ── 3. POST-EXIT: import data back to device ─────────────────────────
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
    //  APK-injection: restore original Play-Store APK (zero save loss)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    //  Clean mods: remove all .mod files so the next launch is unmodified
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Removes all `*.mod` files from [workspace] and returns the number deleted.
     *
     * ## Why this is enough to "clean" the game
     *
     * This launcher never patches the APK binary when using the save-file strategy.
     * Mods only modify save files inside the workspace before each launch.
     * Deleting the `.mod` files means:
     * - No patches are applied on the next launch.
     * - The installed APK retains its original Play-Store signature.
     * - Play-Store updates, Family-Link checks and integrity checks all work normally.
     *
     * If [packageName] is provided **and** an [activationService] is configured,
     * any leftover per-launch token is also cleared as a safety measure (prevents
     * a stale token from triggering the injected bootstrap unexpectedly).
     *
     * @param workspace   workspace root that contains the `.mod` files
     * @param packageName optional: game package whose activation token should be cleared
     * @return number of `.mod` files deleted (0 if none were present)
     */
    fun cleanMods(workspace: Path, packageName: String? = null): Int {
        // Remove all .mod files from the workspace root
        val removed = workspaceService.removeAllMods(workspace)

        // Clear the activation token so no stale injection fires on next launch
        if (packageName != null) {
            activationService?.clearToken(packageName)
        }

        return removed
    }

    /**
     * Restores the original Play-Store APK for [config] **without losing any
     * save data** from the current (modded) session.
     *
     * Delegates to [ApkInjectionService.restoreOriginalApk], which:
     * 1. Backs up `/data/data/<pkg>/` to external storage via `run-as` (works
     *    because the patched APK has `android:debuggable="true"`).
     * 2. Writes a restore script so the next patched-APK launch auto-restores
     *    saves at `Application.onCreate()` before the game reads them.
     * 3. Uninstalls the patched APK (`pm uninstall` – data wiped by Android,
     *    but saves are already safe in the external backup).
     * 4. Installs the original, unmodified APK (`pm install`).
     *
     * After this call the game is Play-Store-compatible.  The save data from
     * the modded session is preserved in external storage and restored
     * automatically on the next launcher-assisted launch.
     *
     * @param config injection config (must include path to the original APK)
     * @return `true` if every step succeeded; `false` if [apkInjection] is
     *         not configured or a step failed
     */
    fun restoreOriginalApk(config: ApkInjectionConfig): Boolean =
        apkInjection?.restoreOriginalApk(config) ?: false
}
