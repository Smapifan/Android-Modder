package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  RunAsExecutor
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Executes shell commands **as the target app's own user-ID** via `run-as <pkg>`.
 *
 * ## Why this matters (the "no-root trick")
 *
 * Android's process model means every app runs as a unique Linux user.  The
 * `/data/data/<pkg>/` directory is owned by that user and is inaccessible to
 * other processes – even the shell – without root.
 *
 * `run-as <pkg>` is an Android shell utility that temporarily adopts the UID of
 * `<pkg>` for the duration of one command.  Because the command runs *as* the
 * app, it has the same read/write access to `/data/data/<pkg>/` that the app
 * itself has.  **No root is required.**
 *
 * ## Prerequisite
 *
 * The target app must have `android:debuggable="true"` in its `AndroidManifest.xml`.
 * This is the case for:
 * - Apps installed from the Google Play Console's **Internal Testing** track.
 * - Manually side-loaded debug APKs (`./gradlew assembleDebug`).
 * - Games that ship a dedicated "modding" or "beta" build.
 *
 * Production Play Store releases are **not** debuggable; use
 * [com.smapifan.androidmodder.model.DataAccessStrategy.ROOT] or
 * [com.smapifan.androidmodder.model.DataAccessStrategy.EXTERNAL_STORAGE] for those.
 *
 * ## How the launcher uses it
 *
 * The game is still started via `am start` in its own normal sandbox.
 * `RunAsExecutor` is only used for the **data-copy steps**
 * (export before launch, import after exit); the game process itself is never
 * touched.
 *
 * Example commands that `RunAsExecutor` can issue:
 * ```shell
 * run-as com.gram.mergedragons ls /data/data/com.gram.mergedragons/files/
 * run-as com.gram.mergedragons cat /data/data/com.gram.mergedragons/files/save.dat
 * run-as com.gram.mergedragons sh -c "cp /sdcard/ws/save.dat /data/data/com.gram.mergedragons/files/save.dat"
 * ```
 *
 * @param packageName the Android package whose UID is adopted for each command
 * @param shell       underlying [ShellExecutor] used to run the wrapped command
 */
class RunAsExecutor(
    val packageName: String,
    private val shell: ShellExecutor = ShellExecutor()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Core execute
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs [command] as [packageName]'s user via `run-as <pkg> sh -c '<command>'`.
     *
     * Using `sh -c` as the command wrapper allows arbitrary shell pipelines and
     * redirections that `run-as` alone would not interpret.
     *
     * @param command    the shell command to execute as the app's UID
     * @param timeoutMs  maximum wait time (ms); 0 = no timeout
     * @return the [ShellResult] produced by the underlying [ShellExecutor]
     */
    fun execute(command: String, timeoutMs: Long = 30_000L): ShellResult =
        shell.execute("run-as $packageName sh -c '$command'", timeoutMs = timeoutMs)

    // ─────────────────────────────────────────────────────────────────────────
    //  Convenience: data copy helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies the app's internal data directory to [destination] using `cp -r`.
     *
     * Equivalent to:
     * ```shell
     * run-as <pkg> sh -c 'cp -r /data/data/<pkg>/. <destination>/'
     * ```
     *
     * @param internalDataDir path to `/data/data/<pkg>/`
     * @param destination     target directory (must be writable by the shell user)
     */
    fun exportDataDir(internalDataDir: String, destination: String): ShellResult =
        execute("cp -r $internalDataDir/. $destination/")

    /**
     * Copies files from [source] back into the app's internal data directory.
     *
     * Equivalent to:
     * ```shell
     * run-as <pkg> sh -c 'cp -r <source>/. /data/data/<pkg>/'
     * ```
     *
     * @param source          directory containing the patched files
     * @param internalDataDir path to `/data/data/<pkg>/`
     */
    fun importDataDir(source: String, internalDataDir: String): ShellResult =
        execute("cp -r $source/. $internalDataDir/")

    /**
     * Reads a single file as the app's user and returns its content as a string.
     *
     * @param filePath full path to the file inside the app's sandbox,
     *                 e.g. `/data/data/com.gram.mergedragons/files/save.dat`
     */
    fun readFile(filePath: String): ShellResult =
        execute("cat $filePath")

    /**
     * Writes [content] to [filePath] inside the app's sandbox.
     *
     * The content is base64-encoded before passing to the shell to avoid
     * special-character escaping issues with arbitrary save-file content.
     *
     * @param filePath full path to the destination file
     * @param content  raw file content to write
     */
    fun writeFile(filePath: String, content: String): ShellResult {
        // Encode as base64 so that newlines, quotes, and special chars in the
        // content do not break the shell command.
        val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray())
        return execute("echo $encoded | base64 -d > $filePath")
    }

    /**
     * Checks whether [packageName] is accessible via `run-as` on this device.
     *
     * Returns `true` if the app is installed **and** debuggable.  A non-zero
     * exit code from `run-as` (with a deliberately harmless `true` command)
     * means the app is either not installed or not debuggable.
     */
    fun isAvailable(): Boolean =
        shell.execute("run-as $packageName true", timeoutMs = 5_000L).success
}
