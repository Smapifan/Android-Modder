package com.smapifan.androidmodder.model

/**
 * Configuration for launching a game through Android-Modder's launcher.
 *
 * ## How the launcher works
 *
 * The launcher calls `am start` to start the game in its own normal sandbox
 * with its real user-ID.  The game runs completely unmodified – it "doesn't know"
 * it is being modded.  The launcher's job is to:
 *
 * 1. Export the game's save data into the workspace.
 * 2. Apply cheats/mods to the workspace copy.
 * 3. Push the modified data back so the game reads it.
 *
 * The [dataAccessStrategy] controls *how* step 1 and 3 are performed.
 *
 * @param packageName         the game's package name, e.g. `"com.gram.mergedragons"`
 * @param launchCommand       shell command to start the game,
 *                            e.g. `"am start -n com.gram.mergedragons/.MainActivity"`
 * @param dataAccessStrategy  determines how save data is read/written (default:
 *                            [DataAccessStrategy.EXTERNAL_STORAGE], no root required)
 * @param useRootForData      legacy flag; when `true` maps to [DataAccessStrategy.ROOT].
 *                            Ignored when [dataAccessStrategy] is set explicitly.
 * @param deviceDataRoot      root of the device's internal data directory, default `/data`
 * @param externalStorageRoot root of external storage, default `/sdcard`
 * @param importAfterExit     if `true`, modified workspace data is imported back after
 *                            the game process exits
 */
data class GameLaunchConfig(
    val packageName: String,
    val launchCommand: String,

    /**
     * How to access the game's save data.
     *
     * - [DataAccessStrategy.EXTERNAL_STORAGE] – `/sdcard/Android/data/<pkg>/`, no root (default).
     * - [DataAccessStrategy.RUN_AS]           – `run-as <pkg>`, no root, debuggable apps only.
     * - [DataAccessStrategy.ROOT]             – `su -c "cp …"`, root required, any app.
     * - [DataAccessStrategy.PROCESS_MEMORY]   – `/proc/<pid>/mem`, root/ptrace, live injection.
     */
    val dataAccessStrategy: DataAccessStrategy = DataAccessStrategy.EXTERNAL_STORAGE,

    /**
     * Legacy compatibility flag.  When `true` and [dataAccessStrategy] is the
     * default [DataAccessStrategy.EXTERNAL_STORAGE], the effective strategy is
     * upgraded to [DataAccessStrategy.ROOT].  Ignored if [dataAccessStrategy]
     * is set explicitly to any non-default value.
     */
    val useRootForData: Boolean = false,

    val deviceDataRoot: String = "/data",
    val externalStorageRoot: String = "/sdcard",
    val importAfterExit: Boolean = true
) {
    /**
     * The **effective** strategy after resolving the [useRootForData] legacy flag.
     *
     * Callers should use this instead of [dataAccessStrategy] directly to
     * ensure backward compatibility.
     */
    val effectiveStrategy: DataAccessStrategy
        get() = if (dataAccessStrategy == DataAccessStrategy.EXTERNAL_STORAGE && useRootForData) {
            DataAccessStrategy.ROOT
        } else {
            dataAccessStrategy
        }
}
