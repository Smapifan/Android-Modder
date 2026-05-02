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
 * Unit tests for [InAppBrowserService].
 *
 * Uses a JUnit 5 [TempDir] so that no real `/data/data/` path is needed.
 */
class InAppBrowserServiceTest {

    @TempDir
    lateinit var tempDir: File

    private fun browser(): InAppBrowserService {
        val vfs = VirtualFileSystemService(appFilesRoot = tempDir.absolutePath)
        return InAppBrowserService(vfs)
    }

    // helper: write a virtual file
    private fun write(browser: InAppBrowserService, pkg: String, path: String, text: String) {
        assertTrue(browser.writeTextFile(pkg, path, text), "Failed to write $path for $pkg")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listPackages
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listPackages returns empty list when VFS is empty`() {
        assertTrue(browser().listPackages().isEmpty())
    }

    @Test
    fun `listPackages returns all packages that have virtual files`() {
        val br = browser()
        write(br, "com.game.a", "save.dat", "x")
        write(br, "com.game.b", "save.dat", "y")

        val pkgs = br.listPackages()
        assertTrue("com.game.a" in pkgs)
        assertTrue("com.game.b" in pkgs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  browse
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `browse returns empty list for package with no files`() {
        assertTrue(browser().browse("com.not.installed").isEmpty())
    }

    @Test
    fun `browse lists directories before files`() {
        val br  = browser()
        val pkg = "com.gram.mergedragons"
        write(br, pkg, "readme.txt", "info")
        write(br, pkg, "files/save.dat", "data")

        val entries = br.browse(pkg)
        assertTrue(entries[0].isDirectory, "First entry should be a directory")
        assertFalse(entries.last().isDirectory, "Last entry should be a file")
    }

    @Test
    fun `browse entries are sorted alphabetically within each group`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "z_file.txt", "z")
        write(br, pkg, "a_dir/x.txt", "x")
        write(br, pkg, "b_dir/y.txt", "y")
        write(br, pkg, "a_file.txt", "a")

        val entries = br.browse(pkg)
        val dirs   = entries.filter { it.isDirectory }.map { it.name }
        val files  = entries.filter { !it.isDirectory }.map { it.name }
        assertEquals(listOf("a_dir", "b_dir"), dirs)
        assertEquals(listOf("a_file.txt", "z_file.txt"), files)
    }

    @Test
    fun `browse sub-directory shows only its direct children`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "files/save.dat", "save")
        write(br, pkg, "files/config.json", "{}")

        val entries = br.browse(pkg, "files")
        assertEquals(2, entries.size)
        val names = entries.map { it.name }.sorted()
        assertEquals(listOf("config.json", "save.dat"), names)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  readTextFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `readTextFile returns content of written file`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "save.dat", "coins=9999")

        assertEquals("coins=9999", br.readTextFile(pkg, "save.dat"))
    }

    @Test
    fun `readTextFile returns null for missing file`() {
        assertNull(browser().readTextFile("com.example.game", "missing.dat"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  readBinaryFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `readBinaryFile returns bytes of written file`() {
        val br      = browser()
        val pkg     = "com.example.game"
        val data    = byteArrayOf(1, 2, 3, 0xFF.toByte())
        assertTrue(br.writeBinaryFile(pkg, "data.bin", data))

        val read = br.readBinaryFile(pkg, "data.bin")
        assertNotNull(read)
        assertTrue(data.contentEquals(read))
    }

    @Test
    fun `readBinaryFile returns null for missing file`() {
        assertNull(browser().readBinaryFile("com.example.game", "no.bin"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  writeTextFile
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `writeTextFile creates parent directories automatically`() {
        val br  = browser()
        val pkg = "com.example.game"
        assertTrue(br.writeTextFile(pkg, "deep/nested/file.txt", "hello"))
        assertEquals("hello", br.readTextFile(pkg, "deep/nested/file.txt"))
    }

    @Test
    fun `writeTextFile overwrites existing file`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "file.txt", "old")
        assertTrue(br.writeTextFile(pkg, "file.txt", "new"))
        assertEquals("new", br.readTextFile(pkg, "file.txt"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  delete
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes a file`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "del.dat", "bye")
        assertTrue(br.delete(pkg, "del.dat"))
        assertNull(br.readTextFile(pkg, "del.dat"))
    }

    @Test
    fun `delete removes a directory tree`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "tree/a.dat", "a")
        write(br, pkg, "tree/b.dat", "b")

        assertTrue(br.delete(pkg, "tree"))
        assertTrue(br.browse(pkg).none { it.name == "tree" })
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  buildTree
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `buildTree returns empty string for empty package`() {
        assertEquals("", browser().buildTree("com.not.installed"))
    }

    @Test
    fun `buildTree contains file names`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "files/save.dat", "data")
        write(br, pkg, "files/config.json", "{}")

        val tree = br.buildTree(pkg)
        assertTrue(tree.contains("files"), "Tree must mention 'files' directory")
        assertTrue(tree.contains("save.dat"), "Tree must mention 'save.dat'")
        assertTrue(tree.contains("config.json"), "Tree must mention 'config.json'")
    }

    @Test
    fun `buildTree shows file sizes`() {
        val br      = browser()
        val pkg     = "com.example.game"
        val content = "hello"
        write(br, pkg, "data.txt", content)

        val tree = br.buildTree(pkg)
        assertTrue(tree.contains("${content.length} bytes"),
            "Tree should show file size; got: $tree")
    }

    @Test
    fun `buildTree indents nested directories`() {
        val br  = browser()
        val pkg = "com.example.game"
        write(br, pkg, "parent/child/leaf.dat", "x")

        val tree = br.buildTree(pkg)
        // leaf should appear further indented than parent
        val parentLine = tree.lines().first { it.contains("parent") }
        val childLine  = tree.lines().first { it.contains("child") }
        val leafLine   = tree.lines().first { it.contains("leaf.dat") }

        assertTrue(childLine.length > parentLine.length || childLine.startsWith("  "),
            "Child directory should be indented relative to parent")
        assertTrue(leafLine.length >= childLine.length,
            "Leaf file should be indented at least as much as child directory")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  System-path "root browser" (VIRTUAL_FS — data/ and data/data/ layout)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `browseSystemPath returns empty list for non-existent path`() {
        assertTrue(browser().browseSystemPath("data/data/no.such.pkg").isEmpty())
    }

    @Test
    fun `browseSystemPath lists entries at data data pkg directory`() {
        val br  = browser()
        val pkg = "com.example.game"
        br.writeSystemBinaryFile("data/data/$pkg/files/save.dat", "data".toByteArray())

        val entries = br.browseSystemPath("data/data/$pkg")
        assertEquals(1, entries.size)
        assertEquals("files", entries[0].name)
        assertTrue(entries[0].isDirectory)
    }

    @Test
    fun `browseSystemPath sets packageName from data data path`() {
        val br  = browser()
        val pkg = "com.example.game"
        br.writeSystemTextFile("data/data/$pkg/save.dat", "x")

        val entries = br.browseSystemPath("data/data/$pkg")
        assertTrue(entries.all { it.packageName == pkg })
    }

    @Test
    fun `browseSystemPath sorts directories before files`() {
        val br  = browser()
        val pkg = "com.example.game"
        br.writeSystemTextFile("data/data/$pkg/readme.txt", "info")
        br.writeSystemTextFile("data/data/$pkg/files/save.dat", "data")

        val entries = br.browseSystemPath("data/data/$pkg")
        assertTrue(entries[0].isDirectory, "First entry should be a directory")
        assertFalse(entries.last().isDirectory, "Last entry should be a file")
    }

    @Test
    fun `listSystemPackages returns packages with virtual system dirs`() {
        val br = browser()
        br.writeSystemTextFile("data/data/com.game.a/save.dat", "a")
        br.writeSystemTextFile("data/data/com.game.b/save.dat", "b")

        val pkgs = br.listSystemPackages()
        assertTrue("com.game.a" in pkgs)
        assertTrue("com.game.b" in pkgs)
    }

    @Test
    fun `readSystemTextFile returns content of written file`() {
        val br = browser()
        br.writeSystemTextFile("data/data/com.example.game/save.dat", "coins=99")
        assertEquals("coins=99", br.readSystemTextFile("data/data/com.example.game/save.dat"))
    }

    @Test
    fun `readSystemTextFile returns null for missing file`() {
        assertNull(browser().readSystemTextFile("data/data/com.example.game/missing.dat"))
    }

    @Test
    fun `readSystemBinaryFile returns bytes of written file`() {
        val br   = browser()
        val data = byteArrayOf(1, 2, 3, 0xFF.toByte())
        assertTrue(br.writeSystemBinaryFile("data/data/com.example.game/data.bin", data))

        val read = br.readSystemBinaryFile("data/data/com.example.game/data.bin")
        assertNotNull(read)
        assertTrue(data.contentEquals(read))
    }

    @Test
    fun `writeSystemTextFile creates parent directories automatically`() {
        val br = browser()
        assertTrue(br.writeSystemTextFile("data/data/com.example.game/deep/nested/file.txt", "hello"))
        assertEquals("hello", br.readSystemTextFile("data/data/com.example.game/deep/nested/file.txt"))
    }

    @Test
    fun `deleteAtSystemPath removes a file`() {
        val br   = browser()
        val path = "data/data/com.example.game/del.dat"
        br.writeSystemTextFile(path, "bye")
        assertTrue(br.deleteAtSystemPath(path))
        assertNull(br.readSystemTextFile(path))
    }

    @Test
    fun `deleteAtSystemPath removes a directory tree`() {
        val br  = browser()
        val pkg = "com.example.game"
        br.writeSystemTextFile("data/data/$pkg/tree/a.dat", "a")
        br.writeSystemTextFile("data/data/$pkg/tree/b.dat", "b")

        assertTrue(br.deleteAtSystemPath("data/data/$pkg/tree"))
        assertTrue(br.browseSystemPath("data/data/$pkg").none { it.name == "tree" })
    }

    @Test
    fun `buildSystemTree contains file names`() {
        val br  = browser()
        val pkg = "com.example.game"
        br.writeSystemTextFile("data/data/$pkg/files/save.dat", "data")
        br.writeSystemTextFile("data/data/$pkg/files/config.json", "{}")

        val tree = br.buildSystemTree("data/data/$pkg")
        assertTrue(tree.contains("files"), "Tree must mention 'files' directory")
        assertTrue(tree.contains("save.dat"), "Tree must mention 'save.dat'")
        assertTrue(tree.contains("config.json"), "Tree must mention 'config.json'")
    }

    @Test
    fun `buildSystemTree shows file sizes`() {
        val br      = browser()
        val content = "hello"
        br.writeSystemTextFile("data/data/com.example.game/data.txt", content)

        val tree = br.buildSystemTree("data/data/com.example.game")
        assertTrue(tree.contains("${content.length} bytes"),
            "Tree should show file size; got: $tree")
    }
}
