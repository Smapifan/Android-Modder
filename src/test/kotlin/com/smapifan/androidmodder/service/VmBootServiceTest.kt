package com.smapifan.androidmodder.service

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [VmBootService].
 *
 * Uses a [TemporaryFolder] as the virtual sandbox root and a map-backed
 * [assetOpener] stub so no Android context or real APK assets are needed.
 */
class VmBootServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun fakeApkBytes(): ByteArray =
        "PK\u0003\u0004fake-apk-content".toByteArray()

    /**
     * Builds an asset map where every bundled entry's assetFileName is mapped
     * to a fake APK stream, unless listed in [missing].
     */
    private fun assetMap(missing: Set<String> = emptySet()): Map<String, ByteArray> {
        return SystemAppsRegistry.bundled
            .filterNot { "${VmBootService.SYSTEM_APPS_ASSET_DIR}/${it.assetFileName}" in missing }
            .associate { "${VmBootService.SYSTEM_APPS_ASSET_DIR}/${it.assetFileName}" to fakeApkBytes() }
    }

    private fun opener(map: Map<String, ByteArray>): (String) -> InputStream? =
        { name -> map[name]?.inputStream() }

    private fun service(
        missing: Set<String> = emptySet()
    ): VmBootService {
        val installer = AppInstallManagerService(filesRoot = tmp.root.absolutePath)
        return VmBootService(installer = installer, assetOpener = opener(assetMap(missing)))
    }

    private fun serviceWithInstaller(
        installer: AppInstallManagerService,
        assetMap: Map<String, ByteArray>
    ) = VmBootService(installer = installer, assetOpener = opener(assetMap))

    // ── boot – happy path ─────────────────────────────────────────────────────

    @Test
    fun `boot installs all autoInstall bundled apps when none are present`() {
        val svc    = service()
        val report = svc.boot()

        val expectedIds = SystemAppsRegistry.bundled
            .filter { it.autoInstall }
            .map { it.packageId }

        assertTrue(report.allSucceeded, "Expected no failures; got: ${report.failed}")
        assertEquals(expectedIds.sorted(), report.installed.sorted())
    }

    @Test
    fun `boot returns correct BootReport on fresh sandbox`() {
        val svc    = service()
        val report = svc.boot()

        assertTrue(report.anyInstalled)
        assertTrue(report.allSucceeded)
        assertTrue(report.warnings.isEmpty())
    }

    // ── boot – idempotency ────────────────────────────────────────────────────

    @Test
    fun `second boot call skips already-installed apps`() {
        val installer = AppInstallManagerService(filesRoot = tmp.root.absolutePath)
        val map       = assetMap()
        val svc       = serviceWithInstaller(installer, map)

        val first  = svc.boot()
        val second = svc.boot()

        assertTrue(first.anyInstalled, "First boot should install apps")
        assertTrue(second.installed.isEmpty(), "Second boot should install nothing")
        assertEquals(first.installed.sorted(), second.skipped.sorted())
        assertTrue(second.allSucceeded)
    }

    // ── boot – missing assets ─────────────────────────────────────────────────

    @Test
    fun `boot reports warning and failed entry when asset is missing`() {
        // Find the first autoInstall entry and drop its asset from the map.
        val missingEntry = SystemAppsRegistry.bundled.first { it.autoInstall }
        val missingAsset = "${VmBootService.SYSTEM_APPS_ASSET_DIR}/${missingEntry.assetFileName}"

        val svc    = service(missing = setOf(missingAsset))
        val report = svc.boot()

        assertTrue(missingEntry.packageId in report.failed,
            "Expected ${missingEntry.packageId} in failed list")
        assertTrue(report.warnings.any { missingEntry.packageId in it },
            "Expected warning mentioning ${missingEntry.packageId}")
        assertFalse(report.allSucceeded)
    }

    // ── installUserApk ────────────────────────────────────────────────────────

    @Test
    fun `installUserApk installs APK and registers in registry`() {
        val installer = AppInstallManagerService(filesRoot = tmp.root.absolutePath)
        val svc       = VmBootService(installer = installer, assetOpener = opener(emptyMap()))

        val ok = svc.installUserApk(
            packageId = "com.example.youtube",
            label     = "YouTube",
            apkStream = fakeApkBytes().inputStream()
        )

        assertTrue(ok)
        assertTrue(installer.isInstalled("com.example.youtube"))
        val registered = SystemAppsRegistry.userSupplied.firstOrNull { it.packageId == "com.example.youtube" }
        assertTrue(registered != null, "Should be registered in userSupplied")
        assertEquals("YouTube", registered.label)
        assertEquals(SystemAppCategory.USER_SUPPLIED, registered.category)
    }

    @Test
    fun `installUserApk with custom category stores correct category`() {
        val installer = AppInstallManagerService(filesRoot = tmp.root.absolutePath)
        val svc       = VmBootService(installer = installer, assetOpener = opener(emptyMap()))

        svc.installUserApk(
            packageId = "com.example.gapps",
            label     = "GApps",
            apkStream = fakeApkBytes().inputStream(),
            category  = SystemAppCategory.GMS_LAYER
        )

        val registered = SystemAppsRegistry.userSupplied.firstOrNull { it.packageId == "com.example.gapps" }
        assertEquals(SystemAppCategory.GMS_LAYER, registered?.category)
    }

    // ── BootReport helpers ────────────────────────────────────────────────────

    @Test
    fun `BootReport anyInstalled is false when installed list is empty`() {
        val report = BootReport(installed = emptyList(), skipped = listOf("a"), failed = emptyList(), warnings = emptyList())
        assertFalse(report.anyInstalled)
    }

    @Test
    fun `BootReport allSucceeded is false when failed list is non-empty`() {
        val report = BootReport(installed = emptyList(), skipped = emptyList(), failed = listOf("x"), warnings = emptyList())
        assertFalse(report.allSucceeded)
    }

    @Test
    fun `BootReport allSucceeded is true when failed list is empty`() {
        val report = BootReport(installed = listOf("a"), skipped = emptyList(), failed = emptyList(), warnings = emptyList())
        assertTrue(report.allSucceeded)
    }
}
