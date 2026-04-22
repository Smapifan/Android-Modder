package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ContainerService].
 *
 * All tests use a [FakeShell] that returns configurable responses so that no
 * real Android device or root shell is required.
 */
class ContainerServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Fake shell
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeShell(
        private val responses: Map<String, ShellResult> = emptyMap()
    ) : ShellExecutor() {
        val commands = mutableListOf<Pair<String, Boolean>>() // command, asRoot

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command to asRoot
            return responses.entries
                .firstOrNull { (prefix, _) -> command.startsWith(prefix) }
                ?.value
                ?: ShellResult(exitCode = 1, stdout = "", stderr = "no match")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  createContainer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `createContainer returns user-ID parsed from pm output`() {
        val fake = FakeShell(mapOf(
            "pm create-user" to ShellResult(0, "Success: created user id 11", "")
        ))
        val svc = ContainerService(fake)

        val id = svc.createContainer("MyContainer")

        assertEquals(11, id)
        assertTrue(fake.commands.any { it.first.startsWith("pm create-user") && it.second })
    }

    @Test
    fun `createContainer passes the given name to pm`() {
        val fake = FakeShell(mapOf(
            "pm create-user" to ShellResult(0, "Success: created user id 12", "")
        ))
        val svc = ContainerService(fake)

        svc.createContainer("TestName")

        assertTrue(fake.commands.any { it.first.contains("TestName") })
    }

    @Test
    fun `createContainer returns null when pm exits with error`() {
        val fake = FakeShell(mapOf(
            "pm create-user" to ShellResult(1, "", "Error: user limit reached")
        ))
        val svc = ContainerService(fake)

        assertNull(svc.createContainer("Fail"))
    }

    @Test
    fun `createContainer returns null when pm output has no user-ID`() {
        val fake = FakeShell(mapOf(
            "pm create-user" to ShellResult(0, "Some unexpected output", "")
        ))
        val svc = ContainerService(fake)

        assertNull(svc.createContainer("NoId"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  removeContainer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `removeContainer returns true on success`() {
        val fake = FakeShell(mapOf(
            "pm remove-user" to ShellResult(0, "Success: removed user 11", "")
        ))
        val svc = ContainerService(fake)

        assertTrue(svc.removeContainer(11))
        assertTrue(fake.commands.any { it.first.contains("11") && it.second })
    }

    @Test
    fun `removeContainer returns false on failure`() {
        val fake = FakeShell(mapOf(
            "pm remove-user" to ShellResult(1, "", "Error")
        ))
        val svc = ContainerService(fake)

        assertFalse(svc.removeContainer(99))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listContainers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listContainers parses user-IDs from pm list users output`() {
        val output = """
            Users:
            	UserInfo{0:Owner:13} running
            	UserInfo{11:MyContainer:0} running
        """.trimIndent()
        val fake = FakeShell(mapOf(
            "pm list users" to ShellResult(0, output, "")
        ))
        val svc = ContainerService(fake)

        val ids = svc.listContainers()

        assertEquals(listOf(0, 11), ids)
    }

    @Test
    fun `listContainers returns empty list on pm failure`() {
        val fake = FakeShell(mapOf(
            "pm list users" to ShellResult(1, "", "Error")
        ))
        val svc = ContainerService(fake)

        assertTrue(svc.listContainers().isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  installApk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `installApk returns true and uses correct user flag`() {
        val fake = FakeShell(mapOf(
            "pm install" to ShellResult(0, "Success", "")
        ))
        val svc = ContainerService(fake)

        assertTrue(svc.installApk("/sdcard/game.apk", 11))
        val cmd = fake.commands.first { it.first.startsWith("pm install") }
        assertTrue(cmd.first.contains("--user 11"))
        assertTrue(cmd.first.contains("/sdcard/game.apk"))
        assertTrue(cmd.second) // asRoot
    }

    @Test
    fun `installApk returns false when pm fails`() {
        val fake = FakeShell(mapOf(
            "pm install" to ShellResult(1, "", "INSTALL_FAILED_INVALID_APK")
        ))
        val svc = ContainerService(fake)

        assertFalse(svc.installApk("/bad.apk", 11))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  uninstallApk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `uninstallApk returns true and targets correct user`() {
        val fake = FakeShell(mapOf(
            "pm uninstall" to ShellResult(0, "Success", "")
        ))
        val svc = ContainerService(fake)

        assertTrue(svc.uninstallApk("com.example.game", 11))
        val cmd = fake.commands.first { it.first.startsWith("pm uninstall") }
        assertTrue(cmd.first.contains("--user 11"))
        assertTrue(cmd.first.contains("com.example.game"))
        assertTrue(cmd.second)
    }

    @Test
    fun `uninstallApk returns false on failure`() {
        val fake = FakeShell(mapOf(
            "pm uninstall" to ShellResult(1, "", "Failure")
        ))
        val svc = ContainerService(fake)

        assertFalse(svc.uninstallApk("com.example.game", 11))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listInstalledApps
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listInstalledApps parses package names from pm output`() {
        val output = """
            package:com.android.settings
            package:com.gram.mergedragons
            package:com.kiloo.subwaysurf
        """.trimIndent()
        val fake = FakeShell(mapOf(
            "pm list packages" to ShellResult(0, output, "")
        ))
        val svc = ContainerService(fake)

        val pkgs = svc.listInstalledApps(11)

        assertEquals(
            listOf("com.android.settings", "com.gram.mergedragons", "com.kiloo.subwaysurf"),
            pkgs
        )
        assertTrue(fake.commands.any { it.first.contains("--user 11") })
    }

    @Test
    fun `listInstalledApps returns empty list on failure`() {
        val fake = FakeShell(mapOf(
            "pm list packages" to ShellResult(1, "", "Error")
        ))
        val svc = ContainerService(fake)

        assertTrue(svc.listInstalledApps(11).isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Regex helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `USER_ID_REGEX matches standard pm output`() {
        val match = ContainerService.USER_ID_REGEX.find("Success: created user id 42")
        assertEquals("42", match?.groupValues?.get(1))
    }

    @Test
    fun `LIST_USER_ID_REGEX matches UserInfo block`() {
        val match = ContainerService.LIST_USER_ID_REGEX.find("\tUserInfo{11:MyContainer:0} running")
        assertEquals("11", match?.groupValues?.get(1))
    }
}
