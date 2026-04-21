package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProcessMemoryService], [MemoryRegion], and [PatchResult].
 *
 * All tests use a [FakeShell] that returns preconfigured responses so that no
 * real `/proc/<pid>/mem` or Android device is needed.
 */
class ProcessMemoryServiceTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  Fake shell
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shell fake that maps command prefixes to fixed responses.
     * Commands not matching any entry receive exitCode=1, stdout="".
     */
    private class FakeShell(
        private val responses: Map<String, ShellResult> = emptyMap()
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return responses.entries
                .firstOrNull { (prefix, _) -> command.startsWith(prefix) }
                ?.value
                ?: ShellResult(exitCode = 1, stdout = "", stderr = "no match")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  findPid
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `findPid returns PID from pidof`() {
        val fake = FakeShell(mapOf(
            "pidof" to ShellResult(0, "12345", "")
        ))
        val svc = ProcessMemoryService(fake)

        assertEquals(12345, svc.findPid("com.gram.mergedragons"))
        assertTrue(fake.commands.any { it.startsWith("pidof") })
    }

    @Test
    fun `findPid picks first PID when pidof returns multiple`() {
        val fake = FakeShell(mapOf(
            "pidof" to ShellResult(0, "1234 5678", "")
        ))
        assertEquals(1234, ProcessMemoryService(fake).findPid("com.example"))
    }

    @Test
    fun `findPid falls back to ps when pidof fails`() {
        val psOutput = """
            USER      PID   PPID  VSZ    RSS   WCHAN   PC  NAME
            u0_a99    9999  1     1234   5678  binder  0   com.gram.mergedragons
        """.trimIndent()
        val fake = FakeShell(mapOf(
            "pidof"   to ShellResult(1, "", ""),   // pidof not found
            "ps"      to ShellResult(0, psOutput, "")
        ))
        val svc = ProcessMemoryService(fake)

        assertEquals(9999, svc.findPid("com.gram.mergedragons"))
    }

    @Test
    fun `findPid returns null when both pidof and ps fail`() {
        val fake = FakeShell(mapOf(
            "pidof" to ShellResult(1, "", ""),
            "ps"    to ShellResult(1, "", "")
        ))
        assertNull(ProcessMemoryService(fake).findPid("com.no.such.app"))
    }

    @Test
    fun `findPid returns null when ps output does not contain package`() {
        val fake = FakeShell(mapOf(
            "pidof" to ShellResult(1, "", ""),
            "ps"    to ShellResult(0, "USER PID NAME\nroot 1 init", "")
        ))
        assertNull(ProcessMemoryService(fake).findPid("com.missing.app"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  readMemoryMaps
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `readMemoryMaps parses rw regions correctly`() {
        val mapsContent = """
            7f4b000000-7f4b010000 rw-p 00000000 00:00 0  [heap]
            7f4b010000-7f4b020000 r--p 00000000 fd:01 12345  /system/lib/libc.so
            7f4b020000-7f4b030000 rw-p 00000000 00:00 0
        """.trimIndent()
        val fake = FakeShell(mapOf("cat /proc/42/maps" to ShellResult(0, mapsContent, "")))
        val regions = ProcessMemoryService(fake).readMemoryMaps(42)

        // Only rw regions
        assertEquals(2, regions.size)
        assertTrue(regions.all { it.permissions.startsWith("rw") })
    }

    @Test
    fun `readMemoryMaps returns empty list when cat fails`() {
        val fake = FakeShell(mapOf("cat /proc/99/maps" to ShellResult(1, "", "")))
        assertTrue(ProcessMemoryService(fake).readMemoryMaps(99).isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  parseMapsLine
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `parseMapsLine parses heap region`() {
        val svc  = ProcessMemoryService(FakeShell())
        val line = "7f4b000000-7f4b010000 rw-p 00000000 00:00 0 [heap]"
        val region = svc.parseMapsLine(line)

        assertNotNull(region)
        assertEquals("rw-p",  region.permissions)
        assertEquals("[heap]", region.pathname)
        assertEquals(0x10000L, region.size)
    }

    @Test
    fun `parseMapsLine returns null for malformed line`() {
        val svc = ProcessMemoryService(FakeShell())
        assertNull(svc.parseMapsLine("this is not a maps line"))
        assertNull(svc.parseMapsLine(""))
    }

    @Test
    fun `parseMapsLine handles anonymous region without pathname`() {
        val svc    = ProcessMemoryService(FakeShell())
        val region = svc.parseMapsLine("7f0000-7f1000 rw-p 0 00:00 0")
        assertNotNull(region)
        assertTrue(region.pathname.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MemoryRegion helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MemoryRegion size is endAddress minus startAddress`() {
        val r = MemoryRegion(0x1000L, 0x2000L, "rw-p", "[heap]")
        assertEquals(0x1000L, r.size)
    }

    @Test
    fun `MemoryRegion isAnonymousOrHeap is true for heap and anonymous`() {
        assertTrue(MemoryRegion(0, 1, "rw-p", "[heap]").isAnonymousOrHeap)
        assertTrue(MemoryRegion(0, 1, "rw-p", "").isAnonymousOrHeap)
        assertTrue(MemoryRegion(0, 1, "rw-p", "[anon:libc_malloc]").isAnonymousOrHeap)
        assertFalse(MemoryRegion(0, 1, "rw-p", "/system/lib/libc.so").isAnonymousOrHeap)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Byte-encoding helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `intToLittleEndianHex encodes 1 as little-endian`() {
        val svc = ProcessMemoryService(FakeShell())
        // 1 in LE 4 bytes: 0x01 0x00 0x00 0x00
        assertEquals("\\x01\\x00\\x00\\x00", svc.intToLittleEndianHex(1))
    }

    @Test
    fun `intToLittleEndianHex encodes 256 correctly`() {
        val svc = ProcessMemoryService(FakeShell())
        // 256 = 0x0100 → LE: 0x00 0x01 0x00 0x00
        assertEquals("\\x00\\x01\\x00\\x00", svc.intToLittleEndianHex(256))
    }

    @Test
    fun `intToLittleEndianHex encodes 1500 correctly`() {
        val svc = ProcessMemoryService(FakeShell())
        // 1500 = 0x05DC → LE: 0xDC 0x05 0x00 0x00
        assertEquals("\\xdc\\x05\\x00\\x00", svc.intToLittleEndianHex(1500))
    }

    @Test
    fun `intToEscapedBytes matches intToLittleEndianHex`() {
        val svc = ProcessMemoryService(FakeShell())
        assertEquals(svc.intToLittleEndianHex(9999), svc.intToEscapedBytes(9999))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  patchInt
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `patchInt issues dd write command with correct address and value`() {
        val fake = FakeShell(mapOf(
            "printf" to ShellResult(0, "", "")
        ))
        val svc = ProcessMemoryService(fake)

        val success = svc.patchInt(pid = 42, address = 0x7F000000L, newValue = 9999)

        assertTrue(success)
        val cmd = fake.commands[0]
        assertTrue(cmd.contains("dd"), "Command must use dd")
        assertTrue(cmd.contains("/proc/42/mem"), "Command must target /proc/<pid>/mem")
        assertTrue(cmd.contains("seek=2130706432"), "seek must equal address (0x7F000000 = 2130706432)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  searchAndPatch – result variants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `searchAndPatch returns NOT_FOUND when no address matches`() {
        // maps returns a region; grep on it returns empty
        val mapsContent = "7f000000-7f010000 rw-p 0 00:00 0 [heap]"
        val fake = FakeShell(mapOf(
            "cat /proc/1/maps" to ShellResult(0, mapsContent, ""),
            "dd if=/proc/1/mem" to ShellResult(0, "", "")  // grep finds nothing
        ))
        val result = ProcessMemoryService(fake).searchAndPatch(1, 500, 9999)
        assertEquals(PatchResult.NOT_FOUND, result)
    }

    @Test
    fun `searchAndPatch returns AMBIGUOUS when multiple addresses match`() {
        val mapsContent = "7f000000-7f010000 rw-p 0 00:00 0 [heap]"
        // Simulate grep returning 2 matches: offset 0 and offset 4
        val grepOutput  = "0:match\n4:match"
        val fake = FakeShell(mapOf(
            "cat /proc/2/maps" to ShellResult(0, mapsContent, ""),
            "dd if=/proc/2/mem" to ShellResult(0, grepOutput, "")
        ))
        val result = ProcessMemoryService(fake).searchAndPatch(2, 500, 9999)
        assertTrue(result is PatchResult.AMBIGUOUS)
        assertEquals(2, (result as PatchResult.AMBIGUOUS).count)
    }

    @Test
    fun `searchAndPatch returns PATCHED when exactly one address matches`() {
        val mapsContent = "7f000000-7f010000 rw-p 0 00:00 0 [heap]"
        val grepOutput  = "8:match"
        val fake = FakeShell(mapOf(
            "cat /proc/3/maps"  to ShellResult(0, mapsContent, ""),
            "dd if=/proc/3/mem" to ShellResult(0, grepOutput,  ""),
            "printf"            to ShellResult(0, "",           "")
        ))
        val result = ProcessMemoryService(fake).searchAndPatch(3, 500, 9999)
        assertTrue(result is PatchResult.PATCHED)
        // 0x7f000000 + 8 = 0x7f000008 = 2130706440
        assertEquals(2130706440L, (result as PatchResult.PATCHED).address)
    }

    @Test
    fun `searchAndPatch returns WRITE_FAILED when dd write fails`() {
        val mapsContent = "7f000000-7f010000 rw-p 0 00:00 0 [heap]"
        val grepOutput  = "0:match"
        val fake = FakeShell(mapOf(
            "cat /proc/4/maps"  to ShellResult(0, mapsContent, ""),
            "dd if=/proc/4/mem" to ShellResult(0, grepOutput,  ""),
            // printf / dd of = write fails
            "printf"            to ShellResult(1, "",           "write error")
        ))
        val result = ProcessMemoryService(fake).searchAndPatch(4, 500, 9999)
        assertEquals(PatchResult.WRITE_FAILED, result)
    }
}
