package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatOperation
import com.smapifan.androidmodder.model.ModDefinition
import com.smapifan.androidmodder.model.ModPatch
import com.smapifan.androidmodder.model.TriggerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [LaunchActivationService].
 *
 * All tests use a fake [ShellExecutor] so no real file-system or Android
 * device is involved.
 */
class LaunchActivationServiceTest {

    private class FakeShell(
        private val exitCode: Int = 0
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode, "", "")
        }
    }

    // ── tokenDir ─────────────────────────────────────────────────────────────

    @Test
    fun `tokenDir returns correct path`() {
        val svc = LaunchActivationService(FakeShell(), externalStorageRoot = "/sdcard")
        assertEquals(
            "/sdcard/Android/data/com.example.game/files",
            svc.tokenDir("com.example.game")
        )
    }

    // ── writeToken ───────────────────────────────────────────────────────────

    @Test
    fun `writeToken writes launcher script and token file`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = LaunchActivationService(fake, externalStorageRoot = "/sdcard")

        val result = svc.writeToken("com.example.game", emptyList())

        assertTrue(result)
        assertTrue(
            fake.commands.any { it.contains("base64 -d") && it.contains("mod_launcher.sh") },
            "Expected base64 decode → mod_launcher.sh; got: ${fake.commands}"
        )
        assertTrue(
            fake.commands.any { it.contains("touch") && it.contains(LaunchActivationService.TOKEN_FILENAME) },
            "Expected touch .launcher_session; got: ${fake.commands}"
        )
    }

    @Test
    fun `writeToken returns false when shell fails`() {
        val fake = FakeShell(exitCode = 1)
        assertFalse(LaunchActivationService(fake).writeToken("com.example.game", emptyList()))
    }

    // ── clearToken ────────────────────────────────────────────────────────────

    @Test
    fun `clearToken issues rm commands for token and script`() {
        val fake = FakeShell()
        LaunchActivationService(fake, externalStorageRoot = "/sdcard")
            .clearToken("com.example.game")

        assertTrue(
            fake.commands.any { it.contains("rm -f") && it.contains(LaunchActivationService.TOKEN_FILENAME) }
        )
        assertTrue(
            fake.commands.any { it.contains("rm -f") && it.contains("mod_launcher.sh") }
        )
    }

    // ── hasToken ──────────────────────────────────────────────────────────────

    @Test
    fun `hasToken returns true when test command succeeds`() {
        val fake = FakeShell(exitCode = 0)
        assertTrue(LaunchActivationService(fake).hasToken("com.example.game"))
    }

    @Test
    fun `hasToken returns false when test command fails`() {
        val fake = FakeShell(exitCode = 1)
        assertFalse(LaunchActivationService(fake).hasToken("com.example.game"))
    }

    // ── instructionsFromMods ─────────────────────────────────────────────────

    @Test
    fun `instructionsFromMods converts mod patches to instructions`() {
        val svc = LaunchActivationService(FakeShell())
        val mod = ModDefinition(
            name    = "TestMod",
            gameId  = "com.example.game",
            patches = listOf(
                ModPatch("coins", CheatOperation.ADD,  1000L),
                ModPatch("gems",  CheatOperation.SET,  999L)
            )
        )

        val instructions = svc.instructionsFromMods("com.example.game", listOf(mod))

        assertEquals(2, instructions.size)
        assertEquals("coins", instructions[0].field)
        assertEquals("ADD",   instructions[0].operation)
        assertEquals(1000L,   instructions[0].value)
        assertEquals("gems",  instructions[1].field)
        assertEquals("SET",   instructions[1].operation)
        assertEquals(999L,    instructions[1].value)
    }

    @Test
    fun `instructionsFromMods encodes correct save file path`() {
        val svc = LaunchActivationService(FakeShell())
        val mod = ModDefinition(
            name    = "M",
            gameId  = "com.example.game",
            patches = listOf(ModPatch("coins", CheatOperation.ADD, 1L))
        )

        val instructions = svc.instructionsFromMods(
            "com.example.game", listOf(mod), deviceDataRoot = "/data"
        )

        assertTrue(
            instructions[0].filePath.endsWith("/data/data/com.example.game/files/save.dat"),
            "File path should include device data root: ${instructions[0].filePath}"
        )
    }

    @Test
    fun `instructionsFromMods returns empty list for mods with no patches`() {
        val svc = LaunchActivationService(FakeShell())
        val mod = ModDefinition(name = "Empty", gameId = "com.example.game", patches = emptyList())

        assertTrue(svc.instructionsFromMods("com.example.game", listOf(mod)).isEmpty())
    }

    // ── buildPatchCommand ─────────────────────────────────────────────────────

    @Test
    fun `buildPatchCommand SET produces sed command`() {
        val svc = LaunchActivationService(FakeShell())
        val cmd = svc.buildPatchCommand(
            PatchInstruction("/data/data/pkg/files/save.dat", "coins", "SET", 9999L)
        )
        assertTrue(cmd.contains("sed"), "SET must use sed; got: $cmd")
        assertTrue(cmd.contains("coins=9999"), "SET command must embed field=value")
    }

    @Test
    fun `buildPatchCommand ADD produces awk command`() {
        val svc = LaunchActivationService(FakeShell())
        val cmd = svc.buildPatchCommand(
            PatchInstruction("/data/data/pkg/files/save.dat", "coins", "ADD", 500L)
        )
        assertTrue(cmd.contains("awk"), "ADD must use awk; got: $cmd")
        assertTrue(cmd.contains("500"), "ADD command must contain the delta value")
    }

    @Test
    fun `buildPatchCommand SUBTRACT produces awk command with non-negative guard`() {
        val svc = LaunchActivationService(FakeShell())
        val cmd = svc.buildPatchCommand(
            PatchInstruction("/data/data/pkg/files/save.dat", "lives", "SUBTRACT", 1L)
        )
        assertTrue(cmd.contains("awk"), "SUBTRACT must use awk; got: $cmd")
        // The generated awk must guard against negative values
        assertTrue(cmd.contains("v<0?0:v") || cmd.contains("(v<0"), "SUBTRACT must guard negatives; got: $cmd")
    }

    @Test
    fun `buildPatchCommand MUL produces awk multiply command`() {
        val svc = LaunchActivationService(FakeShell())
        val cmd = svc.buildPatchCommand(
            PatchInstruction("/data/data/pkg/files/save.dat", "coins", "MUL", 2L)
        )
        assertTrue(cmd.contains("awk") && cmd.contains("*"), "MUL must use awk with *; got: $cmd")
    }

    @Test
    fun `buildPatchCommand unknown operation emits comment`() {
        val svc = LaunchActivationService(FakeShell())
        val cmd = svc.buildPatchCommand(
            PatchInstruction("/data/data/pkg/files/save.dat", "coins", "UNKNOWN_OP", 1L)
        )
        assertTrue(cmd.startsWith("#"), "Unknown operation must emit a comment; got: $cmd")
    }

    // ── buildModLauncherScript ────────────────────────────────────────────────

    @Test
    fun `buildModLauncherScript contains shebang and cleanup`() {
        val svc    = LaunchActivationService(FakeShell(), externalStorageRoot = "/sdcard")
        val script = svc.buildModLauncherScript("com.example.game", emptyList())

        assertTrue(script.startsWith("#!/bin/sh"), "Script must start with shebang")
        assertTrue(
            script.contains(LaunchActivationService.TOKEN_FILENAME),
            "Script must clean up the session token"
        )
        assertTrue(script.contains("mod_launcher.sh"), "Script must clean up itself")
    }

    @Test
    fun `buildModLauncherScript embeds patch commands`() {
        val svc = LaunchActivationService(FakeShell(), externalStorageRoot = "/sdcard")
        val instructions = listOf(
            PatchInstruction("/data/data/com.example.game/files/save.dat", "coins", "SET", 9999L)
        )
        val script = svc.buildModLauncherScript("com.example.game", instructions)

        assertTrue(script.contains("sed"), "Script must embed patch command; got:\n$script")
    }

    @Test
    fun `buildModLauncherScript writes backup to external storage`() {
        val svc    = LaunchActivationService(FakeShell(), externalStorageRoot = "/sdcard")
        val script = svc.buildModLauncherScript("com.example.game", emptyList())

        assertTrue(
            script.contains("saves_backup"),
            "Script must back up saves to external storage; got:\n$script"
        )
    }

    @Test
    fun `ON_LAUNCH mods are used for instructionsFromMods`() {
        val svc = LaunchActivationService(FakeShell())
        val onLaunch = ModDefinition(
            name        = "LaunchMod",
            gameId      = "com.example.game",
            triggerMode = TriggerMode.ON_LAUNCH,
            patches     = listOf(ModPatch("coins", CheatOperation.ADD, 100L))
        )
        val onDemand = ModDefinition(
            name        = "DemandMod",
            gameId      = "com.example.game",
            triggerMode = TriggerMode.ON_DEMAND,
            patches     = listOf(ModPatch("gems", CheatOperation.SET, 999L))
        )
        // Caller filters by trigger mode before calling instructionsFromMods
        val filtered = listOf(onLaunch, onDemand).filter { it.triggerMode == TriggerMode.ON_LAUNCH }
        val instructions = svc.instructionsFromMods("com.example.game", filtered)

        assertEquals(1, instructions.size, "Only ON_LAUNCH patches should be included")
        assertEquals("coins", instructions[0].field)
    }
}
