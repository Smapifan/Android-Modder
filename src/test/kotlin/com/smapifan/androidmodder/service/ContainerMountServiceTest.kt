package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ContainerMountService].
 *
 * All tests use a [FakeShell] that records commands and returns configurable
 * responses – no real `mount` binary or Android device is needed.
 */
class ContainerMountServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Fake shell
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeShell(
        private val defaultExitCode: Int = 0
    ) : ShellExecutor() {
        val commands = mutableListOf<Pair<String, Boolean>>() // command, asRoot

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command to asRoot
            return ShellResult(exitCode = defaultExitCode, stdout = "", stderr = "")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  mountSavePath
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `mountSavePath issues bind-mount with correct paths`() {
        val fake = FakeShell()
        val svc  = ContainerMountService(fake)

        val ok = svc.mountSavePath("com.gram.mergedragons", 11, "/sdcard/workspace/saves")

        assertTrue(ok)
        val (cmd, asRoot) = fake.commands.first()
        assertTrue(asRoot)
        assertTrue(cmd.startsWith("mount --bind"))
        assertTrue(cmd.contains("/sdcard/workspace/saves"))
        assertTrue(cmd.contains("/data/user/11/com.gram.mergedragons"))
    }

    @Test
    fun `mountSavePath returns false when mount fails`() {
        val fake = FakeShell(defaultExitCode = 1)
        val svc  = ContainerMountService(fake)

        assertFalse(svc.mountSavePath("com.example", 11, "/tmp/saves"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  unmountSavePath
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unmountSavePath issues umount for correct target`() {
        val fake = FakeShell()
        val svc  = ContainerMountService(fake)

        val ok = svc.unmountSavePath("com.gram.mergedragons", 11)

        assertTrue(ok)
        val (cmd, asRoot) = fake.commands.first()
        assertTrue(asRoot)
        assertTrue(cmd.startsWith("umount"))
        assertTrue(cmd.contains("/data/user/11/com.gram.mergedragons"))
    }

    @Test
    fun `unmountSavePath returns false when umount fails`() {
        val fake = FakeShell(defaultExitCode = 1)
        val svc  = ContainerMountService(fake)

        assertFalse(svc.unmountSavePath("com.example", 11))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  mountExternalSavePath
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `mountExternalSavePath issues bind-mount for external storage path`() {
        val fake = FakeShell()
        val svc  = ContainerMountService(fake)

        val ok = svc.mountExternalSavePath("com.gram.mergedragons", "/sdcard/workspace/ext")

        assertTrue(ok)
        val (cmd, asRoot) = fake.commands.first()
        assertTrue(asRoot)
        assertTrue(cmd.startsWith("mount --bind"))
        assertTrue(cmd.contains("/sdcard/workspace/ext"))
        assertTrue(cmd.contains("/sdcard/Android/data/com.gram.mergedragons"))
    }

    @Test
    fun `mountExternalSavePath respects custom externalStorageRoot`() {
        val fake = FakeShell()
        val svc  = ContainerMountService(fake)

        svc.mountExternalSavePath("com.example", "/saves", externalStorageRoot = "/mnt/sdcard")

        val (cmd, _) = fake.commands.first()
        assertTrue(cmd.contains("/mnt/sdcard/Android/data/com.example"))
    }

    @Test
    fun `mountExternalSavePath returns false on failure`() {
        val fake = FakeShell(defaultExitCode = 1)
        val svc  = ContainerMountService(fake)

        assertFalse(svc.mountExternalSavePath("com.example", "/saves"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  unmountExternalSavePath
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unmountExternalSavePath issues umount for external path`() {
        val fake = FakeShell()
        val svc  = ContainerMountService(fake)

        val ok = svc.unmountExternalSavePath("com.gram.mergedragons")

        assertTrue(ok)
        val (cmd, asRoot) = fake.commands.first()
        assertTrue(asRoot)
        assertTrue(cmd.startsWith("umount"))
        assertTrue(cmd.contains("/sdcard/Android/data/com.gram.mergedragons"))
    }

    @Test
    fun `unmountExternalSavePath returns false on failure`() {
        val fake = FakeShell(defaultExitCode = 1)
        val svc  = ContainerMountService(fake)

        assertFalse(svc.unmountExternalSavePath("com.example"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `internalDataPath builds correct path`() {
        val svc = ContainerMountService()
        assertEquals(
            "/data/user/11/com.gram.mergedragons",
            svc.internalDataPath("com.gram.mergedragons", 11)
        )
    }

    @Test
    fun `externalDataPath builds correct path with default sdcard root`() {
        val svc = ContainerMountService()
        assertEquals(
            "/sdcard/Android/data/com.gram.mergedragons",
            svc.externalDataPath("com.gram.mergedragons")
        )
    }

    @Test
    fun `externalDataPath builds correct path with custom root`() {
        val svc = ContainerMountService()
        assertEquals(
            "/mnt/sdcard/Android/data/com.example",
            svc.externalDataPath("com.example", "/mnt/sdcard")
        )
    }
}
