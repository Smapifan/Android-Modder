package com.smapifan.androidmodder.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModWorkspaceServiceTest {
    private val service = ModWorkspaceService()

    // --- exportAppData / importAppData ------------------------------------

    @Test
    fun `exportAppData copies primary data-data directory into workspace`() {
        val deviceData = Files.createTempDirectory("device-data")
        val appPrimaryDir = deviceData.resolve("data").resolve("com.example.game")
        appPrimaryDir.createDirectories()
        Files.writeString(appPrimaryDir.resolve("files").also { it.createDirectories() }.resolve("savegame.dat"), "coins=500")

        val workspace = Files.createTempDirectory("workspace")
        service.exportAppData(workspace, deviceData, "com.example.game")

        val exported = workspace
            .resolve("com.example.game")
            .resolve("data").resolve("data").resolve("com.example.game")
            .resolve("files").resolve("savegame.dat")
        assertTrue(exported.exists(), "Primary data dir should be exported")
        assertEquals("coins=500", exported.readText())
    }

    @Test
    fun `exportAppData copies secondary data directory into workspace`() {
        val deviceData = Files.createTempDirectory("device-data-sec")
        val appSecondaryDir = deviceData.resolve("com.example.game")
        appSecondaryDir.createDirectories()
        Files.writeString(appSecondaryDir.resolve("prefs.dat"), "level=5")

        val workspace = Files.createTempDirectory("workspace-sec")
        service.exportAppData(workspace, deviceData, "com.example.game")

        val exported = workspace
            .resolve("com.example.game")
            .resolve("data").resolve("com.example.game")
            .resolve("prefs.dat")
        assertTrue(exported.exists(), "Secondary data dir should be exported")
        assertEquals("level=5", exported.readText())
    }

    @Test
    fun `exportAppData skips missing directories silently`() {
        val deviceData = Files.createTempDirectory("device-empty")
        val workspace  = Files.createTempDirectory("workspace-empty")

        // Neither /data/data/<app> nor /data/<app> exists – should not throw
        service.exportAppData(workspace, deviceData, "com.example.game")

        val appDir = workspace.resolve("com.example.game")
        assertTrue(appDir.exists(), "App workspace dir should still be created")
    }

    @Test
    fun `importAppData restores primary directory from workspace to device`() {
        val workspace  = Files.createTempDirectory("ws-import")
        val deviceData = Files.createTempDirectory("device-import")

        // Simulate an already-exported workspace
        val primaryWs = workspace
            .resolve("com.example.game")
            .resolve("data").resolve("data").resolve("com.example.game")
            .resolve("files")
        primaryWs.createDirectories()
        Files.writeString(primaryWs.resolve("savegame.dat"), "coins=1500")

        service.importAppData(workspace, deviceData, "com.example.game")

        val restored = deviceData
            .resolve("data").resolve("com.example.game")
            .resolve("files").resolve("savegame.dat")
        assertTrue(restored.exists(), "File should be restored to device data dir")
        assertEquals("coins=1500", restored.readText())
    }

    @Test
    fun `exportAppData and importAppData round-trip preserves content`() {
        val deviceData  = Files.createTempDirectory("device-roundtrip")
        val workspace   = Files.createTempDirectory("ws-roundtrip")
        val restoreData = Files.createTempDirectory("device-restore")

        // Set up device data
        val saveDir = deviceData.resolve("data").resolve("com.game.test")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "gems=42\ncoins=100")

        service.exportAppData(workspace, deviceData, "com.game.test")
        service.importAppData(workspace, restoreData, "com.game.test")

        val restored = restoreData.resolve("data").resolve("com.game.test").resolve("save.dat")
        assertEquals("gems=42\ncoins=100", restored.readText())
    }

    // --- listExtensions ---------------------------------------------------

    @Test
    fun `lists extension bundles`() {
        val root = Files.createTempDirectory("ext-test")
        Files.writeString(root.resolve("MergeDragons.extension"), "bundle")
        Files.writeString(root.resolve("ignore.txt"), "bundle")

        val extensions = service.listExtensions(root)

        assertEquals(1, extensions.size)
        assertEquals("MergeDragons.extension", extensions.first().fileName.toString())
    }

    // --- listMods ---------------------------------------------------------

    @Test
    fun `lists user-provided mod files`() {
        val root = Files.createTempDirectory("mod-test")
        Files.writeString(root.resolve("ExtraCheats.mod"), "cheat_data")
        Files.writeString(root.resolve("CoolMod.mod"), "mod_data")
        Files.writeString(root.resolve("readme.txt"), "ignored")

        val mods = service.listMods(root)

        assertEquals(2, mods.size)
        assertEquals(listOf("CoolMod.mod", "ExtraCheats.mod"), mods.map { it.fileName.toString() })
    }

    @Test
    fun `listMods returns empty list when directory does not exist`() {
        val nonExistent = Path.of("/tmp/does-not-exist-${System.nanoTime()}")
        assertEquals(emptyList(), service.listMods(nonExistent))
    }

    // --- unpackApk --------------------------------------------------------

    @Test
    fun `unpacks apk zip safely`() {
        val root = Files.createTempDirectory("apk-test")
        val apk = root.resolve("example.apk")
        createZip(apk, "assets/test.txt", "hello")

        val output = service.unpackApk(apk, root)
        assertTrue(output.resolve("assets/test.txt").exists())
        assertEquals("hello", output.resolve("assets/test.txt").readText())
    }

    private fun createZip(zipPath: Path, entryName: String, content: String) {
        zipPath.parent?.createDirectories()
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}
