package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.ModDefinition
import com.smapifan.androidmodder.model.OverlayAction
import com.smapifan.androidmodder.model.SaveDataAction
import com.smapifan.androidmodder.model.TriggerMode
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// ═════════════════════════════════════════════════════════════════════════════
//  ModOverlayService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * JVM-side coordinator for the **floating mod overlay** that appears over the
 * running game.
 *
 * ## What is the overlay?
 *
 * On a real Android device the floating HUD is rendered by a companion
 * `android.app.Service` that uses `WindowManager` with window-type
 * `TYPE_APPLICATION_OVERLAY` and requires the `SYSTEM_ALERT_WINDOW`
 * permission.  *This class is the JVM-side orchestrator* for that Android
 * component; it handles:
 *
 * 1. **Process monitoring** – polling `pidof <packageName>` to detect when the
 *    game exits so that the post-exit import phase can be triggered.
 * 2. **Autosave polling** – periodically re-applying [TriggerMode.ON_AUTOSAVE]
 *    mods so that the game always finds up-to-date values on its next save cycle.
 * 3. **On-demand actions** – executing a single [OverlayAction] (i.e. one
 *    overlay-button tap) for [TriggerMode.ON_DEMAND] mods.
 * 4. **Save-data integration** – honouring a mod's [SaveDataAction] directive
 *    to push workspace data to external storage immediately after patches.
 *
 * ## Root requirement
 *
 * **No root is needed.**  All mod operations target external storage
 * (`/sdcard/Android/data/<pkg>/`) which every app can read and write.
 * Root is only required for the internal-data path (`/data/data/<pkg>/`),
 * which is a separate opt-in via [GameLaunchConfig.useRootForData].
 *
 * ## Threading model
 *
 * Each [OverlaySession] owns a single-thread [ScheduledExecutorService] that
 * drives the autosave loop.  All public methods on [OverlaySession] are
 * thread-safe.  [OverlaySession.waitForGameExit] blocks the *calling* thread.
 *
 * @param modLoader             loads and applies mod definitions
 * @param cheatApplier          applies individual cheat operations to save files
 * @param workspaceService      manages workspace file-system operations (for import/export)
 * @param shell                 executes shell commands on the device
 * @param autosaveIntervalMs    how often (ms) [TriggerMode.ON_AUTOSAVE] mods are applied
 *                              while the game is running (default: 30 000 ms)
 * @param processCheckIntervalMs how often (ms) [OverlaySession.waitForGameExit] polls
 *                              for game-process liveness (default: 2 000 ms)
 */
