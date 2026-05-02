package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.DataAccessStrategy
import com.smapifan.androidmodder.model.GameLaunchConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SystemAppLauncherService].
 *
 * Uses a [FakeDelegate] that records the [GameLaunchConfig] passed to
 * [GameLauncherService.launch] so we can assert that [DataAccessStrategy.VIRTUAL_FS]
 * is always enforced without running a real shell.
 */
class SystemAppLauncherServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Test doubles
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subclass of [GameLauncherService] that captures every [GameLaunchConfig]
     * passed to [launch] and immediately returns a successful [ShellResult].
     */
    private class FakeDelegate : GameLauncherService() {
        val capturedConfigs = mutableListOf<GameLaunchConfig>()

        override fun launch(
            workspace: Path,
            config: GameLaunchConfig,
            preHooks: List<() -> Unit>,
            postHooks: List<() -> Unit>
        ): ShellResult {
            capturedConfigs += config
            return ShellResult(exitCode = 0, stdout = "", stderr = "")
        }
    }

    private val workspace: Path = Files.createTempDirectory("sysapp-launcher-test")

    // ─────────────────────────────────────────────────────────────────────────
    //  VIRTUAL_FS enforcement
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `launchSystemApp always uses VIRTUAL_FS strategy`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        launcher.launchSystemApp(
            workspace = workspace,
            packageId = "com.smapifan.rootbrowser",
            amCommand = "am start -n com.smapifan.rootbrowser/.MainActivity"
        )

        val cfg = fake.capturedConfigs.single()
        assertEquals(DataAccessStrategy.VIRTUAL_FS, cfg.dataAccessStrategy)
    }

    @Test
    fun `launchSystemApp ignores strategyOverride and keeps VIRTUAL_FS`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        // Even if a caller accidentally passes a different strategy, VIRTUAL_FS
        // must be used.
        launcher.launchSystemApp(
            workspace        = workspace,
            packageId        = "com.smapifan.rootbrowser",
            amCommand        = "am start -n com.smapifan.rootbrowser/.MainActivity",
            strategyOverride = DataAccessStrategy.ROOT   // must be ignored
        )

        val cfg = fake.capturedConfigs.single()
        assertEquals(DataAccessStrategy.VIRTUAL_FS, cfg.dataAccessStrategy)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Config forwarding
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `launchSystemApp forwards packageId and amCommand`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        launcher.launchSystemApp(
            workspace = workspace,
            packageId = "com.smapifan.rameditor",
            amCommand = "am start -n com.smapifan.rameditor/.MainActivity"
        )

        val cfg = fake.capturedConfigs.single()
        assertEquals("com.smapifan.rameditor", cfg.packageName)
        assertEquals("am start -n com.smapifan.rameditor/.MainActivity", cfg.launchCommand)
    }

    @Test
    fun `launchSystemApp respects importAfterExit flag`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        launcher.launchSystemApp(
            workspace       = workspace,
            packageId       = "com.smapifan.rootbrowser",
            amCommand       = "am start -n com.smapifan.rootbrowser/.MainActivity",
            importAfterExit = false
        )

        val cfg = fake.capturedConfigs.single()
        assertEquals(false, cfg.importAfterExit)
    }

    @Test
    fun `launchSystemApp returns ShellResult from delegate`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        val result = launcher.launchSystemApp(
            workspace = workspace,
            packageId = "com.smapifan.rootbrowser",
            amCommand = "am start -n com.smapifan.rootbrowser/.MainActivity"
        )

        assertTrue(result.success)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  findEntry
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `findEntry returns entry for known system app`() {
        val launcher = SystemAppLauncherService()
        val entry    = launcher.findEntry("com.smapifan.rootbrowser")
        assertNotNull(entry)
        assertEquals("com.smapifan.rootbrowser", entry.packageId)
    }

    @Test
    fun `findEntry returns null for unknown package`() {
        val launcher = SystemAppLauncherService()
        assertNull(launcher.findEntry("com.unknown.package"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  launchAll
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `launchAll skips entries when commandBuilder returns null`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        val results = launcher.launchAll(workspace) { null }

        assertTrue(results.isEmpty())
        assertTrue(fake.capturedConfigs.isEmpty())
    }

    @Test
    fun `launchAll launches all bundled entries when commandBuilder provides a command`() {
        val fake     = FakeDelegate()
        val launcher = SystemAppLauncherService(fake)

        val results = launcher.launchAll(workspace) { entry ->
            "am start -n ${entry.packageId}/.MainActivity"
        }

        assertEquals(SystemAppsRegistry.bundled.size, results.size)
        assertEquals(SystemAppsRegistry.bundled.size, fake.capturedConfigs.size)
        // Every launch must use VIRTUAL_FS
        assertTrue(fake.capturedConfigs.all { it.dataAccessStrategy == DataAccessStrategy.VIRTUAL_FS })
    }
}
