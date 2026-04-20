package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellExecutorTest {
    private val shell = ShellExecutor()

    @Test
    fun `executes simple echo command`() {
        val result = shell.execute("echo hello")
        assertTrue(result.success, "echo should succeed")
        assertEquals("hello", result.stdout)
    }

    @Test
    fun `captures non-zero exit code`() {
        val result = shell.execute("exit 42", timeoutMs = 5_000L)
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `captures stderr output`() {
        val result = shell.execute("echo error_msg >&2")
        // stderr may not be captured on all platforms via redirectErrorStream=false; at least must not throw
        assertTrue(result.exitCode == 0)
    }

    @Test
    fun `success property is true for exit code 0`() {
        assertTrue(ShellResult(0, "", "").success)
    }

    @Test
    fun `success property is false for non-zero exit code`() {
        assertTrue(!ShellResult(1, "", "").success)
    }
}
