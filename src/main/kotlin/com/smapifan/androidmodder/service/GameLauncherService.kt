package com.smapifan.androidmodder.service

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
 * │  1. PRE-LAUNCH HOOK                                      │
 * │     • exportAppData()  – copies /data/data/<pkg>/        │
 * │       into workspace   (ROOT required)                   │
 * │     • exportExternalData() – copies /sdcard/Android/     │
 * │       data/<pkg>/ into workspace (no root)               │
 * │     • user-supplied preHooks run (apply cheats / mods)   │
 * ├──────────────────────────────────────────────────────────┤
 * │  2. GAME LAUNCH                                          │
 * │     • `am start -n <package>/<activity>` via shell       │
 * │     • game runs in its normal sandbox, unmodified        │
 * │     • game reads the (now-modified) save files           │
 * ├──────────────────────────────────────────────────────────┤
 * │  3. POST-EXIT HOOK  (importAfterExit = true)             │
 * │     • importAppData()       (ROOT required)              │
 * │     • importExternalData()  (no root)                    │
 * │     • user-supplied postHooks run                        │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * The game binary is **never patched**. All modifications are applied to
 * the save files in the workspace. Root access is used only to copy files
 * in and out of the internal data directory – the game itself runs normally.
 *
 * ## Root access
 *
 * `/data/data/<pkg>/` is protected by Android's app sandbox. Access requires:
 * - The device is rooted (Magisk / SuperSU)
 * - The user has granted root to Android-Modder
 *
 * External storage (`/sdcard/Android/data/<pkg>/`) is accessible without root.
 * Set [GameLaunchConfig.useRootForData] = `false` if only external data is needed.
 */
class GameLauncherService(
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val shell: ShellExecutor = ShellExecutor()
) {

    /**
     * Runs the full launch cycle for [config].
     *
     * @param workspace  the workspace root directory
     * @param config     launch configuration (package, command, root flag, …)
     * @param preHooks   callbacks invoked **after** export but **before** game launch
     *                   (use these to apply cheats / mods to the exported data)
     * @param postHooks  callbacks invoked **after** the game exits (optional cleanup)
     * @return the [ShellResult] of the `am start` command
     */
    fun launch(
        workspace: Path,
        config: GameLaunchConfig,
        preHooks: List<() -> Unit> = emptyList(),
        postHooks: List<() -> Unit> = emptyList()
    ): ShellResult {
        val deviceData = java.nio.file.Path.of(config.deviceDataRoot)
        val sdcard     = java.nio.file.Path.of(config.externalStorageRoot)

        // ── 1. PRE-LAUNCH: export ─────────────────────────────────────────
        if (config.useRootForData) {
            exportInternalWithRoot(config.packageName, config.deviceDataRoot, workspace)
        }
        workspaceService.exportExternalData(workspace, sdcard, config.packageName)

        // Run pre-launch hooks (apply cheats, mods, …)
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
