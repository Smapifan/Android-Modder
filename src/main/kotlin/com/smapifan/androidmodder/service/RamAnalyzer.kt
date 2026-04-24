package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  RamAnalyzer – read-only memory analysis
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Read-only analytics on top of [ProcessMemoryService] and [RamEditor].
 *
 * While [ProcessMemoryService] is the low-level read/write primitive and
 * [RamEditor] adds multi-type writes, `RamAnalyzer` helps the user make
 * sense of the numbers:
 *
 * - [summariseRegions] – aggregates `/proc/<pid>/maps` by permission and
 *   path category so you can tell at a glance how much RAM the game's
 *   heap, stack, shared libs and anon regions occupy.
 * - [multiTypeSearch]  – scans for the same numeric value interpreted as
 *   Int, Long, Float **and** Double in a single sweep.  Great when the
 *   decompiled source says `K_CHANCE_OF_DRAGON_STAR = 0.05` but you don't
 *   know yet whether the compiler picked `float` or `double`.
 * - [Snapshot] / [diff] – implement the classic "progressive scan": take
 *   a snapshot of address→value pairs, wait for the player to change the
 *   value in-game, take another snapshot, and diff them to narrow down
 *   the true address.
 *
 * @param processMemory shared access to PID lookup and `/proc/<pid>/maps`
 * @param ramEditor     multi-type scan backend
 */
open class RamAnalyzer(
    private val processMemory: ProcessMemoryService = ProcessMemoryService(),
    private val ramEditor: RamEditor = RamEditor(processMemory)
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Region summary
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads `/proc/<pid>/maps`, classifies every region and returns a
     * [RegionSummary] aggregating the total byte count, region count and
     * per-category breakdown.
     *
     * Categories:
     * - `HEAP`    – `[heap]` pseudo-name
     * - `STACK`   – `[stack]` / `[stack:NNN]`
     * - `ANON`    – empty path or `[anon*]` – where almost all game state lives
     * - `LIB`     – `.so` file-backed regions (shared libraries)
     * - `FILE`    – any other file-backed region (mapped resources, APK assets)
     * - `OTHER`   – unknown pseudo-regions (`[vdso]`, `[vsyscall]`, …)
     */
    open fun summariseRegions(pid: Int): RegionSummary {
        val regions = processMemory.readMemoryMaps(pid)
        val byCategory = LinkedHashMap<RegionCategory, CategoryStats>()
        RegionCategory.values().forEach { byCategory[it] = CategoryStats() }

        regions.forEach { region ->
            val cat = classify(region.pathname)
            byCategory[cat] = byCategory.getValue(cat).add(region.size)
        }

        val total = byCategory.values.sumOf { it.totalBytes }
        return RegionSummary(
            totalRegions = regions.size,
            totalBytes   = total,
            byCategory   = byCategory
        )
    }

    internal fun classify(pathname: String): RegionCategory {
        val p = pathname.trim()
        return when {
            p == "[heap]"                          -> RegionCategory.HEAP
            p.startsWith("[stack")                 -> RegionCategory.STACK
            p.isEmpty() || p.startsWith("[anon")   -> RegionCategory.ANON
            p.endsWith(".so") || p.contains(".so.")-> RegionCategory.LIB
            p.startsWith("[")                      -> RegionCategory.OTHER
            else                                   -> RegionCategory.FILE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Multi-type value search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches [pid]'s writable regions for [value] interpreted as every
     * primitive numeric type and returns a [MultiTypeHits] summary.
     *
     * This is helpful when the exact in-memory representation is unknown:
     * the same game field might be stored as `Int`, `Long`, `Float` or
     * `Double` depending on the compiler / engine.
     */
    open fun multiTypeSearch(pid: Int, value: Number): MultiTypeHits {
        val intHits    = if (value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE)
                             processMemory.searchInt(pid, value.toInt()) else emptyList()
        val longHits   = ramEditor.searchLong  (pid, value.toLong())
        val floatHits  = ramEditor.searchFloat (pid, value.toFloat())
        val doubleHits = ramEditor.searchDouble(pid, value.toDouble())

        return MultiTypeHits(
            asInt    = intHits,
            asLong   = longHits,
            asFloat  = floatHits,
            asDouble = doubleHits
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Progressive scan (snapshot + diff)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a [Snapshot] keyed by virtual address for every address where
     * [initialValue] is currently stored (as a 32-bit int).  The snapshot is
     * later intersected with a second scan via [diff] to narrow down the
     * true field address.
     */
    open fun snapshotInt(pid: Int, initialValue: Int): Snapshot {
        val addresses = processMemory.searchInt(pid, initialValue)
        return Snapshot(addresses = addresses.toSet(), lastValue = initialValue.toLong())
    }

    /**
     * Diffs a [previous] snapshot against a fresh search for [newValue].
     * Returns the addresses present in **both** sets – i.e. the locations
     * where the value was `previous.lastValue` before and is `newValue` now.
     *
     * After a few iterations this usually converges on a single address.
     */
    open fun diff(pid: Int, previous: Snapshot, newValue: Int): Snapshot {
        val freshHits = processMemory.searchInt(pid, newValue).toSet()
        val narrowed  = previous.addresses.intersect(freshHits)
        return Snapshot(addresses = narrowed, lastValue = newValue.toLong())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Data types
// ─────────────────────────────────────────────────────────────────────────────

/** Category assigned to a region by [RamAnalyzer.classify]. */
enum class RegionCategory { HEAP, STACK, ANON, LIB, FILE, OTHER }

/** Statistics for one [RegionCategory] inside a [RegionSummary]. */
data class CategoryStats(
    val regionCount: Int = 0,
    val totalBytes: Long = 0
) {
    internal fun add(size: Long) = CategoryStats(regionCount + 1, totalBytes + size)
}

/**
 * Result of [RamAnalyzer.summariseRegions].
 *
 * @param totalRegions Number of writable regions found.
 * @param totalBytes   Sum of all region sizes in bytes.
 * @param byCategory   Per-category statistics.
 */
data class RegionSummary(
    val totalRegions: Int,
    val totalBytes: Long,
    val byCategory: Map<RegionCategory, CategoryStats>
)

/**
 * Result of [RamAnalyzer.multiTypeSearch].  Each list contains the virtual
 * addresses where the value was found under the respective type interpretation.
 */
data class MultiTypeHits(
    val asInt: List<Long>,
    val asLong: List<Long>,
    val asFloat: List<Long>,
    val asDouble: List<Long>
) {
    /** Total number of candidate addresses across all type interpretations. */
    val total: Int get() = asInt.size + asLong.size + asFloat.size + asDouble.size
}

/**
 * A progressive-scan snapshot: the set of addresses that currently contain a
 * given [lastValue].  Used with [RamAnalyzer.diff] to narrow down the true
 * field address over multiple iterations.
 */
data class Snapshot(
    val addresses: Set<Long>,
    val lastValue: Long
)
