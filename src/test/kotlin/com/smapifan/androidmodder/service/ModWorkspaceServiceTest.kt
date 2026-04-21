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

    // --- exportAppData / importAppData (internal, root required) ---------

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
            .resolve("internal").resolve("data").resolve("data").resolve("com.example.game")
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
            .resolve("internal").resolve("data").resolve("com.example.game")
            .resolve("prefs.dat")
        assertTrue(exported.exists(), "Secondary data dir should be exported")
        assertEquals("level=5", exported.readText())
    }

    @Test
    fun `exportAppData skips missing directories silently`() {
        val deviceData = Files.createTempDirectory("device-empty")
        val workspace  = Files.createTempDirectory("workspace-empty")

        service.exportAppData(workspace, deviceData, "com.example.game")

        val appDir = workspace.resolve("com.example.game")
        assertTrue(appDir.exists(), "App workspace dir should still be created")
    }

    @Test
    fun `importAppData restores primary directory from workspace to device`() {
        val workspace  = Files.createTempDirectory("ws-import")
        val deviceData = Files.createTempDirectory("device-import")

        val primaryWs = workspace
            .resolve("com.example.game")
            .resolve("internal").resolve("data").resolve("data").resolve("com.example.game")
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

        val saveDir = deviceData.resolve("data").resolve("com.game.test")
        saveDir.createDirectories()
        Files.writeString(saveDir.resolve("save.dat"), "gems=42\ncoins=100")

        service.exportAppData(workspace, deviceData, "com.game.test")
        service.importAppData(workspace, restoreData, "com.game.test")

        val restored = restoreData.resolve("data").resolve("com.game.test").resolve("save.dat")
        assertEquals("gems=42\ncoins=100", restored.readText())
    }

    // --- exportExternalData / importExternalData (no root required) -------

    @Test
    fun `exportExternalData copies sdcard Android data into workspace`() {
        val sdcard = Files.createTempDirectory("sdcard")
        val extDir = sdcard.resolve("Android").resolve("data").resolve("com.example.game")
        extDir.createDirectories()
        Files.writeString(extDir.resolve("world.dat"), "map=level1")

        val workspace = Files.createTempDirectory("ws-ext-export")
        service.exportExternalData(workspace, sdcard, "com.example.game")

        val exported = workspace
            .resolve("com.example.game")
            .resolve("external").resolve("com.example.game")
            .resolve("world.dat")
        assertTrue(exported.exists(), "External data should be exported")
        assertEquals("map=level1", exported.readText())
    }

    @Test
    fun `importExternalData restores sdcard data from workspace`() {
        val workspace = Files.createTempDirectory("ws-ext-import")
        val sdcard    = Files.createTempDirectory("sdcard-restore")

        val extWs = workspace
            .resolve("com.example.game")
            .resolve("external").resolve("com.example.game")
        extWs.createDirectories()
        Files.writeString(extWs.resolve("world.dat"), "map=modded")

        service.importExternalData(workspace, sdcard, "com.example.game")

        val restored = sdcard.resolve("Android").resolve("data")
            .resolve("com.example.game").resolve("world.dat")
        assertTrue(restored.exists(), "External data should be restored")
        assertEquals("map=modded", restored.readText())
    }

    @Test
    fun `exportExternalData and importExternalData round-trip preserves content`() {
        val sdcard      = Files.createTempDirectory("sdcard-rt")
        val workspace   = Files.createTempDirectory("ws-ext-rt")
        val sdcardRestore = Files.createTempDirectory("sdcard-rt-restore")

        val extDir = sdcard.resolve("Android").resolve("data").resolve("com.game.rt")
        extDir.createDirectories()
        Files.writeString(extDir.resolve("config.dat"), "volume=80\nfps=60")

        service.exportExternalData(workspace, sdcard, "com.game.rt")
        service.importExternalData(workspace, sdcardRestore, "com.game.rt")

        val restored = sdcardRestore.resolve("Android").resolve("data")
            .resolve("com.game.rt").resolve("config.dat")
        assertEquals("volume=80\nfps=60", restored.readText())
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

    // --- removeAllMods --------------------------------------------------------

    @Test
    fun `removeAllMods deletes all mod files and returns count`() {
        val root = Files.createTempDirectory("rm-mods-test")
        Files.writeString(root.resolve("Alpha.mod"), "mod_a")
        Files.writeString(root.resolve("Beta.mod"),  "mod_b")
        Files.writeString(root.resolve("keep.txt"),  "not a mod")

        // Should delete the two .mod files and ignore non-.mod files
        val removed = service.removeAllMods(root)

        assertEquals(2, removed)
        // .mod files must be gone
        assertTrue(!root.resolve("Alpha.mod").toFile().exists(), "Alpha.mod should be deleted")
        assertTrue(!root.resolve("Beta.mod").toFile().exists(),  "Beta.mod should be deleted")
        // Other files must be untouched
        assertTrue(root.resolve("keep.txt").toFile().exists(),   "keep.txt must not be deleted")
    }

    @Test
    fun `removeAllMods returns zero when no mod files are present`() {
        val root = Files.createTempDirectory("rm-mods-empty")
        Files.writeString(root.resolve("readme.txt"), "nothing here")

        val removed = service.removeAllMods(root)

        assertEquals(0, removed)
    }

    @Test
    fun `removeAllMods returns zero for non-existent directory`() {
        val nonExistent = Path.of("/tmp/does-not-exist-${System.nanoTime()}")

        val removed = service.removeAllMods(nonExistent)

        assertEquals(0, removed)
    }

    @Test
    fun `removeAllMods leaves workspace otherwise intact`() {
        val root = Files.createTempDirectory("rm-mods-intact")
        Files.writeString(root.resolve("Cheat.mod"),     "cheat")
        Files.writeString(root.resolve("Game.extension"), "ext")
        Files.writeString(root.resolve("notes.txt"),      "notes")

        service.removeAllMods(root)

        // Non-.mod files must still exist after the clean
        assertTrue(root.resolve("Game.extension").toFile().exists())
        assertTrue(root.resolve("notes.txt").toFile().exists())
    }

    // --- listModsForApp ---------------------------------------------------

    @Test
    fun `listModsForApp returns mods from game-specific subdirectory`() {
        val root = Files.createTempDirectory("listmods-app-test")
        val appDir = root.resolve("com.example.game")
        appDir.createDirectories()
        Files.writeString(appDir.resolve("AlphaMod.mod"), "mod_a")
        Files.writeString(appDir.resolve("BetaMod.mod"),  "mod_b")
        Files.writeString(appDir.resolve("readme.txt"),   "ignored")

        val mods = service.listModsForApp(root, "com.example.game")

        assertEquals(2, mods.size)
        assertEquals(listOf("AlphaMod.mod", "BetaMod.mod"), mods.map { it.fileName.toString() })
    }

    @Test
    fun `listModsForApp returns empty list when game directory does not exist`() {
        val root = Files.createTempDirectory("listmods-app-missing")
        assertEquals(emptyList(), service.listModsForApp(root, "com.nonexistent.game"))
    }

    @Test
    fun `listModsForApp does not return mods from other game directories`() {
        val root = Files.createTempDirectory("listmods-app-isolation")
        val appA = root.resolve("com.game.a").also { it.createDirectories() }
        val appB = root.resolve("com.game.b").also { it.createDirectories() }
        Files.writeString(appA.resolve("ModA.mod"), "mod_a")
        Files.writeString(appB.resolve("ModB.mod"), "mod_b")

        val modsA = service.listModsForApp(root, "com.game.a")
        val modsB = service.listModsForApp(root, "com.game.b")

        assertEquals(listOf("ModA.mod"), modsA.map { it.fileName.toString() })
        assertEquals(listOf("ModB.mod"), modsB.map { it.fileName.toString() })
    }

    // --- removeModsForApp -------------------------------------------------

    @Test
    fun `removeModsForApp deletes mods only from the specified game directory`() {
        val root = Files.createTempDirectory("removemods-app-test")
        val appDir = root.resolve("com.example.game")
        appDir.createDirectories()
        Files.writeString(appDir.resolve("Cheat.mod"), "mod")
        Files.writeString(appDir.resolve("notes.txt"), "keep")

        val removed = service.removeModsForApp(root, "com.example.game")

        assertEquals(1, removed)
        assertTrue(!appDir.resolve("Cheat.mod").toFile().exists(), "Cheat.mod should be deleted")
        assertTrue(appDir.resolve("notes.txt").toFile().exists(),   "notes.txt must not be deleted")
    }

    @Test
    fun `removeModsForApp does not touch other games`() {
        val root = Files.createTempDirectory("removemods-isolation")
        val appA = root.resolve("com.game.a").also { it.createDirectories() }
        val appB = root.resolve("com.game.b").also { it.createDirectories() }
        Files.writeString(appA.resolve("ModA.mod"), "mod_a")
        Files.writeString(appB.resolve("ModB.mod"), "mod_b")

        service.removeModsForApp(root, "com.game.a")

        assertTrue(!appA.resolve("ModA.mod").toFile().exists(), "com.game.a mod should be removed")
        assertTrue(appB.resolve("ModB.mod").toFile().exists(),  "com.game.b mod must not be touched")
    }

    @Test
    fun `removeModsForApp returns zero for non-existent game directory`() {
        val root = Files.createTempDirectory("removemods-missing")
        assertEquals(0, service.removeModsForApp(root, "com.nonexistent.game"))
    }

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
