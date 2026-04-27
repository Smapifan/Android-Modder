package com.smapifan.androidmodder.service

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [VirtualFileSystemService].
 *
 * Uses a JUnit 5 [TempDir] so that no real `/data/data/` path is needed.
 */
class VirtualFileSystemServiceTest {

    @TempDir
    lateinit var tempDir: File

    private fun service() = VirtualFileSystemService(appFilesRoot = tempDir.absolutePath)

    // ─────────────────────────────────────────────────────────────────────────
    //  virtualDataRoot
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `virtualDataRoot returns correct path`() {
        val svc = service()
        assertEquals(
            "${tempDir.absolutePath}/com.example.game",
            svc.virtualDataRoot("com.example.game")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ensureVirtualDir
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ensureVirtualDir creates directory`() {
        val svc = service()
        val result = svc.ensureVirtualDir("com.gram.mergedragons")
        assertTrue(result)
        assertTrue(File(svc.virtualDataRoot("com.gram.mergedragons")).isDirectory)
    }

    @Test
    fun `ensureVirtualDir returns true when directory already exists`() {
        val svc = service()
        svc.ensureVirtualDir("com.example.game")
        assertTrue(svc.ensureVirtualDir("com.example.game"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listInstalledPackages
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listInstalledPackages returns empty list when no packages exist`() {
        assertEquals(emptyList(), service().listInstalledPackages())
    }

    @Test
    fun `listInstalledPackages returns sorted package names`() {
        val svc = service()
        svc.ensureVirtualDir("com.z.last")
        svc.ensureVirtualDir("com.a.first")
        svc.ensureVirtualDir("com.m.middle")

        val packages = svc.listInstalledPackages()
        assertEquals(listOf("com.a.first", "com.m.middle", "com.z.last"), packages)
    }

    @Test
    fun `listInstalledPackages includes legacy vdata packages`() {
        val svc = service()
        File(tempDir, "vdata/com.legacy.game/files").mkdirs()
        svc.ensureVirtualDir("com.new.game")
        assertEquals(listOf("com.legacy.game", "com.new.game"), svc.listInstalledPackages())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  writeFile / readFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `writeFile creates file and readFile returns content`() {
        val svc     = service()
        val pkg     = "com.gram.mergedragons"
        val content = "coins=9999\ngems=50"

        assertTrue(svc.writeFile(pkg, "files/save.dat", content.toByteArray()))

        val read = svc.readFile(pkg, "files/save.dat")
        assertNotNull(read)
        assertEquals(content, read.decodeToString())
    }

    @Test
    fun `writeFile creates parent directories automatically`() {
        val svc = service()
        assertTrue(svc.writeFile("com.example.game", "a/b/c/deep.txt", "hello".toByteArray()))
        assertNotNull(svc.readFile("com.example.game", "a/b/c/deep.txt"))
    }

    @Test
    fun `readFile returns null for non-existent path`() {
        assertNull(service().readFile("com.example.game", "missing.dat"))
    }

    @Test
    fun `readFile returns null for directory path`() {
        val svc = service()
        svc.ensureVirtualDir("com.example.game")
        assertNull(svc.readFile("com.example.game", ""))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `list returns entries at root`() {
        val svc = service()
        val pkg = "com.gram.mergedragons"
        svc.writeFile(pkg, "files/save.dat", ByteArray(0))
        svc.writeFile(pkg, "files/config.json", ByteArray(0))

        val entries = svc.list(pkg)
        assertEquals(1, entries.size)               // only "files/" dir at root
        assertEquals("files", entries[0].name)
        assertTrue(entries[0].isDirectory)
    }

    @Test
    fun `list returns files in sub-directory`() {
        val svc = service()
        val pkg = "com.gram.mergedragons"
        svc.writeFile(pkg, "files/save.dat", "data".toByteArray())
        svc.writeFile(pkg, "files/config.json", "{}".toByteArray())

        val entries = svc.list(pkg, "files")
        val names = entries.map { it.name }.sorted()
        assertEquals(listOf("config.json", "save.dat"), names)
        assertTrue(entries.all { !it.isDirectory })
    }

    @Test
    fun `list returns empty list for non-existent directory`() {
        assertTrue(service().list("com.not.installed", "files").isEmpty())
    }

    @Test
    fun `list entry sizeBytes reflects actual file size`() {
        val svc     = service()
        val pkg     = "com.example.game"
        val content = "hello".toByteArray()
        svc.writeFile(pkg, "data.txt", content)

        val entry = svc.list(pkg).firstOrNull { it.name == "data.txt" }
        assertNotNull(entry)
        assertEquals(content.size.toLong(), entry.sizeBytes)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  exists
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `exists returns true for written file`() {
        val svc = service()
        svc.writeFile("com.example.game", "save.dat", ByteArray(0))
        assertTrue(svc.exists("com.example.game", "save.dat"))
    }

    @Test
    fun `exists returns false for missing path`() {
        assertFalse(service().exists("com.example.game", "no-such-file.dat"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  delete
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes a file`() {
        val svc = service()
        val pkg = "com.example.game"
        svc.writeFile(pkg, "save.dat", ByteArray(0))
        assertTrue(svc.delete(pkg, "save.dat"))
        assertFalse(svc.exists(pkg, "save.dat"))
    }

    @Test
    fun `delete removes a directory tree`() {
        val svc = service()
        val pkg = "com.example.game"
        svc.writeFile(pkg, "dir/a.dat", ByteArray(0))
        svc.writeFile(pkg, "dir/b.dat", ByteArray(0))
        assertTrue(svc.delete(pkg, "dir"))
        assertFalse(svc.exists(pkg, "dir"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  importFromDirectory / exportToDirectory
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `importFromDirectory copies files into virtual root`() {
        val svc     = service()
        val srcDir  = File(tempDir, "src_game").also { it.mkdirs() }
        val saveFile = File(srcDir, "save.dat").also { it.writeText("coins=100") }

        assertTrue(svc.importFromDirectory("com.example.game", srcDir))
        assertEquals("coins=100", svc.readFile("com.example.game", "save.dat")?.decodeToString())
    }

    @Test
    fun `importFromDirectory returns false for non-directory source`() {
        val fakeFile = File(tempDir, "not_a_dir.txt").also { it.writeText("x") }
        assertFalse(service().importFromDirectory("com.example.game", fakeFile))
    }

    @Test
    fun `exportToDirectory copies files out of virtual root`() {
        val svc    = service()
        val pkg    = "com.example.game"
        svc.writeFile(pkg, "save.dat", "gems=999".toByteArray())

        val destDir = File(tempDir, "dest_game")
        assertTrue(svc.exportToDirectory(pkg, destDir))
        assertEquals("gems=999", File(destDir, "save.dat").readText())
    }

    @Test
    fun `exportToDirectory returns false when package has no virtual directory`() {
        val destDir = File(tempDir, "dest")
        assertFalse(service().exportToDirectory("com.not.installed", destDir))
    }
}
