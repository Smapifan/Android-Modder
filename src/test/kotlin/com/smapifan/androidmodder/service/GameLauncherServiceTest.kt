package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.ApkInjectionConfig
import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.CheatOperation
import com.smapifan.androidmodder.model.DataAccessStrategy
import com.smapifan.androidmodder.model.GameLaunchConfig
import com.smapifan.androidmodder.model.ModDefinition
import com.smapifan.androidmodder.model.ModPatch
import com.smapifan.androidmodder.model.OverlayAction
import com.smapifan.androidmodder.model.TriggerMode
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ── trigger mode: ON_LAUNCH vs ON_DEMAND / ON_AUTOSAVE ──────────────────

    @Test
    fun `launch only auto-applies ON_LAUNCH mods during pre-launch`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-trigger-filter")

        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "coins=100\ngems=10")

        // ON_LAUNCH mod – should be applied at pre-launch
        Files.writeString(ws.resolve("LaunchMod.mod"), """
            {
              "name": "LaunchMod",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_LAUNCH",
              "patches": [{"field":"coins","operation":"ADD","amount":500}]
            }
        """.trimIndent())

        // ON_DEMAND mod – must NOT be applied at pre-launch
        Files.writeString(ws.resolve("DemandMod.mod"), """
            {
              "name": "DemandMod",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_DEMAND",
              "patches": [{"field":"gems","operation":"SET","amount":9999}],
              "overlayActions": [{"label":"Max Gems","patchFields":["gems"]}]
            }
        """.trimIndent())

        val service = makeService(shell = fake)
        service.launch(ws, baseConfig())

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("600",  fields["coins"], "ON_LAUNCH mod should have added 500 coins")
        assertEquals("10",   fields["gems"],  "ON_DEMAND mod must NOT fire at pre-launch")
    }

    @Test
    fun `launch with overlayService starts session and waits for game exit`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-overlay-session")

        // A fake overlay service where the game "exits" immediately
        val overlayService = object : ModOverlayService(
            shell                  = fake,
            autosaveIntervalMs     = 100L,
            processCheckIntervalMs = 50L
        ) {
            override fun isGameRunning(packageName: String): Boolean = false
        }

        val service = GameLauncherService(
            workspaceService = ModWorkspaceService(),
            cheatApplier     = CheatApplier(),
            modLoader        = ModLoader(),
            shell            = fake,
            overlayService   = overlayService
        )

        // Should complete without hanging
        val result = service.launch(ws, baseConfig())
        assertTrue(result.success)
    }

    @Test
    fun `launch with overlayService does not apply ON_DEMAND mod at pre-launch`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-overlay-no-prelaunch")

        val saveDir = ws
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "gems=0")

        Files.writeString(ws.resolve("DemandGems.mod"), """
            {
              "name": "DemandGems",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_DEMAND",
              "patches": [{"field":"gems","operation":"SET","amount":9999}],
              "overlayActions": [{"label":"Max Gems","patchFields":["gems"]}]
            }
        """.trimIndent())

        val overlayService = object : ModOverlayService(
            shell                  = fake,
            autosaveIntervalMs     = 100L,
            processCheckIntervalMs = 50L
        ) {
            override fun isGameRunning(packageName: String): Boolean = false
        }

        val service = GameLauncherService(
            workspaceService = ModWorkspaceService(),
            cheatApplier     = CheatApplier(),
            modLoader        = ModLoader(),
            shell            = fake,
            overlayService   = overlayService
        )

        service.launch(ws, baseConfig())

        val fields = CheatApplier().readFields(saveDir.resolve("save.dat"))
        assertEquals("0", fields["gems"], "ON_DEMAND mod must not fire at pre-launch even with overlayService")
    }

    // ── DataAccessStrategy dispatch ─────────────────────────────────────────

    @Test
    fun `EXTERNAL_STORAGE strategy issues only external-storage shell commands`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("strategy-external")

        val service = makeService(shell = fake)
        service.launch(
            ws,
            baseConfig().copy(dataAccessStrategy = DataAccessStrategy.EXTERNAL_STORAGE)
        )

        // Should NOT issue any 'su' root commands for data copy
        assertFalse(
            fake.commands.any { it.second },
            "EXTERNAL_STORAGE must not issue any root (su) commands"
        )
    }

    @Test
    fun `ROOT strategy issues su cp commands for export and import`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("strategy-root")

        val service = makeService(shell = fake)
        service.launch(
            ws,
            baseConfig().copy(
                dataAccessStrategy = DataAccessStrategy.ROOT,
                importAfterExit    = true
            )
        )

        // ROOT strategy must issue root (asRoot=true) cp commands
        val rootCmds = fake.commands.filter { it.second }
        assertTrue(rootCmds.isNotEmpty(), "ROOT strategy must issue asRoot=true cp commands")
        assertTrue(rootCmds.all { (cmd, _) -> cmd.startsWith("cp ") })
    }

    @Test
    fun `useRootForData=true maps to ROOT strategy (legacy compat)`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("strategy-legacy-root")

        val service = makeService(shell = fake)
        service.launch(
            ws,
            // Old-style: useRootForData without explicit dataAccessStrategy
            baseConfig().copy(useRootForData = true)
        )

        val rootCmds = fake.commands.filter { it.second }
        assertTrue(rootCmds.isNotEmpty(), "useRootForData=true must behave like ROOT strategy")
    }

    @Test
    fun `RUN_AS strategy issues run-as commands (no su) for export`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("strategy-runas")

        val service = makeService(shell = fake)
        service.launch(
            ws,
            baseConfig().copy(dataAccessStrategy = DataAccessStrategy.RUN_AS)
        )

        // run-as command must appear
        assertTrue(
            fake.commands.any { (cmd, _) -> cmd.startsWith("run-as") },
            "RUN_AS strategy must issue run-as commands"
        )
        // No root commands for data export/import
        assertFalse(
            fake.commands.filter { (cmd, _) -> cmd.startsWith("cp ") }.any { it.second },
            "RUN_AS strategy must not use su for cp"
        )
    }

    @Test
    fun `effectiveStrategy resolves useRootForData legacy flag correctly`() {
        val cfgDefault = baseConfig()
        assertEquals(DataAccessStrategy.EXTERNAL_STORAGE, cfgDefault.effectiveStrategy)

        val cfgLegacyRoot = baseConfig().copy(useRootForData = true)
        assertEquals(DataAccessStrategy.ROOT, cfgLegacyRoot.effectiveStrategy)

        val cfgExplicitRunAs = baseConfig().copy(
            dataAccessStrategy = DataAccessStrategy.RUN_AS,
            useRootForData     = true   // explicit strategy wins
        )
        assertEquals(DataAccessStrategy.RUN_AS, cfgExplicitRunAs.effectiveStrategy)
    }

    // ── APK-injection activation token ───────────────────────────────────────

    @Test
    fun `launch writes activation token when activationService provided and ON_LAUNCH mods exist`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-token-write")

        // Drop an ON_LAUNCH mod
        Files.writeString(ws.resolve("CoinMod.mod"), """
            {
              "name": "CoinMod",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_LAUNCH",
              "patches": [{"field":"coins","operation":"ADD","amount":1000}]
            }
        """.trimIndent())

        // Capture token-write commands via the same fake shell
        val activationService = LaunchActivationService(fake, externalStorageRoot = "/sdcard")
        val service = GameLauncherService(
            workspaceService  = ModWorkspaceService(),
            cheatApplier      = CheatApplier(),
            modLoader         = ModLoader(),
            shell             = fake,
            activationService = activationService
        )

        service.launch(ws, baseConfig())

        // The activation service writes via the shell; .launcher_session must appear
        assertTrue(
            fake.commands.any { (cmd, _) ->
                cmd.contains(LaunchActivationService.TOKEN_FILENAME)
            },
            "Activation token must be written when ON_LAUNCH mods are present; commands: ${fake.commands}"
        )
    }

    @Test
    fun `launch does NOT write activation token when no ON_LAUNCH mods exist`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-token-no-write")

        // Only an ON_DEMAND mod – should NOT trigger token write
        Files.writeString(ws.resolve("DemandMod.mod"), """
            {
              "name": "DemandMod",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_DEMAND",
              "patches": [{"field":"gems","operation":"SET","amount":9999}],
              "overlayActions": [{"label":"Max","patchFields":["gems"]}]
            }
        """.trimIndent())

        val activationService = LaunchActivationService(fake, externalStorageRoot = "/sdcard")
        val service = GameLauncherService(
            workspaceService  = ModWorkspaceService(),
            cheatApplier      = CheatApplier(),
            modLoader         = ModLoader(),
            shell             = fake,
            activationService = activationService
        )

        service.launch(ws, baseConfig())

        assertFalse(
            fake.commands.any { (cmd, _) ->
                cmd.contains("touch") && cmd.contains(LaunchActivationService.TOKEN_FILENAME)
            },
            "Token must NOT be written when no ON_LAUNCH mods exist"
        )
    }

    @Test
    fun `launch clears token after game exits`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-token-clear")

        Files.writeString(ws.resolve("CoinMod.mod"), """
            {
              "name": "CoinMod",
              "gameId": "com.gram.mergedragons",
              "triggerMode": "ON_LAUNCH",
              "patches": [{"field":"coins","operation":"ADD","amount":500}]
            }
        """.trimIndent())

        val activationService = LaunchActivationService(fake, externalStorageRoot = "/sdcard")
        val service = GameLauncherService(
            workspaceService  = ModWorkspaceService(),
            cheatApplier      = CheatApplier(),
            modLoader         = ModLoader(),
            shell             = fake,
            activationService = activationService
        )

        service.launch(ws, baseConfig())

        val cmds = fake.commands.map { it.first }
        val amStartIdx = cmds.indexOfFirst { it.contains("am start") }
        val clearIdx   = cmds.indexOfLast {
            it.contains("rm -f") && it.contains(LaunchActivationService.TOKEN_FILENAME)
        }
        assertTrue(clearIdx >= 0, "Token must be cleared after game exit; commands: $cmds")
        assertTrue(amStartIdx < clearIdx, "Token clear must come AFTER am start")
    }

    @Test
    fun `launch does not write token when no activationService is configured`() {
        val fake = FakeShellExecutor()
        val ws   = Files.createTempDirectory("launcher-no-activation-svc")

        Files.writeString(ws.resolve("Mod.mod"), """
            {"name":"M","gameId":"com.gram.mergedragons",
             "patches":[{"field":"coins","operation":"ADD","amount":1}]}
        """.trimIndent())

        val service = makeService(shell = fake) // no activationService

        service.launch(ws, baseConfig())

        assertFalse(
            fake.commands.any { (cmd, _) -> cmd.contains(LaunchActivationService.TOKEN_FILENAME) },
            "No token ops should occur without an activationService"
        )
    }

    // ── restoreOriginalApk ────────────────────────────────────────────────────

    @Test
    fun `restoreOriginalApk returns false when apkInjection not configured`() {
        val service = makeService()
        val config  = ApkInjectionConfig(
            packageName      = "com.gram.mergedragons",
            originalApkPath  = "/sdcard/original.apk",
            keystorePath     = "/sdcard/my.jks",
            keystorePassword = "pass",
            keyAlias         = "key"
        )
        assertFalse(service.restoreOriginalApk(config))
    }

    @Test
    fun `restoreOriginalApk delegates to apkInjection service`() {
        val fake       = FakeShellExecutor()
        val saveBackup = SaveBackupService(fake, externalStorageRoot = "/sdcard")
        val apkSvc     = ApkInjectionService(fake, saveBackup)
        val service    = GameLauncherService(
            workspaceService = ModWorkspaceService(),
            cheatApplier     = CheatApplier(),
            modLoader        = ModLoader(),
            shell            = fake,
            apkInjection     = apkSvc
        )
        val config = ApkInjectionConfig(
            packageName      = "com.gram.mergedragons",
            originalApkPath  = "/sdcard/original.apk",
            keystorePath     = "/sdcard/my.jks",
            keystorePassword = "pass",
            keyAlias         = "key"
        )

        service.restoreOriginalApk(config)

        // Backup (run-as cp) + restore script + pm uninstall + pm install must all fire
        val cmds = fake.commands.map { it.first }
        assertTrue(cmds.any { it.contains("run-as") && it.contains("cp -r") }, "Expected backup command; got: $cmds")
        assertTrue(cmds.any { it.contains("restore_saves.sh") }, "Expected restore script write; got: $cmds")
        assertTrue(cmds.any { it.contains("pm uninstall") }, "Expected pm uninstall; got: $cmds")
        assertTrue(cmds.any { it.contains("pm install") && !it.contains("uninstall") }, "Expected pm install; got: $cmds")
    }
}