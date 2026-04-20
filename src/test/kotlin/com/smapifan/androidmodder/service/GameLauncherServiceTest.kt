package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.CheatOperation
import com.smapifan.androidmodder.model.GameLaunchConfig
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameLauncherServiceTest {

    /**
     * A [ShellExecutor] that records every command and returns a configurable result
     * instead of actually executing anything. Used to test [GameLauncherService]
     * without a real shell / Android device.
     */
    private class FakeShellExecutor(private val exitCode: Int = 0) : ShellExecutor() {
        val commands = mutableListOf<Pair<String, Boolean>>() // command, asRoot

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command to asRoot
            return ShellResult(exitCode = exitCode, stdout = "", stderr = "")
        }
    }

    private fun makeService(
        cheats: List<CheatDefinition> = emptyList(),
        shell: FakeShellExecutor = FakeShellExecutor()
    ) = GameLauncherService(
        cheats           = cheats,
        workspaceService = ModWorkspaceService(),
        cheatApplier     = CheatApplier(),
        modLoader        = ModLoader(),
        shell            = shell
    )

    private fun baseConfig(pkg: String = "com.gram.mergedragons") = GameLaunchConfig(
        packageName     = pkg,
        launchCommand   = "am start -n $pkg/.MainActivity",
        useRootForData  = false,
        importAfterExit = false
    )

    // ── basic launch ────────────────────────────────────────────────────────

    @Test
    fun `launch invokes am-start command`() {
        val fake    = FakeShellExecutor()
        val ws      = Files.createTempDirectory("launcher-test")
        val service = makeService(shell = fake)

        val result = service.launch(ws, baseConfig())

        assertTrue(fake.commands.any { it.first.contains("am start") })
        assertTrue(result.success)
    }

    // ── auto cheats ─────────────────────────────────────────────────────────

    @Test
    fun `launch auto-applies matching cheats before game starts`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-auto-cheat")

        // Set up exported save in workspace
        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=12500\nGemCount=20")

        val cheats = listOf(
            CheatDefinition("com.gram.mergedragons", "CoinCount", CheatOperation.ADD, 1000L),
            CheatDefinition("com.gram.mergedragons", "GemCount",  CheatOperation.SET, 999L)
        )
        val service = makeService(cheats = cheats, shell = fake)

        service.launch(ws, baseConfig())

        val saveFile = saveDir.resolve("save.dat")
        val applier  = CheatApplier()
        val fields   = applier.readFields(saveFile)
        assertEquals("13500", fields["CoinCount"], "CoinCount should be 12500 + 1000")
        assertEquals("999",   fields["GemCount"],  "GemCount should be SET to 999")
    }

    @Test
    fun `launch only applies cheats matching the launched package`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-pkg-filter")

        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=100")

        val cheats = listOf(
            CheatDefinition("com.gram.mergedragons",  "CoinCount", CheatOperation.ADD,  500L),
            CheatDefinition("net.stardewvalley",       "money",     CheatOperation.ADD,  9999L)  // different game
        )
        val service = makeService(cheats = cheats, shell = fake)

        service.launch(ws, baseConfig("com.gram.mergedragons"))

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("600", fields["CoinCount"], "Only merge-dragons cheat should be applied")
        assertEquals(null,  fields["money"],     "Stardew Valley cheat must not appear")
    }

    @Test
    fun `launch with no matching cheats does not throw`() {
        val fake    = FakeShellExecutor()
        val ws      = Files.createTempDirectory("launcher-no-cheat")
        val service = makeService(cheats = emptyList(), shell = fake)

        service.launch(ws, baseConfig())   // should complete without error
    }

    // ── auto mods ───────────────────────────────────────────────────────────

    @Test
    fun `launch auto-applies matching mod files from workspace`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-auto-mod")

        // Set up exported save
        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=5000\nGemCount=10")

        // Drop a .mod file in the workspace root
        Files.writeString(ws.resolve("InfiniteCoins.mod"), """
            {
              "name": "InfiniteCoins",
              "gameId": "com.gram.mergedragons",
              "patches": [
                { "field": "CoinCount", "operation": "ADD",  "amount": 10000 },
                { "field": "GemCount",  "operation": "SET",  "amount": 999   }
              ]
            }
        """.trimIndent())

        val service = makeService(shell = fake)
        service.launch(ws, baseConfig())

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("15000", fields["CoinCount"], "Mod should have added 10000 coins")
        assertEquals("999",   fields["GemCount"],  "Mod should have set gems to 999")
    }

    @Test
    fun `launch skips mod files for other games`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-mod-filter")

        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=100")

        // Mod for a different game
        Files.writeString(ws.resolve("StardewMod.mod"), """
            {"name":"StardewMod","gameId":"net.stardewvalley",
             "patches":[{"field":"money","operation":"ADD","amount":9999}]}
        """.trimIndent())

        val service = makeService(shell = fake)
        service.launch(ws, baseConfig("com.gram.mergedragons"))

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("100", fields["CoinCount"], "Save should be unchanged")
        assertEquals(null,  fields["money"],     "Stardew mod must not touch merge dragons save")
    }

    // ── cheats + mods combined ──────────────────────────────────────────────

    @Test
    fun `launch applies both cheats and mods automatically`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-both")

        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=1000\nGemCount=5\nChaliceCount=2")

        // Built-in cheat
        val cheats = listOf(
            CheatDefinition("com.gram.mergedragons", "CoinCount", CheatOperation.ADD, 500L)
        )

        // Community mod
        Files.writeString(ws.resolve("Extra.mod"), """
            {"name":"Extra","gameId":"com.gram.mergedragons",
             "patches":[
               {"field":"GemCount",    "operation":"SET","amount":999},
               {"field":"ChaliceCount","operation":"SET","amount":5  }
             ]}
        """.trimIndent())

        val service = makeService(cheats = cheats, shell = fake)
        service.launch(ws, baseConfig())

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("1500", fields["CoinCount"],    "Cheat: 1000 + 500")
        assertEquals("999",  fields["GemCount"],     "Mod: SET 999")
        assertEquals("5",    fields["ChaliceCount"], "Mod: SET 5")
    }

    // ── optional hooks still work ───────────────────────────────────────────

    @Test
    fun `extra preHooks run after auto-cheats`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-pre-hook")
        val log  = mutableListOf<String>()
        val service = makeService(shell = fake)

        service.launch(ws, baseConfig(), preHooks = listOf({ log += "pre1" }, { log += "pre2" }))

        assertEquals(listOf("pre1", "pre2"), log)
    }

    @Test
    fun `postHooks run when importAfterExit is true`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-post-hook")
        val log  = mutableListOf<String>()
        val service = makeService(shell = fake)

        service.launch(ws, baseConfig().copy(importAfterExit = true), postHooks = listOf { log += "post" })

        assertEquals(listOf("post"), log)
    }

    @Test
    fun `postHooks are NOT called when importAfterExit is false`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-no-post")
        val log  = mutableListOf<String>()
        val service = makeService(shell = fake)

        service.launch(ws, baseConfig().copy(importAfterExit = false), postHooks = listOf { log += "post" })

        assertTrue(log.isEmpty())
    }

    // ── root export / import ────────────────────────────────────────────────

    @Test
    fun `launch with root issues su cp commands for export`() {
        val fake    = FakeShellExecutor()
        val ws      = Files.createTempDirectory("launcher-root")
        val service = makeService(shell = fake)

        service.launch(ws, baseConfig().copy(useRootForData = true, importAfterExit = false))

        val rootCmds = fake.commands.filter { it.second }
        assertTrue(rootCmds.any { it.first.contains("cp -r") && it.first.contains("com.gram.mergedragons") })
    }

    @Test
    fun `launch with root issues su cp commands for import`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-root-import")

        ws.resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .createDirectories()

        val service = makeService(shell = fake)
        service.launch(ws, baseConfig().copy(useRootForData = true, importAfterExit = true))

        assertTrue(fake.commands.filter { it.second }.size >= 2)
    }
}
