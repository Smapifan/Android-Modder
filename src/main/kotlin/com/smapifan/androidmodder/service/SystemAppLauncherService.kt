package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.DataAccessStrategy
import com.smapifan.androidmodder.model.GameLaunchConfig
import java.nio.file.Path

// ═════════════════════════════════════════════════════════════════════════════
//  SystemAppLauncherService – thin wrapper for launching VM system apps
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Launches VM system apps with [DataAccessStrategy.VIRTUAL_FS] hard-pinned.
 *
 * System apps (Root Browser, RAM Editor, MicroG, F-Droid, NewPipe, …) are
 * managed entirely inside Android-Modder's own sandbox.  Their data must never
 * be read from or written to real device paths (`/data/data/…`, `/sdcard/…`).
 * This service enforces that by replacing any caller-supplied [DataAccessStrategy]
 * with [DataAccessStrategy.VIRTUAL_FS] before delegating to the underlying
 * [GameLauncherService].
 *
 * ## Minimal-RAM design
 *
 * This class is a **stateless thin wrapper**: it holds only a single
 * [GameLauncherService] reference and does no I/O itself.  The caller may
 * instantiate it on demand and discard it once the launch call returns.
 *
 * ## Typical usage
 *
 * ```kotlin
 * val launcher = SystemAppLauncherService(GameLauncherService(shell = shell))
 *
 * // Launch the virtual root browser — VIRTUAL_FS is enforced automatically.
 * launcher.launchSystemApp(
 *     workspace  = workspacePath,
 *     packageId  = "com.smapifan.rootbrowser",
 *     amCommand  = "am start -n com.smapifan.rootbrowser/.MainActivity"
 * )
 * ```
 *
 * @param delegate  the underlying [GameLauncherService] to delegate to;
 *                  defaults to a minimal instance with no cheats or overlay.
 */
class SystemAppLauncherService(
    private val delegate: GameLauncherService = GameLauncherService()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Launch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launches a VM system app identified by [packageId].
     *
     * The [DataAccessStrategy] is unconditionally set to [DataAccessStrategy.VIRTUAL_FS]
     * regardless of what the caller provides in [strategyOverride].  This ensures
     * all app data stays inside Android-Modder's own sandbox.
     *
     * @param workspace        workspace root directory passed to [GameLauncherService.launch].
     * @param packageId        Android package name of the system app, e.g.
     *                         `"com.smapifan.rootbrowser"`.
     * @param amCommand        shell command to start the app, e.g.
     *                         `"am start -n com.smapifan.rootbrowser/.MainActivity"`.
     * @param importAfterExit  whether to copy workspace data back to the virtual FS
     *                         after the app exits (default: `true`).
     * @param strategyOverride ignored — present only to document that [VIRTUAL_FS]
     *                         is always used; callers should not pass this parameter.
     * @param preHooks         optional callbacks invoked after mod application but
     *                         before the `am start` shell call.
     * @param postHooks        optional callbacks invoked after the app exits, before
     *                         the post-exit data import.
     * @return the [ShellResult] of the `am start` command.
     */
    fun launchSystemApp(
        workspace: Path,
        packageId: String,
        amCommand: String,
        importAfterExit: Boolean = true,
        @Suppress("UNUSED_PARAMETER")
        strategyOverride: DataAccessStrategy? = null,
        preHooks: List<() -> Unit> = emptyList(),
        postHooks: List<() -> Unit> = emptyList()
    ): ShellResult {
        val config = GameLaunchConfig(
            packageName        = packageId,
            launchCommand      = amCommand,
            dataAccessStrategy = DataAccessStrategy.VIRTUAL_FS,
            importAfterExit    = importAfterExit
        )
        return delegate.launch(workspace, config, preHooks, postHooks)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Registry helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the [SystemAppEntry] for [packageId] from the combined
     * [SystemAppsRegistry.all] list, or `null` if not registered.
     */
    fun findEntry(packageId: String): SystemAppEntry? =
        SystemAppsRegistry.all().firstOrNull { it.packageId == packageId }

    /**
     * Convenience: launches all registered [SystemAppsRegistry.bundled] entries
     * in the order they appear in the registry.
     *
     * In practice callers will launch individual apps; this helper is mostly
     * useful for integration tests that verify the full boot → launch cycle.
     *
     * @param workspace       workspace root directory.
     * @param commandBuilder  maps a [SystemAppEntry] to its `am start` command;
     *                        return `null` to skip the entry.
     * @return map of `packageId → ShellResult` for every entry that was launched.
     */
    fun launchAll(
        workspace: Path,
        commandBuilder: (SystemAppEntry) -> String?
    ): Map<String, ShellResult> {
        val results = linkedMapOf<String, ShellResult>()
        for (entry in SystemAppsRegistry.bundled) {
            val cmd = commandBuilder(entry) ?: continue
            results[entry.packageId] = launchSystemApp(
                workspace = workspace,
                packageId = entry.packageId,
                amCommand = cmd
            )
        }
        return results
    }
}
