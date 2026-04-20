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

    @Test
    fun `exports and imports save files`() {
        val root = Files.createTempDirectory("workspace-test")
        val source = Files.createTempFile("save", ".dat")
        Files.writeString(source, "coins=999")

        val exported = service.exportSave(root, "MergeDragons", source)
        assertTrue(exported.exists())

        val target = root.resolve("game-data").resolve("save-imported.dat")
        service.importSave(root, "MergeDragons", exported.fileName.toString(), target)
        assertEquals("coins=999", target.readText())
    }

    @Test
    fun `lists extension bundles`() {
        val root = Files.createTempDirectory("ext-test")
        Files.writeString(root.resolve("MergeDragons.extension"), "bundle")
        Files.writeString(root.resolve("ignore.txt"), "bundle")

        val extensions = service.listExtensions(root)

        assertEquals(1, extensions.size)
        assertEquals("MergeDragons.extension", extensions.first().fileName.toString())
    }

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
