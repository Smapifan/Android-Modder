package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatOperation
import com.smapifan.androidmodder.model.ModDefinition
import com.smapifan.androidmodder.model.ModPatch
import com.smapifan.androidmodder.model.OverlayAction
import com.smapifan.androidmodder.model.SaveDataAction
import com.smapifan.androidmodder.model.TriggerMode
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ModOverlayService] and its inner [ModOverlayService.OverlaySession].
 *
 * All tests use a fake [ShellExecutor] and a fake [ModOverlayService] subclass so that
 * no real shell process or Android device is needed.
 */
class ModOverlayServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Fakes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A [ShellExecutor] that records every command without executing anything.
     * Returns a configurable [exitCode] for all commands.
     */
    private class FakeShellExecutor(private val exitCode: Int = 0) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode = exitCode, stdout = "fake-pid-123", stderr = "")
        }
    }

    /**
     * A [ModOverlayService] subclass that overrides [isGameRunning] so tests
     * can control whether the game appears to be running without a real shell.
     */
    private inner class FakeOverlayService(
        fakeShell: ShellExecutor = FakeShellExecutor(),
        private val gameRunning: () -> Boolean = { false }
    ) : ModOverlayService(
        shell                  = fakeShell,
        autosaveIntervalMs     = 100L,    // fast interval for tests
        processCheckIntervalMs = 50L      // fast polling for tests
    ) {
        override fun isGameRunning(packageName: String): Boolean = gameRunning()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeWorkspace(vararg files: Pair<String, String>): java.nio.file.Path {
        val dir = Files.createTempDirectory("overlay-test")
        files.forEach { (relativePath, content) ->
            val file = dir.resolve(relativePath)
            file.parent?.createDirectories()
            Files.writeString(file, content)
        }
        return dir
    }

    private fun coinsMod(
        triggerMode: TriggerMode = TriggerMode.ON_DEMAND,
        saveDataAction: SaveDataAction? = null
    ) = ModDefinition(
        name        = "InfiniteCoins",
        gameId      = "com.gram.mergedragons",
        triggerMode = triggerMode,
        saveDataAction = saveDataAction,
        patches     = listOf(
            ModPatch("coins", CheatOperation.ADD,  10_000L),
            ModPatch("gems",  CheatOperation.SET,  999L)
        ),
        overlayActions = listOf(
            OverlayAction("+10k Coins", listOf("coins")),
            OverlayAction("Max Gems",   listOf("gems"))
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  triggerAction  (ON_DEMAND)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `triggerAction applies only the fields listed in the action`() {
        val appDir = makeWorkspace("save/coins.dat" to "coins=500\ngems=10")
        val mod    = coinsMod()

        val service = FakeOverlayService()
        val session = service.startSession(listOf(mod), appDir, "com.gram.mergedragons")
        try {
            // "+10k Coins" button should only touch 'coins'
            val result = session.triggerAction(mod, mod.overlayActions[0], appDir)

            assertEquals(setOf("coins"), result.keys, "Only 'coins' should be patched")
            assertEquals(10_500L, result["coins"])
            // gems should be untouched
            val fields = CheatApplier().readFields(appDir.resolve("save/coins.dat"))
            assertEquals("10", fields["gems"])
        } finally {
            session.stop()
        }
    }

    @Test
    fun `triggerAction for Max Gems only changes gems`() {
        val appDir = makeWorkspace("save/save.dat" to "coins=100\ngems=5")
        val mod    = coinsMod()

        val service = FakeOverlayService()
        val session = service.startSession(listOf(mod), appDir, "com.gram.mergedragons")
        try {
            val result = session.triggerAction(mod, mod.overlayActions[1], appDir)  // "Max Gems"

            assertEquals(setOf("gems"), result.keys)
            assertEquals(999L, result["gems"])
            val fields = CheatApplier().readFields(appDir.resolve("save/save.dat"))
            assertEquals("100", fields["coins"], "coins must remain unchanged")
        } finally {
            session.stop()
        }
    }

    @Test
    fun `triggerAction with empty patchFields returns empty map`() {
        val appDir  = makeWorkspace("save/save.dat" to "coins=100")
        val mod     = coinsMod()
        val noopAction = OverlayAction("Noop", emptyList())

        val service = FakeOverlayService()
        val session = service.startSession(listOf(mod), appDir, "com.gram.mergedragons")
        try {
            val result = session.triggerAction(mod, noopAction, appDir)
            assertTrue(result.isEmpty(), "No patches should fire for an empty patchFields list")
        } finally {
            session.stop()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  overlayButtons
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `overlayButtons returns only ON_DEMAND mods`() {
        val onDemand    = coinsMod(TriggerMode.ON_DEMAND)
        val onAutosave  = coinsMod(TriggerMode.ON_AUTOSAVE).copy(name = "AutoMod", overlayActions = listOf(
            OverlayAction("AutoAction", listOf("coins"))
        ))
        val onLaunch    = coinsMod(TriggerMode.ON_LAUNCH).copy(name = "LaunchMod")

        val service = FakeOverlayService()
        val session = service.startSession(
            listOf(onDemand, onAutosave, onLaunch),
            Files.createTempDirectory("btn-test"),
            "com.gram.mergedragons"
        )
        try {
            val buttons = session.overlayButtons()
            // Only the ON_DEMAND mod's actions should appear
            assertEquals(2, buttons.size, "Only ON_DEMAND overlay actions")
            assertTrue(buttons.all { (mod, _) -> mod.triggerMode == TriggerMode.ON_DEMAND })
            assertEquals(listOf("+10k Coins", "Max Gems"), buttons.map { (_, a) -> a.label })
        } finally {
            session.stop()
        }
    }

    @Test
    fun `overlayButtons is empty when no ON_DEMAND mods present`() {
        val mod = coinsMod(TriggerMode.ON_LAUNCH)

        val service = FakeOverlayService()
        val session = service.startSession(
            listOf(mod),
            Files.createTempDirectory("no-btn-test"),
            "com.gram.mergedragons"
        )
        try {
            assertTrue(session.overlayButtons().isEmpty())
        } finally {
            session.stop()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  autosaveMods
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `autosaveMods returns only ON_AUTOSAVE mods`() {
        val autoMod   = coinsMod(TriggerMode.ON_AUTOSAVE).copy(name = "AutoMod")
        val launchMod = coinsMod(TriggerMode.ON_LAUNCH).copy(name = "LaunchMod")

        val service = FakeOverlayService()
        val session = service.startSession(
            listOf(autoMod, launchMod),
            Files.createTempDirectory("autosave-list"),
            "com.gram.mergedragons"
        )
        try {
            val result = session.autosaveMods()
            assertEquals(listOf(autoMod), result)
        } finally {
            session.stop()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  isGameRunning / waitForGameExit
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isGameRunning returns false after stop is called`() {
        // Game "appears" alive initially but session is stopped
        val service = FakeOverlayService(gameRunning = { true })
        val session = service.startSession(
            emptyList(),
            Files.createTempDirectory("alive-test"),
            "com.example.game"
        )
        session.stop()
        assertFalse(session.isGameRunning(), "Session reports game not running after stop()")
    }

    @Test
    fun `waitForGameExit returns immediately when game is not running`() {
        val service = FakeOverlayService(gameRunning = { false })
        val session = service.startSession(
            emptyList(),
            Files.createTempDirectory("exit-test"),
            "com.example.game"
        )
        try {
            // Should return immediately – game is already not running
            session.waitForGameExit()   // must not block
        } finally {
            session.stop()
        }
    }

    @Test
    fun `stop is idempotent`() {
        val service = FakeOverlayService()
        val session = service.startSession(
            emptyList(),
            Files.createTempDirectory("stop-test"),
            "com.example.game"
        )
        session.stop()
        session.stop()  // second call must not throw
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Autosave polling integration
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ON_AUTOSAVE mod is applied by background scheduler`() {
        val appDir = makeWorkspace("save.dat" to "coins=0")

        val autoMod = ModDefinition(
            name        = "AutoCoins",
            gameId      = "com.gram.mergedragons",
            triggerMode = TriggerMode.ON_AUTOSAVE,
            patches     = listOf(ModPatch("coins", CheatOperation.ADD, 100L))
        )

        // Game "runs" long enough for at least one autosave tick (interval = 100 ms)
        val service = FakeOverlayService(gameRunning = { false })
        val session = service.startSession(
            listOf(autoMod),
            appDir,
            "com.gram.mergedragons"
        )

        // Give the scheduler time to fire at least once
        Thread.sleep(300L)
        session.stop()

        val fields = CheatApplier().readFields(appDir.resolve("save.dat"))
        val coins  = fields["coins"]?.toLong() ?: 0L
        assertTrue(coins > 0L, "Autosave polling should have applied the ADD patch at least once, got coins=$coins")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  isGameRunning shell-level detection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isGameRunning returns true when pidof exits 0 with non-blank stdout`() {
        val fakeShell = FakeShellExecutor(exitCode = 0) // stdout = "fake-pid-123" by default
        val service   = ModOverlayService(shell = fakeShell)

        assertTrue(service.isGameRunning("com.any.game"))
        assertTrue(fakeShell.commands.any { it.contains("pidof") })
    }

    @Test
    fun `isGameRunning falls back to ps grep when pidof fails`() {
        // First call (pidof) returns exitCode=1; second call (ps) returns 0 with package name
        val calls = mutableListOf<String>()
        val fakeShell = object : ShellExecutor() {
            override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
                calls += command
                return if (command.startsWith("pidof")) {
                    ShellResult(exitCode = 1, stdout = "", stderr = "")
                } else {
                    // ps | grep <pkg>
                    ShellResult(exitCode = 0, stdout = "com.gram.mergedragons", stderr = "")
                }
            }
        }
        val service = ModOverlayService(shell = fakeShell)
        assertTrue(service.isGameRunning("com.gram.mergedragons"))
        assertTrue(calls.any { it.startsWith("ps") })
    }

    @Test
    fun `isGameRunning returns false when both pidof and ps fail`() {
        val fakeShell = FakeShellExecutor(exitCode = 1)
        val service   = ModOverlayService(shell = fakeShell)

        // stdout from FakeShellExecutor is "fake-pid-123" even on exitCode=1
        // override to return blank stdout to simulate truly no process found
        val strictFake = object : ShellExecutor() {
            override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult =
                ShellResult(exitCode = 1, stdout = "", stderr = "")
        }
        val svc = ModOverlayService(shell = strictFake)
        assertFalse(svc.isGameRunning("com.no.such.game"))
    }
}
