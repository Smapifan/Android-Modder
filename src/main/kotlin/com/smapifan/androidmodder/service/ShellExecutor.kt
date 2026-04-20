package com.smapifan.androidmodder.service

/**
 * Executes shell commands via [ProcessBuilder].
 *
 * On Android the process already has root if the user granted it via
 * Magisk / SuperSU. Pass [asRoot] = true to prefix the command with `su -c`,
 * which is needed to access `/data/data/<other_app>/` on a standard device.
 *
 * Example (Android):
 * ```kotlin
 * val shell = ShellExecutor()
 *
 * // Launch a game
 * shell.execute("am start -n com.gram.mergedragons/.MainActivity")
 *
 * // Copy internal app data with root
 * shell.execute("cp -r /data/data/com.gram.mergedragons/ /sdcard/workspace/", asRoot = true)
 * ```
 */
open class ShellExecutor {

    /**
     * Runs [command] in a shell and returns a [ShellResult].
     *
     * @param command  the shell command to execute
     * @param asRoot   if `true`, wraps the command with `su -c '...'`
     * @param timeoutMs maximum time to wait for the process (ms); 0 = no timeout
     */
    open fun execute(command: String, asRoot: Boolean = false, timeoutMs: Long = 30_000L): ShellResult {
        val fullCommand = if (asRoot) listOf("su", "-c", command) else listOf("sh", "-c", command)

        val process = ProcessBuilder(fullCommand)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        val finished = if (timeoutMs > 0) {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } else {
            process.waitFor()
            true
        }

        return if (finished) {
            ShellResult(exitCode = process.exitValue(), stdout = stdout.trim(), stderr = stderr.trim())
        } else {
            process.destroyForcibly()
            ShellResult(exitCode = -1, stdout = stdout.trim(), stderr = "Timed out after ${timeoutMs}ms")
        }
    }
}
