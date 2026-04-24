package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [RamAnalyzer].  Uses a [FakeShell] to deliver synthetic
 * `/proc/<pid>/maps` and `dd | grep` output so that no real Android device
 * is required.
 */
class RamAnalyzerTest {

    // ─── Fake shell ────────────────────────────────────────────────────────

    private class FakeShell(
        private val responses: Map<String, ShellResult> = emptyMap()
    ) : ShellExecutor() {
        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult =
            responses.entries.firstOrNull { (p, _) -> command.startsWith(p) }?.value
                ?: ShellResult(exitCode = 1, stdout = "", stderr = "no match")
    }

    // ─── classify ──────────────────────────────────────────────────────────

    @Test fun `classify categorises well-known path names correctly`() {
        val analyzer = RamAnalyzer()
        assertEquals(RegionCategory.HEAP,  analyzer.classify("[heap]"))
        assertEquals(RegionCategory.STACK, analyzer.classify("[stack]"))
        assertEquals(RegionCategory.STACK, analyzer.classify("[stack:1234]"))
        assertEquals(RegionCategory.ANON,  analyzer.classify(""))
        assertEquals(RegionCategory.ANON,  analyzer.classify("[anon:libc_malloc]"))
        assertEquals(RegionCategory.LIB,   analyzer.classify("/system/lib64/libc.so"))
        assertEquals(RegionCategory.FILE,  analyzer.classify("/data/app/game.apk"))
        assertEquals(RegionCategory.OTHER, analyzer.classify("[vdso]"))
    }

    // ─── summariseRegions ─────────────────────────────────────────────────

    @Test fun `summariseRegions aggregates per-category totals`() {
        val maps = """
            00010000-00020000 rw-p 00000000 00:00 0    [heap]
            00100000-00110000 rw-p 00000000 00:00 0    [anon:libc]
            00200000-00210000 rw-p 00000000 00:00 0    /system/lib64/libgame.so
        """.trimIndent()
        val shell    = FakeShell(mapOf("cat /proc/42/maps" to ShellResult(0, maps, "")))
        val analyzer = RamAnalyzer(ProcessMemoryService(shell), RamEditor(ProcessMemoryService(shell), shell))
        val summary  = analyzer.summariseRegions(42)

        assertEquals(3, summary.totalRegions)
        // Each region is 0x10000 = 65536 bytes; total = 196608
        assertEquals(196608L, summary.totalBytes)
        assertEquals(1, summary.byCategory.getValue(RegionCategory.HEAP).regionCount)
        assertEquals(1, summary.byCategory.getValue(RegionCategory.ANON).regionCount)
        assertEquals(1, summary.byCategory.getValue(RegionCategory.LIB).regionCount)
    }

    // ─── Snapshot / diff ───────────────────────────────────────────────────

    @Test fun `diff narrows snapshot to intersection of addresses`() {
        val maps = "00010000-00020000 rw-p 00000000 00:00 0    [heap]\n"
        // First scan for value 100 finds offsets 10 and 20. Second scan for 99 finds 20 and 30.
        val shell = FakeShell(mapOf(
            "cat /proc/5/maps"  to ShellResult(0, maps, ""),
            "dd if=/proc/5/mem" to ShellResult(0, "10:match\n20:match\n", "")
        ))
        val pms      = ProcessMemoryService(shell)
        val analyzer = RamAnalyzer(pms, RamEditor(pms, shell))

        val snap1 = analyzer.snapshotInt(pid = 5, initialValue = 100)
        assertEquals(setOf(65546L, 65556L), snap1.addresses)
        assertEquals(100L, snap1.lastValue)

        // Second snapshot also returns the same shell response (same offsets 10, 20)
        // so intersection must be both.
        val snap2 = analyzer.diff(pid = 5, previous = snap1, newValue = 99)
        assertEquals(setOf(65546L, 65556L), snap2.addresses)
        assertEquals(99L, snap2.lastValue)
    }

    // ─── multiTypeSearch ───────────────────────────────────────────────────

    @Test fun `multiTypeSearch returns hits for every numeric interpretation`() {
        val maps = "00010000-00020000 rw-p 00000000 00:00 0    [heap]\n"
        val shell = FakeShell(mapOf(
            "cat /proc/7/maps"  to ShellResult(0, maps, ""),
            "dd if=/proc/7/mem" to ShellResult(0, "100:match\n", "")
        ))
        val pms      = ProcessMemoryService(shell)
        val analyzer = RamAnalyzer(pms, RamEditor(pms, shell))

        val hits = analyzer.multiTypeSearch(pid = 7, value = 100)
        assertTrue(hits.asInt.isNotEmpty())
        assertTrue(hits.asLong.isNotEmpty())
        assertTrue(hits.asFloat.isNotEmpty())
        assertTrue(hits.asDouble.isNotEmpty())
        assertEquals(4, hits.total / hits.asInt.size)
    }
}
