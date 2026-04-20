package com.smapifan.androidmodder.service

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

    // --- launch without root (external storage only) ----------------------

    @Test
    fun `launch invokes am-start command`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-test")
        val service   = GameLauncherService(ModWorkspaceService(), fake)

        val config = GameLaunchConfig(
            packageName          = "com.gram.mergedragons",
            launchCommand        = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData       = false,
            importAfterExit      = false
        )

        val result = service.launch(workspace, config)

        assertTrue(fake.commands.any { it.first.contains("am start") })
        assertTrue(result.success)
    }

    @Test
    fun `pre-hooks are called before launch`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-pre-hook")
        val service   = GameLauncherService(ModWorkspaceService(), fake)
        val log       = mutableListOf<String>()

        val config = GameLaunchConfig(
            packageName    = "com.gram.mergedragons",
            launchCommand  = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData = false,
            importAfterExit = false
        )

        service.launch(workspace, config,
            preHooks = listOf(
                { log += "pre1" },
                { log += "pre2" }
            )
        )

        assertEquals(listOf("pre1", "pre2"), log)
    }

    @Test
    fun `post-hooks are called after launch when importAfterExit is true`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-post-hook")
        val service   = GameLauncherService(ModWorkspaceService(), fake)
        val log       = mutableListOf<String>()

        val config = GameLaunchConfig(
            packageName     = "com.gram.mergedragons",
            launchCommand   = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData  = false,
            importAfterExit = true
        )

        service.launch(workspace, config, postHooks = listOf { log += "post" })

        assertEquals(listOf("post"), log)
    }

    @Test
    fun `post-hooks are NOT called when importAfterExit is false`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-no-post")
        val service   = GameLauncherService(ModWorkspaceService(), fake)
        val log       = mutableListOf<String>()

        val config = GameLaunchConfig(
            packageName     = "com.gram.mergedragons",
            launchCommand   = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData  = false,
            importAfterExit = false
        )

        service.launch(workspace, config, postHooks = listOf { log += "post" })

        assertTrue(log.isEmpty())
    }

    // --- launch with root (internal storage) ------------------------------

    @Test
    fun `launch with root issues su cp commands for export`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-root")
        val service   = GameLauncherService(ModWorkspaceService(), fake)

        val config = GameLaunchConfig(
            packageName     = "com.gram.mergedragons",
            launchCommand   = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData  = true,
            importAfterExit = false
        )

        service.launch(workspace, config)

        val rootCommands = fake.commands.filter { it.second } // asRoot = true
        assertTrue(rootCommands.any { it.first.contains("cp -r") && it.first.contains("com.gram.mergedragons") },
            "Should have issued a root cp command for export")
    }

    @Test
    fun `launch with root issues su cp commands for import`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-root-import")

        // Create fake exported workspace data so importInternalWithRoot finds directories
        val primaryWs = workspace
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
        primaryWs.createDirectories()

        val service = GameLauncherService(ModWorkspaceService(), fake)

        val config = GameLaunchConfig(
            packageName     = "com.gram.mergedragons",
            launchCommand   = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData  = true,
            importAfterExit = true
        )

        service.launch(workspace, config)

        val rootCommands = fake.commands.filter { it.second }
        assertTrue(rootCommands.size >= 2, "Should have at least export + import root commands")
    }

    // --- pre-hook + cheat integration ------------------------------------

    @Test
    fun `pre-hook can apply a cheat to exported save data`() {
        val fake      = FakeShellExecutor()
        val workspace = Files.createTempDirectory("launcher-cheat")

        // Simulate already-exported save data in workspace
        val saveDir = workspace
            .resolve("com.gram.mergedragons")
            .resolve("internal").resolve("data").resolve("data").resolve("com.gram.mergedragons")
            .resolve("files")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "CoinCount=12500\nGemCount=20")

        val service = GameLauncherService(ModWorkspaceService(), fake)
        val applier = CheatApplier()
        val appDir  = workspace.resolve("com.gram.mergedragons")

        val config = GameLaunchConfig(
            packageName     = "com.gram.mergedragons",
            launchCommand   = "am start -n com.gram.mergedragons/.MainActivity",
            useRootForData  = false,
            importAfterExit = false
        )

        var coinCountAfterCheat = 0L
        service.launch(workspace, config,
            preHooks = listOf {
                // Cheat: add 1000 coins before game starts
                val cheat = com.smapifan.androidmodder.model.CheatDefinition(
                    appName   = "com.gram.mergedragons",
                    field     = "CoinCount",
                    operation = com.smapifan.androidmodder.model.CheatOperation.ADD,
                    amount    = 1000L
                )
                coinCountAfterCheat = applier.apply(appDir, cheat)
            }
        )

        assertEquals(13500L, coinCountAfterCheat)
    }
}