open class ModOverlayService(
    private val modLoader: ModLoader = ModLoader(),
    private val cheatApplier: CheatApplier = CheatApplier(),
    private val workspaceService: ModWorkspaceService = ModWorkspaceService(),
    private val shell: ShellExecutor = ShellExecutor(),
    val autosaveIntervalMs: Long = 30_000L,
    val processCheckIntervalMs: Long = 2_000L
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Session factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens a new [OverlaySession] for the given game.
     *
     * If any mod in [mods] has [TriggerMode.ON_AUTOSAVE], a background thread
     * immediately begins polling at [autosaveIntervalMs].  The session stays
     * active until [OverlaySession.stop] is called or [OverlaySession.waitForGameExit]
     * returns.
     *
     * @param mods            all mods that are active for this game run
     * @param appWorkspaceDir workspace directory for this game (`<workspace>/<pkg>/`)
     * @param packageName     Android package name; used to detect process liveness
     * @param workspace       workspace root; needed for save-data import/export
     * @param externalStorageRoot path to external storage root (e.g. `/sdcard`)
     */
    fun startSession(
        mods: List<ModDefinition>,
        appWorkspaceDir: Path,
        packageName: String,
        workspace: Path = appWorkspaceDir.parent ?: appWorkspaceDir,
        externalStorageRoot: Path = java.nio.file.Path.of("/sdcard")
    ): OverlaySession = OverlaySession(
        mods               = mods,
        appWorkspaceDir    = appWorkspaceDir,
        packageName        = packageName,
        workspace          = workspace,
        externalStorageRoot = externalStorageRoot
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Process detection helper (internal so tests can stub via a subclass)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns `true` if [packageName] has a live process on the device.
     *
     * Strategy:
     * 1. Try `pidof <pkg>` (available on Android ≥ 8 / busybox).
     * 2. Fall back to `ps | grep <pkg>` for older builds.
     *
     * Both commands are non-root; they only list processes visible to the
     * calling shell user.
     */
    internal open fun isGameRunning(packageName: String): Boolean {
        // ── Primary: pidof (fast, exact match) ──────────────────────────────
        val pidofResult = shell.execute("pidof $packageName", timeoutMs = 5_000L)
        if (pidofResult.exitCode == 0 && pidofResult.stdout.isNotBlank()) return true

        // ── Fallback: ps | grep (works on all Android versions) ─────────────
        val psResult = shell.execute("ps | grep $packageName", timeoutMs = 5_000L)
        return psResult.success && psResult.stdout.contains(packageName)
    }

    // =========================================================================
    //  OverlaySession – one live game session
    // =========================================================================

    /**
     * A live overlay session bound to a single game run.
     *
     * Obtained from [ModOverlayService.startSession]; the owner is responsible
     * for calling [stop] when the session is no longer needed (typically after
     * [waitForGameExit] returns and the post-exit phase completes).
     *
     * All public methods are thread-safe.
     */
    inner class OverlaySession(
        private val mods: List<ModDefinition>,
        private val appWorkspaceDir: Path,
        private val packageName: String,
        private val workspace: Path,
        private val externalStorageRoot: Path
    ) {

        /** Set to `false` by [stop]; prevents re-entrant cleanup. */
        @Volatile
        private var running: Boolean = true

        /**
         * Single-threaded executor that drives the autosave polling loop.
         * Named thread is a daemon so it never prevents JVM shutdown.
         */
        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mod-autosave-$packageName").also { it.isDaemon = true }
            }

        init {
            // ── Start autosave polling if any mod needs it ───────────────────
            val autosaveMods = mods.filter { it.triggerMode == TriggerMode.ON_AUTOSAVE }
            if (autosaveMods.isNotEmpty()) {
                scheduler.scheduleWithFixedDelay(
                    { runAutosaveCycle(autosaveMods) },
                    autosaveIntervalMs,     // initial delay
                    autosaveIntervalMs,     // period
                    TimeUnit.MILLISECONDS
                )
            }
        }

        // ── Autosave ──────────────────────────────────────────────────────────

        /**
         * Applies all [TriggerMode.ON_AUTOSAVE] mods once.
         *
         * Called by the scheduler on a background thread.  Errors are caught
         * per-mod so that one broken mod does not disrupt the others.
         *
         * If a mod specifies [SaveDataAction.IMPORT], the patched workspace data
         * is pushed to external storage immediately after patching, so the game
         * sees the new values on its very next autosave read.
         */
        private fun runAutosaveCycle(autosaveMods: List<ModDefinition>) {
            if (!running) return
            autosaveMods.forEach { mod ->
                runCatching { modLoader.applyMod(mod, appWorkspaceDir) }
                    .onSuccess {
                        // Push to device right away if the mod requests IMPORT
                        if (mod.saveDataAction == SaveDataAction.IMPORT) {
                            runCatching {
                                workspaceService.importExternalData(
                                    workspace,
                                    externalStorageRoot,
                                    packageName
                                )
                            }
                        }
                    }
            }
        }

        // ── On-demand overlay-button actions ──────────────────────────────────

        /**
         * Executes one overlay-button tap: applies the patches from [mod] whose
         * [com.smapifan.androidmodder.model.ModPatch.field] is listed in
         * [action]'s [OverlayAction.patchFields].
         *
         * After applying patches, if [mod] carries [SaveDataAction.IMPORT] the
         * workspace data is pushed to external storage immediately so the game
         * reads the new values on its next autosave cycle.
         *
         * @param mod    the mod this button belongs to
         * @param action the specific overlay action/button that was tapped
         * @param appDir workspace directory for this game; defaults to the
         *               directory passed to [ModOverlayService.startSession]
         * @return map of `field → newValue` for every patch that was applied
         */
        fun triggerAction(
            mod: ModDefinition,
            action: OverlayAction,
            appDir: Path = appWorkspaceDir
        ): Map<String, Long> {
            val results = LinkedHashMap<String, Long>()

            // Only apply patches whose field is explicitly listed in this action
            mod.patches
                .filter { it.field in action.patchFields }
                .forEach { patch ->
                    val syntheticCheat = CheatDefinition(
                        appName   = mod.gameId,
                        field     = patch.field,
                        operation = patch.operation,
                        amount    = patch.amount
                    )
                    runCatching {
                        results[patch.field] = cheatApplier.apply(appDir, syntheticCheat)
                    }
                }

            // Push to device if mod requests IMPORT (non-blocking, best-effort)
            if (mod.saveDataAction == SaveDataAction.IMPORT) {
                runCatching {
                    workspaceService.importExternalData(
                        workspace,
                        externalStorageRoot,
                        packageName
                    )
                }
            }

            return results
        }

        // ── Process monitoring ─────────────────────────────────────────────────

        /**
         * Returns `true` while the game process is alive **and** the session
         * has not been [stop]ped.
         */
        fun isGameRunning(): Boolean = running && this@ModOverlayService.isGameRunning(packageName)

        /**
         * Blocks the calling thread until the game process exits or [stop] is
         * called, polling every [processCheckIntervalMs] milliseconds.
         *
         * Returns immediately if the session has already been stopped.
         */
        fun waitForGameExit() {
            while (isGameRunning()) {
                Thread.sleep(processCheckIntervalMs)
            }
        }

        // ── UI helpers ─────────────────────────────────────────────────────────

        /**
         * Returns a flat list of `(mod, overlayAction)` pairs for every
         * [TriggerMode.ON_DEMAND] mod that has overlay actions defined.
         *
         * A UI layer can iterate this list to render one button per entry
         * without needing to know which parent mod each action belongs to.
         *
         * Example usage (console / ANSI output):
         * ```
         * session.overlayButtons().forEachIndexed { i, (mod, action) ->
         *     println("  [${i + 1}] ${action.label}  (${mod.name})")
         * }
         * ```
         */
        fun overlayButtons(): List<Pair<ModDefinition, OverlayAction>> =
            mods
                .filter { it.triggerMode == TriggerMode.ON_DEMAND }
                .flatMap { mod -> mod.overlayActions.map { action -> mod to action } }

        /**
         * Returns all [TriggerMode.ON_AUTOSAVE] mods active in this session.
         * Useful for displaying an overview of what is being polled.
         */
        fun autosaveMods(): List<ModDefinition> =
            mods.filter { it.triggerMode == TriggerMode.ON_AUTOSAVE }

        // ── Lifecycle ──────────────────────────────────────────────────────────

        /**
         * Stops the autosave scheduler and marks the session as finished.
         *
         * Idempotent – safe to call multiple times.
         */
        fun stop() {
            if (!running) return
            running = false
            scheduler.shutdownNow()
        }
    }
}
