// Reads and writes a running Android app's live process memory via /proc/<pid>/mem without requiring root.
// Liest und schreibt den Live-Prozessspeicher einer laufenden Android-App über /proc/<pid>/mem ohne Root-Rechte.

package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  ProcessMemoryService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Reads and writes a running Android app's memory via `/proc/<pid>/mem`.
 *
 * ## How it works
 *
 * Linux exposes every process's virtual address space as the pseudo-file
 * `/proc/<pid>/mem`.  A process with sufficient privilege (root or a ptrace
 * capability) can seek to any virtual address and read/write raw bytes.
 *
 * The typical flow for a live mod is:
 *
 * ```
 * 1. Start the game via `am start` (normal sandbox, no changes).
 * 2. Wait a moment for the game to initialise its in-memory state.
 * 3. Use findPid()       → locate the game's PID.
 * 4. Use readMemoryMaps()→ parse /proc/<pid>/maps to find candidate regions.
 * 5. Use searchInt()     → scan those regions for the current field value.
 * 6. Use patchInt()      → overwrite the address(es) with the new value.
 * 7. The game "sees" the new value on its next read from its own memory.
 * ```
 *
 * ## Root / privilege requirement
 *
 * Writing to `/proc/<pid>/mem` for a **different** process requires either:
 * - A rooted shell (`su`), or
 * - The `CAP_SYS_PTRACE` capability (granted to `adb shell` on debug builds).
 *
 * Reading is slightly more permissive; some kernels allow reading from
 * processes in the same user group.
 *
 * ## No-root note for the game itself
 *
 * The game is **always** started via `am start` in its own sandbox – it never
 * "knows" it is being observed.  All memory operations are performed by the
 * *launcher*'s shell session, not by code injected into the game process.
 *
 * @param shell          executes shell commands; pass a root-capable shell for
 *                       write operations
 * @param searchChunkKb  size in KiB of each block read during a memory scan
 *                       (smaller = more shell calls; larger = faster but more
 *                       memory used in the shell pipe)
 */
