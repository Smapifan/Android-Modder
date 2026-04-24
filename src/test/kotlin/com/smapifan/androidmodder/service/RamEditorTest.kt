package com.smapifan.androidmodder.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [RamEditor].  Uses a [FakeShell] to avoid touching any
 * real `/proc/<pid>/mem`.  Verifies byte-encoding, command construction
 * and the [ProcessMemoryService] integration.
 */
class RamEditorTest {

    // ─── Fake shell ────────────────────────────────────────────────────────

    private class FakeShell(
        private val responses: Map<String, ShellResult> = emptyMap()
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()
        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return responses.entries.firstOrNull { (p, _) -> command.startsWith(p) }?.value
                ?: ShellResult(exitCode = 1, stdout = "", stderr = "no match")
        }
    }

    // ─── patchFloat / patchLong / patchDouble: byte encoding ──────────────

    @Test fun `patchFloat writes 4 little-endian IEEE-754 bytes`() {
        val shell  = FakeShell(mapOf("printf" to ShellResult(0, "", "")))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertTrue(editor.patchFloat(pid = 123, address = 0x10, value = 0.5f))

        val cmd = shell.commands.single()
        assertTrue(cmd.contains("seek=16"))
        // 0.5f in IEEE-754 LE = 00 00 00 3F
        assertTrue(cmd.contains("""\x00\x00\x00\x3f"""))
    }

    @Test fun `patchLong writes 8 little-endian bytes`() {
        val shell  = FakeShell(mapOf("printf" to ShellResult(0, "", "")))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertTrue(editor.patchLong(pid = 77, address = 0, value = 1L))

        // 1L in LE = 01 00 00 00 00 00 00 00
        assertTrue(shell.commands.single().contains("""\x01\x00\x00\x00\x00\x00\x00\x00"""))
    }

    @Test fun `patchDouble writes 8 little-endian IEEE-754 bytes`() {
        val shell  = FakeShell(mapOf("printf" to ShellResult(0, "", "")))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertTrue(editor.patchDouble(pid = 5, address = 0, value = 1.0))

        // 1.0 in IEEE-754 LE double = 00 00 00 00 00 00 F0 3F
        assertTrue(shell.commands.single().contains("""\x00\x00\x00\x00\x00\x00\xf0\x3f"""))
    }

    // ─── patchString / patchBytes ──────────────────────────────────────────

    @Test fun `patchString writes UTF-8 bytes`() {
        val shell  = FakeShell(mapOf("printf" to ShellResult(0, "", "")))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertTrue(editor.patchString(pid = 5, address = 0, value = "AB"))

        // 'A'=0x41 'B'=0x42
        assertTrue(shell.commands.single().contains("""\x41\x42"""))
    }

    @Test fun `patchBytes fails when shell returns non-zero`() {
        val shell  = FakeShell(mapOf("printf" to ShellResult(1, "", "denied")))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertFalse(editor.patchBytes(pid = 5, address = 0, data = byteArrayOf(0x00)))
    }

    // ─── searchFloat returns addresses from region + grep offsets ─────────

    @Test fun `searchFloat computes virtual addresses from region offsets`() {
        val maps = "00010000-00020000 rw-p 00000000 00:00 0    [heap]\n"
        val shell = FakeShell(mapOf(
            "cat /proc/42/maps" to ShellResult(0, maps, ""),
            "dd if=/proc/42/mem" to ShellResult(0, "100:match\n200:match", "")
        ))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        val hits   = editor.searchFloat(pid = 42, value = 0.5f)

        // region starts at 0x10000 = 65536, so hits are 65636 and 65736
        assertEquals(listOf(65636L, 65736L), hits)
    }

    // ─── searchAndPatchFloat dispatches correctly ──────────────────────────

    @Test fun `searchAndPatchFloat returns NOT_FOUND when region has no hits`() {
        val maps = "00010000-00020000 rw-p 00000000 00:00 0    [heap]\n"
        val shell = FakeShell(mapOf(
            "cat /proc/1/maps"   to ShellResult(0, maps, ""),
            "dd if=/proc/1/mem"  to ShellResult(0, "", "")
        ))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        assertEquals(PatchResult.NOT_FOUND, editor.searchAndPatchFloat(1, 0.05f, 0.5f))
    }

    @Test fun `searchAndPatchFloat returns AMBIGUOUS when multiple hits`() {
        val maps = "00010000-00020000 rw-p 00000000 00:00 0    [heap]\n"
        val shell = FakeShell(mapOf(
            "cat /proc/1/maps"  to ShellResult(0, maps, ""),
            "dd if=/proc/1/mem" to ShellResult(0, "100:match\n200:match\n", "")
        ))
        val editor = RamEditor(ProcessMemoryService(shell), shell)
        val result = editor.searchAndPatchFloat(1, 0.05f, 0.5f)
        assertTrue(result is PatchResult.AMBIGUOUS && result.count == 2)
    }

    // ─── Byte-encoding helpers are stable ─────────────────────────────────

    @Test fun `bytesToPrintfEscape produces lowercase hex`() {
        val editor = RamEditor()
        val bytes  = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xAB).array()
        assertEquals("""\xab\x00\x00\x00""", editor.bytesToPrintfEscape(bytes))
    }
}
