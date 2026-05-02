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
 * Unit tests for [SystemAppsRegistry].
 */
class SystemAppsRegistryTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  bundled list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `bundled list is not empty`() {
        assertTrue(SystemAppsRegistry.bundled.isNotEmpty())
    }

    @Test
    fun `bundled list contains root browser`() {
        val entry = SystemAppsRegistry.bundled.firstOrNull {
            it.packageId == "com.smapifan.rootbrowser"
        }
        assertNotNull(entry)
        assertEquals(SystemAppCategory.FILE_BROWSER, entry.category)
        assertTrue(entry.autoInstall)
    }

    @Test
    fun `bundled list contains RAM editor`() {
        val entry = SystemAppsRegistry.bundled.firstOrNull {
            it.packageId == "com.smapifan.rameditor"
        }
        assertNotNull(entry)
        assertEquals(SystemAppCategory.RAM_TOOL, entry.category)
        assertTrue(entry.autoInstall)
    }

    @Test
    fun `bundled list contains NewPipe media app`() {
        val entry = SystemAppsRegistry.bundled.firstOrNull {
            it.packageId == "org.schabi.newpipe"
        }
        assertNotNull(entry)
        assertEquals(SystemAppCategory.MEDIA, entry.category)
    }

    @Test
    fun `bundled list contains MicroG GMS layer`() {
        val gms = SystemAppsRegistry.bundled.firstOrNull {
            it.packageId == "com.google.android.gms"
        }
        assertNotNull(gms)
        assertEquals(SystemAppCategory.GMS_LAYER, gms.category)
    }

    @Test
    fun `every bundled entry has a non-blank assetFileName`() {
        for (entry in SystemAppsRegistry.bundled) {
            assertTrue(
                entry.assetFileName.isNotBlank(),
                "Missing assetFileName for ${entry.packageId}"
            )
        }
    }

    @Test
    fun `bundled package IDs are unique`() {
        val ids = SystemAppsRegistry.bundled.map { it.packageId }
        assertEquals(ids.distinct(), ids, "Duplicate packageId found in bundled list")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  userSupplied / registerUserApk
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `registerUserApk adds entry to userSupplied`() {
        // Clean up any previous test run registrations
        val before = SystemAppsRegistry.userSupplied.size
        SystemAppsRegistry.registerUserApk("com.test.fake", "Fake App")
        assertEquals(before + 1, SystemAppsRegistry.userSupplied.size)
        // Clean up
        SystemAppsRegistry.registerUserApk("com.test.fake", "Fake App") // idempotent re-register
    }

    @Test
    fun `registerUserApk deduplicates by packageId`() {
        SystemAppsRegistry.registerUserApk("com.test.dedup", "Dedup v1")
        val afterFirst = SystemAppsRegistry.userSupplied.count { it.packageId == "com.test.dedup" }
        SystemAppsRegistry.registerUserApk("com.test.dedup", "Dedup v2")
        val afterSecond = SystemAppsRegistry.userSupplied.count { it.packageId == "com.test.dedup" }
        assertEquals(1, afterFirst)
        assertEquals(1, afterSecond)
        assertEquals("Dedup v2", SystemAppsRegistry.userSupplied.first { it.packageId == "com.test.dedup" }.label)
    }

    @Test
    fun `registerUserApk assigns USER_SUPPLIED category by default`() {
        SystemAppsRegistry.registerUserApk("com.test.cat", "Cat App")
        val entry = SystemAppsRegistry.userSupplied.first { it.packageId == "com.test.cat" }
        assertEquals(SystemAppCategory.USER_SUPPLIED, entry.category)
    }

    @Test
    fun `registerUserApk accepts custom category`() {
        SystemAppsRegistry.registerUserApk("com.test.media", "Media App", SystemAppCategory.MEDIA)
        val entry = SystemAppsRegistry.userSupplied.first { it.packageId == "com.test.media" }
        assertEquals(SystemAppCategory.MEDIA, entry.category)
    }

    @Test
    fun `userSupplied entries have empty assetFileName`() {
        SystemAppsRegistry.registerUserApk("com.test.noasset", "No Asset App")
        val entry = SystemAppsRegistry.userSupplied.first { it.packageId == "com.test.noasset" }
        assertEquals("", entry.assetFileName)
        assertFalse(entry.autoInstall)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  all()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `all() returns bundled plus userSupplied sorted by label`() {
        SystemAppsRegistry.registerUserApk("com.test.all", "All Test App")
        val all    = SystemAppsRegistry.all()
        val labels = all.map { it.label }
        assertEquals(labels.sorted(), labels)
        assertTrue(all.any { it.packageId == "com.smapifan.rootbrowser" })
        assertTrue(all.any { it.packageId == "com.test.all" })
    }
}
