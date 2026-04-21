package com.smapifan.androidmodder.service

/**
 * Result of a shell command execution.
 *
 * @param exitCode 0 = success, non-zero = failure
 * @param stdout   captured standard output
 * @param stderr   captured standard error
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val success: Boolean get() = exitCode == 0
}
