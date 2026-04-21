package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SaveBackupService].
 *
 * A fake [ShellExecutor] records every command that would be issued and
 * returns a configurable exit-code, so no real Android device or file
 * system operations are required.
 */
class SaveBackupServiceTest {

    private class FakeShell(
        private val exitCode: Int = 0
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode, "", "")
        }
    }

    // ── path helpers ─────────────────────────────────────────────────────────

    @Test
    fun `backupRootDir returns correct path`() {
        val svc = SaveBackupService(FakeShell(), externalStorageRoot = "/sdcard")
        assertEquals(
            "/sdcard/Android/data/com.example.game/saves_backup",
            svc.backupRootDir("com.example.game")
        )
    }

    @Test
    fun `backupDataDir appends data subdirectory`() {
        val svc = SaveBackupService(FakeShell(), externalStorageRoot = "/sdcard")
        assertEquals(
            "/sdcard/Android/data/com.example.game/saves_backup/data",
            svc.backupDataDir("com.example.game")
        )
    }

    @Test
    fun `internalDataDir returns correct path`() {
        val svc = SaveBackupService(FakeShell())
        assertEquals("/data/data/com.example.game", svc.internalDataDir("com.example.game"))
    }

    // ── backupToExternal ─────────────────────────────────────────────────────

    @Test
    fun `backupToExternal creates backup dir and issues run-as cp command`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = SaveBackupService(fake, externalStorageRoot = "/sdcard")

        val result = svc.backupToExternal("com.example.game")

        assertTrue(result, "backupToExternal should return true on success")
        // mkdir command must have run
        assertTrue(fake.commands.any { it.contains("mkdir") && it.contains("saves_backup") })
        // run-as cp command must have run
        assertTrue(
            fake.commands.any { it.contains("run-as com.example.game") && it.contains("cp -r") },
            "Expected a run-as cp command; got: ${fake.commands}"
        )
    }

    @Test
    fun `backupToExternal returns false when copy fails`() {
        val fake = FakeShell(exitCode = 1) // all commands fail
        val svc  = SaveBackupService(fake, externalStorageRoot = "/sdcard")

        assertFalse(svc.backupToExternal("com.example.game"))
    }

    // ── writeRestoreScript ────────────────────────────────────────────────────

    @Test
    fun `writeRestoreScript issues base64 write and touch commands`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = SaveBackupService(fake, externalStorageRoot = "/sdcard")

        val result = svc.writeRestoreScript("com.example.game")

        assertTrue(result)
        // base64 decode into restore script
        assertTrue(
            fake.commands.any { it.contains("base64 -d") && it.contains("restore_saves.sh") },
            "Expected base64 decode command; got: ${fake.commands}"
        )
        // touch pending flag
        assertTrue(
            fake.commands.any { it.contains("touch") && it.contains(SaveBackupService.RESTORE_PENDING_FILENAME) },
            "Expected touch command for .restore_pending; got: ${fake.commands}"
        )
    }

    @Test
    fun `writeRestoreScript returns false when shell fails`() {
        val fake = FakeShell(exitCode = 1)
        val svc  = SaveBackupService(fake)

        assertFalse(svc.writeRestoreScript("com.example.game"))
    }

    // ── status checks ─────────────────────────────────────────────────────────

    @Test
    fun `hasBackup returns true when test command succeeds`() {
        val fake = FakeShell(exitCode = 0)
        assertTrue(SaveBackupService(fake).hasBackup("com.example.game"))
        assertTrue(fake.commands.any { it.startsWith("test -d") })
    }

    @Test
    fun `hasBackup returns false when test command fails`() {
        val fake = FakeShell(exitCode = 1)
        assertFalse(SaveBackupService(fake).hasBackup("com.example.game"))
    }

    @Test
    fun `hasRestorePending returns true when test command succeeds`() {
        val fake = FakeShell(exitCode = 0)
        assertTrue(SaveBackupService(fake).hasRestorePending("com.example.game"))
        assertTrue(
            fake.commands.any { it.contains(SaveBackupService.RESTORE_PENDING_FILENAME) }
        )
    }

    @Test
    fun `hasRestorePending returns false when test command fails`() {
        val fake = FakeShell(exitCode = 1)
        assertFalse(SaveBackupService(fake).hasRestorePending("com.example.game"))
    }

    // ── buildRestoreScript ────────────────────────────────────────────────────

    @Test
    fun `buildRestoreScript contains shebang and cp command`() {
        val svc    = SaveBackupService(FakeShell(), externalStorageRoot = "/sdcard")
        val script = svc.buildRestoreScript("com.example.game")

        assertTrue(script.startsWith("#!/bin/sh"), "Script must start with shebang")
        assertTrue(script.contains("cp -r"), "Script must contain cp -r")
        assertTrue(
            script.contains("/sdcard/Android/data/com.example.game/saves_backup/data"),
            "Script must reference backup source"
        )
        assertTrue(
            script.contains("/data/data/com.example.game"),
            "Script must reference internal data destination"
        )
    }

    @Test
    fun `buildRestoreScript cleans up pending flag and itself`() {
        val svc    = SaveBackupService(FakeShell(), externalStorageRoot = "/sdcard")
        val script = svc.buildRestoreScript("com.example.game")

        assertTrue(
            script.contains("rm -f") && script.contains(SaveBackupService.RESTORE_PENDING_FILENAME),
            "Script must remove the .restore_pending flag"
        )
        assertTrue(
            script.contains(SaveBackupService.RESTORE_SCRIPT_FILENAME),
            "Script must remove itself"
        )
    }
}
