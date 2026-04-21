package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RunAsExecutor].
 *
 * All tests use a fake [ShellExecutor] so that no real Android device or
 * `run-as` binary is required.
 */
class RunAsExecutorTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Fake
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeShell(
        private val exitCode: Int = 0,
        private val stdout: String = ""
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode, stdout, "")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  execute – command wrapping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `execute wraps command with run-as and sh -c`() {
        val fake = FakeShell()
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        runAs.execute("ls /data/data/com.gram.mergedragons")

        assertEquals(1, fake.commands.size)
        val cmd = fake.commands[0]
        assertTrue(cmd.startsWith("run-as com.gram.mergedragons sh -c"), "Command must start with run-as prefix")
        assertTrue(cmd.contains("ls /data/data/com.gram.mergedragons"), "Inner command must be present")
    }

    @Test
    fun `execute returns ShellResult from underlying shell`() {
        val fake  = FakeShell(exitCode = 0, stdout = "file1\nfile2")
        val runAs = RunAsExecutor("com.example.game", fake)

        val result = runAs.execute("ls")

        assertTrue(result.success)
        assertEquals("file1\nfile2", result.stdout)
    }

    @Test
    fun `execute propagates non-zero exit code`() {
        val fake  = FakeShell(exitCode = 1)
        val runAs = RunAsExecutor("com.example.game", fake)

        val result = runAs.execute("cat nonexistent")

        assertFalse(result.success)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  exportDataDir / importDataDir
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `exportDataDir issues cp -r command via run-as`() {
        val fake  = FakeShell()
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        runAs.exportDataDir("/data/data/com.gram.mergedragons", "/sdcard/workspace/internal")

        val cmd = fake.commands[0]
        assertTrue(cmd.contains("cp -r /data/data/com.gram.mergedragons"))
        assertTrue(cmd.contains("/sdcard/workspace/internal"))
    }

    @Test
    fun `importDataDir issues cp -r command via run-as`() {
        val fake  = FakeShell()
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        runAs.importDataDir("/sdcard/workspace/internal", "/data/data/com.gram.mergedragons")

        val cmd = fake.commands[0]
        assertTrue(cmd.contains("cp -r /sdcard/workspace/internal"))
        assertTrue(cmd.contains("/data/data/com.gram.mergedragons"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  readFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `readFile issues cat command and returns content`() {
        val fake  = FakeShell(exitCode = 0, stdout = "coins=500\ngems=10")
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        val result = runAs.readFile("/data/data/com.gram.mergedragons/files/save.dat")

        assertTrue(result.success)
        assertEquals("coins=500\ngems=10", result.stdout)
        assertTrue(fake.commands[0].contains("cat"))
        assertTrue(fake.commands[0].contains("save.dat"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  writeFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `writeFile encodes content as base64 and pipes through base64 -d`() {
        val fake  = FakeShell()
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        runAs.writeFile("/data/data/com.gram.mergedragons/files/save.dat", "coins=9999\ngems=50")

        val cmd = fake.commands[0]
        assertTrue(cmd.contains("base64 -d"), "Write command must use base64 decode")
        assertTrue(cmd.contains("save.dat"), "Write command must reference the target file")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  isAvailable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isAvailable returns true when run-as succeeds`() {
        val fake  = FakeShell(exitCode = 0)
        val runAs = RunAsExecutor("com.gram.mergedragons", fake)

        assertTrue(runAs.isAvailable())
        // Verify that the availability check uses `run-as <pkg> true`
        assertTrue(fake.commands[0].contains("run-as com.gram.mergedragons"))
        assertTrue(fake.commands[0].contains("true"))
    }

    @Test
    fun `isAvailable returns false when run-as fails (non-debuggable app)`() {
        val fake  = FakeShell(exitCode = 1)
        val runAs = RunAsExecutor("com.production.app", fake)

        assertFalse(runAs.isAvailable())
    }
}