open class ProcessMemoryService(
    private val shell: ShellExecutor = ShellExecutor(),
    private val searchChunkKb: Int = 256
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  PID resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the PID of [packageName] or `null` if the process is not running.
     *
     * Uses `pidof <pkg>` first (fast); falls back to parsing `ps` output for
     * Android versions where `pidof` is unavailable.
     */
    open fun findPid(packageName: String): Int? {
        // ── Primary: pidof ───────────────────────────────────────────────────
        val pidofResult = shell.execute("pidof $packageName", timeoutMs = 5_000L)
        if (pidofResult.success && pidofResult.stdout.isNotBlank()) {
            // pidof may return multiple PIDs separated by spaces; take the first
            return pidofResult.stdout.trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull()
        }

        // ── Fallback: ps ─────────────────────────────────────────────────────
        val psResult = shell.execute("ps -A 2>/dev/null || ps", timeoutMs = 5_000L)
        if (!psResult.success) return null

        return psResult.stdout.lines()
            .firstOrNull { it.contains(packageName) }
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.getOrNull(1)   // second column is PID in `ps` output
            ?.toIntOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Memory-map parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses `/proc/<pid>/maps` and returns all [MemoryRegion]s that are both
     * **readable** and **writable** (`rw`), which are the regions where heap
     * and bss data live.
     *
     * Each line in `/proc/<pid>/maps` has the format:
     * ```
     * <startAddr>-<endAddr> <perms> <offset> <dev> <inode> [pathname]
     * ```
     *
     * @param pid the process ID returned by [findPid]
     * @return list of readable+writable regions, or an empty list on error
     */
    fun readMemoryMaps(pid: Int): List<MemoryRegion> {
        val result = shell.execute("cat /proc/$pid/maps", timeoutMs = 10_000L)
        if (!result.success || result.stdout.isBlank()) return emptyList()

        return result.stdout.lines().mapNotNull { line ->
            parseMapsLine(line)
        }.filter { region ->
            // Only heap / anonymous rw regions are useful for game-value scanning
            region.permissions.startsWith("rw")
        }
    }

    /**
     * Parses a single line from `/proc/<pid>/maps`.
     *
     * Returns `null` for malformed lines (comment lines, empty lines, etc.).
     */
    internal fun parseMapsLine(line: String): MemoryRegion? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null
        val rangeParts = parts[0].split("-")
        if (rangeParts.size != 2) return null
        return runCatching {
            MemoryRegion(
                startAddress = java.lang.Long.parseUnsignedLong(rangeParts[0], 16),
                endAddress   = java.lang.Long.parseUnsignedLong(rangeParts[1], 16),
                permissions  = parts[1],
                pathname     = if (parts.size >= 6) parts[5] else ""
            )
        }.getOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Memory scanning (search for a known value)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scans the writable heap regions of process [pid] for the 32-bit signed
     * integer [value] (little-endian) and returns every virtual address where
     * that value is found.
     *
     * ## Typical usage pattern
     * 1. Look up the current coin count in-game (e.g. 1500).
     * 2. Call `searchInt(pid, 1500)` → get a list of candidate addresses.
     * 3. Spend one coin in-game so the count becomes 1499.
     * 4. Call `searchInt(pid, 1499)` again and intersect with the first result.
     * 5. The remaining address(es) are the true location of the field.
     * 6. Call `patchInt(pid, address, 9999999)` to set the new value.
     *
     * @param pid   the game's PID
     * @param value the 32-bit value to search for
     * @return list of virtual addresses containing [value]
     */
    fun searchInt(pid: Int, value: Int): List<Long> {
        val regions = readMemoryMaps(pid)
        val hits    = mutableListOf<Long>()
        val needle  = intToLittleEndianHex(value)

        regions.forEach { region ->
            hits += searchInRegion(pid, region, needle)
        }
        return hits
    }

    /**
     * Searches a single [region] of process [pid] for [needleHex] (a
     * hex-encoded byte sequence in the form `\xNN\xNN\xNN\xNN`).
     *
     * The scan is done entirely on the device using `dd` + `xxd` piped through
     * `grep`, which avoids transferring large memory dumps over ADB.
     *
     * Returns the virtual addresses (within [region]) where the needle was found.
     */
    private fun searchInRegion(pid: Int, region: MemoryRegion, needleHex: String): List<Long> {
        val regionSize = region.endAddress - region.startAddress
        if (regionSize <= 0 || regionSize > MAX_SCAN_REGION_BYTES) return emptyList()

        // Use `dd` to dump the region and `grep -boa` to find byte offsets of the needle.
        // `grep -P` with the hex needle requires the hex escape format: \xNN\xNN\xNN\xNN
        val cmd = buildString {
            append("dd if=/proc/$pid/mem ")
            append("bs=1 ")
            append("skip=${region.startAddress} ")
            append("count=${regionSize} ")
            append("2>/dev/null")
            append(" | grep -Pboa '${needleHex}'")
        }
        val result = shell.execute(cmd, timeoutMs = 30_000L)
        if (!result.success || result.stdout.isBlank()) return emptyList()

        // grep -boa output: <byte-offset>:<matched-bytes>
        return result.stdout.lines().mapNotNull { line ->
            val offsetStr = line.substringBefore(":", "").trim()
            offsetStr.toLongOrNull()?.let { byteOffset ->
                region.startAddress + byteOffset
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Memory patching (write a new value to a known address)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes [newValue] (32-bit signed int, little-endian) to [address] in
     * process [pid]'s virtual address space.
     *
     * Requires root or ptrace access.
     *
     * Uses `dd` with `seek` to write exactly 4 bytes at the target offset:
     * ```shell
     * printf '\xNN\xNN\xNN\xNN' | dd of=/proc/<pid>/mem bs=1 seek=<addr> conv=notrunc 2>/dev/null
     * ```
     *
     * @param pid      the game's process ID
     * @param address  virtual address of the field to patch
     * @param newValue new 32-bit value to write
     * @return `true` if the write command exited with code 0
     */
    fun patchInt(pid: Int, address: Long, newValue: Int): Boolean {
        val bytes  = intToEscapedBytes(newValue)
        val cmd    = "printf '$bytes' | dd of=/proc/$pid/mem bs=1 seek=$address conv=notrunc 2>/dev/null"
        return shell.execute(cmd, asRoot = true, timeoutMs = 10_000L).success
    }

    /**
     * Convenience: searches for [currentValue] in process [pid], then patches
     * every found address to [newValue].
     *
     * For safety the method only patches addresses when the search returns
     * **exactly one** result.  If multiple addresses are found the caller should
     * narrow down the target using an iterative search approach first (see
     * [searchInt] documentation).
     *
     * @param pid          the game's PID
     * @param currentValue the value currently stored in RAM (used to locate it)
     * @param newValue     the replacement value to write
     * @return [PatchResult] describing what happened
     */
    fun searchAndPatch(pid: Int, currentValue: Int, newValue: Int): PatchResult {
        val addresses = searchInt(pid, currentValue)
        return when {
            addresses.isEmpty() -> PatchResult.NOT_FOUND
            addresses.size > 1  -> PatchResult.AMBIGUOUS(addresses.size)
            else -> {
                val success = patchInt(pid, addresses[0], newValue)
                if (success) PatchResult.PATCHED(addresses[0]) else PatchResult.WRITE_FAILED
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Byte-encoding helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a 32-bit int to a little-endian hex needle string for use with
     * `grep -P`, e.g. `1500` → `\xdc\x05\x00\x00`.
     */
    internal fun intToLittleEndianHex(value: Int): String {
        val bytes = intToBytes(value)
        return bytes.joinToString("") { b -> "\\x%02x".format(b.toInt() and 0xFF) }
    }

    /**
     * Converts a 32-bit int to a `printf`-compatible escaped byte string for
     * use with `dd`, e.g. `1500` → `\xdc\x05\x00\x00`.
     */
    internal fun intToEscapedBytes(value: Int): String {
        val bytes = intToBytes(value)
        return bytes.joinToString("") { b -> "\\x%02x".format(b.toInt() and 0xFF) }
    }

    /** Returns the 4 bytes of [value] in little-endian order. */
    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8)  and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Skip regions larger than this to avoid scanning e.g. file-mapped shared libraries. */
        const val MAX_SCAN_REGION_BYTES: Long = 512L * 1024 * 1024  // 512 MiB
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Supporting data classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One entry from `/proc/<pid>/maps`.
 *
 * @param startAddress first virtual address of this region (inclusive)
 * @param endAddress   first virtual address past the end of this region (exclusive)
 * @param permissions  Linux permission flags, e.g. `"rw-p"` or `"r-xp"`
 * @param pathname     backing file name or pseudo-name (e.g. `"[heap]"`, `"[anon]"`, `""`)
 */
data class MemoryRegion(
    val startAddress: Long,
    val endAddress: Long,
    val permissions: String,
    val pathname: String
) {
    /** Size of this region in bytes. */
    val size: Long get() = endAddress - startAddress

    /** `true` for anonymous heap / stack regions that are likely to hold game state. */
    val isAnonymousOrHeap: Boolean
        get() = pathname.isEmpty() || pathname == "[heap]" || pathname.startsWith("[anon")
}

/**
 * Result of a [ProcessMemoryService.searchAndPatch] call.
 */
sealed class PatchResult {

    /** The search yielded no results; the value may have changed or is in a different region. */
    object NOT_FOUND : PatchResult() {
        override fun toString() = "NOT_FOUND"
    }

    /** The write to `/proc/<pid>/mem` failed (likely missing root/ptrace). */
    object WRITE_FAILED : PatchResult() {
        override fun toString() = "WRITE_FAILED"
    }

    /**
     * The value was found at multiple addresses; supply a narrower search to
     * identify the exact location.
     * @param count number of candidate addresses found
     */
    data class AMBIGUOUS(val count: Int) : PatchResult()

    /**
     * The value was found at exactly one address and successfully patched.
     * @param address the virtual address that was patched
     */
    data class PATCHED(val address: Long) : PatchResult()
}
