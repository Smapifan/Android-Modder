package com.smapifan.androidmodder.service

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AppInstallManagerService].
 *
 * Uses a [TemporaryFolder] as [AppInstallManagerService.filesRoot] and a
 * minimal fake APK stream so no real Android context is needed for the
 * filesystem operations.
 */
class AppInstallManagerServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates a service backed by a temp directory (no Android Context needed). */
    private fun service(): AppInstallManagerService {
        // Constructor that accepts only a filesRoot string (no Context).
        // We use the reflection-free secondary constructor added for testing.
        return AppInstallManagerService(filesRoot = tmp.root.absolutePath)
    }

    private fun fakeApkBytes(): ByteArray =
        "PK\u0003\u0004fake-apk-content".toByteArray()

    private fun installFake(svc: AppInstallManagerService, packageId: String): Boolean {
        val stream = fakeApkBytes().inputStream()
        return svc.installApk(packageId, stream)
    }

    // ── installApk ────────────────────────────────────────────────────────────

    @Test
    fun `installApk creates APK file`() {
        val svc = service()
        assertTrue(installFake(svc, "com.example.game"))
        assertTrue(File(svc.apkPath("com.example.game")).exists())
    }

    @Test
    fun `installApk creates standard data subdirectories`() {
        val svc = service()
        assertTrue(installFake(svc, "com.example.game"))
        val root = File(svc.dataDataRoot("com.example.game"))
        assertTrue(root.isDirectory)
        for (sub in AppInstallManagerService.STANDARD_SUBDIRS) {
            assertTrue(File(root, sub).isDirectory, "Missing subdir: $sub")
        }
    }

    @Test
    fun `installApk creates mods directory`() {
        val svc = service()
        assertTrue(installFake(svc, "com.example.game"))
        assertTrue(File(svc.modsRoot("com.example.game")).isDirectory)
    }

    // ── listInstalledPackages ─────────────────────────────────────────────────

    @Test
    fun `listInstalledPackages returns empty list when nothing installed`() {
        val svc = service()
        assertTrue(svc.listInstalledPackages().isEmpty())
    }

    @Test
    fun `listInstalledPackages returns installed package`() {
        val svc = service()
        installFake(svc, "com.example.game")
        val pkgs = svc.listInstalledPackages()
        assertEquals(listOf("com.example.game"), pkgs)
    }

    @Test
    fun `listInstalledPackages returns multiple packages sorted`() {
        val svc = service()
        installFake(svc, "com.z.app")
        installFake(svc, "com.a.app")
        installFake(svc, "net.example.game")
        assertEquals(listOf("com.a.app", "com.z.app", "net.example.game"), svc.listInstalledPackages())
    }

    // ── isInstalled ───────────────────────────────────────────────────────────

    @Test
    fun `isInstalled returns false before installation`() {
        assertFalse(service().isInstalled("com.example.game"))
    }

    @Test
    fun `isInstalled returns true after installation`() {
        val svc = service()
        installFake(svc, "com.example.game")
        assertTrue(svc.isInstalled("com.example.game"))
    }

    // ── uninstall ─────────────────────────────────────────────────────────────

    @Test
    fun `uninstall removes package directory and APK`() {
        val svc = service()
        installFake(svc, "com.example.game")
        assertTrue(svc.uninstall("com.example.game"))
        assertFalse(svc.isInstalled("com.example.game"))
        assertFalse(File(svc.apkPath("com.example.game")).exists())
    }

    @Test
    fun `uninstall returns true for non-existent package`() {
        // deleteRecursively on a missing dir returns true
        assertTrue(service().uninstall("com.missing.pkg"))
    }

    // ── mod layers ────────────────────────────────────────────────────────────

    @Test
    fun `createModLayer creates directory`() {
        val svc = service()
        installFake(svc, "com.example.game")
        assertTrue(svc.createModLayer("com.example.game", "InfiniteCoins"))
        assertTrue(File(svc.modsRoot("com.example.game"), "InfiniteCoins").isDirectory)
    }

    @Test
    fun `listModLayers returns empty list when no mods`() {
        val svc = service()
        installFake(svc, "com.example.game")
        assertTrue(svc.listModLayers("com.example.game").isEmpty())
    }

    @Test
    fun `listModLayers returns mod names sorted`() {
        val svc = service()
        installFake(svc, "com.example.game")
        svc.createModLayer("com.example.game", "ZMod")
        svc.createModLayer("com.example.game", "AMod")
        assertEquals(listOf("AMod", "ZMod"), svc.listModLayers("com.example.game"))
    }

    @Test
    fun `deleteModLayer removes directory`() {
        val svc = service()
        installFake(svc, "com.example.game")
        svc.createModLayer("com.example.game", "ToDelete")
        assertTrue(svc.deleteModLayer("com.example.game", "ToDelete"))
        assertFalse(File(svc.modsRoot("com.example.game"), "ToDelete").exists())
    }

    // ── path helpers ──────────────────────────────────────────────────────────

    @Test
    fun `apkPath returns expected path`() {
        val svc = service()
        val expected = "${tmp.root.absolutePath}/${AppInstallManagerService.APPS_DIR}/com.example.game.apk"
        assertEquals(expected, svc.apkPath("com.example.game"))
    }

    @Test
    fun `dataDataRoot returns expected path`() {
        val svc = service()
        val expected = "${tmp.root.absolutePath}/com.example.game"
        assertEquals(expected, svc.dataDataRoot("com.example.game"))
    }

    @Test
    fun `modsRoot returns expected path`() {
        val svc = service()
        val expected = "${tmp.root.absolutePath}/com.example.game/mods"
        assertEquals(expected, svc.modsRoot("com.example.game"))
    }
}
