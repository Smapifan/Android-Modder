// Multi-type companion to ProcessMemoryService for reading and patching all primitive data types in live process memory.
// Mehrtypiger Begleiter zum ProcessMemoryService zum Lesen und Patchen aller primitiven Datentypen im Live-Prozessspeicher.

package com.smapifan.androidmodder.service

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

// ═════════════════════════════════════════════════════════════════════════════
//  RamEditor – multi-type /proc/<pid>/mem editor
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Multi-type companion to [ProcessMemoryService] that can read and patch
 * every primitive data type a game might store in RAM:
 *
 * | Type     | Width       | Encoding                    |
 * |----------|-------------|-----------------------------|
 * | Byte     | 1 byte      | raw                         |
 * | Short    | 2 bytes LE  | two's complement            |
 * | Int      | 4 bytes LE  | two's complement            |
 * | Long     | 8 bytes LE  | two's complement            |
 * | Float    | 4 bytes LE  | IEEE-754 binary32           |
 * | Double   | 8 bytes LE  | IEEE-754 binary64           |
 * | String   | N bytes     | UTF-8 or UTF-16LE           |
 * | Bytes    | N bytes     | raw                         |
 *
 * All scans and writes reuse the existing regex-based search infrastructure
 * in [ProcessMemoryService] (`dd` + `grep -Pboa` on device) and the 4-byte
 * write path (`printf | dd seek`) extended to variable lengths.
 *
 * ## Typical usage
 *
 * ```kotlin
 * val svc  = ProcessMemoryService(shell)
 * val edit = RamEditor(svc, shell)
 *
 * val pid = svc.findPid("com.gram.mergedragons") ?: return
 *
 * // 1. Scan for the current dragon-star chance (0.05f).
 * val hits = edit.searchFloat(pid, 0.05f)
 *
 * // 2. Patch the first hit to 0.5f.
 * if (hits.size == 1) edit.patchFloat(pid, hits[0], 0.5f)
 * ```
 *
 * @param processMemory the underlying [ProcessMemoryService] (reused for PID
 *                      resolution and memory-map parsing)
 * @param shell         shell executor used for write operations
 */
open class RamEditor(
    private val processMemory: ProcessMemoryService = ProcessMemoryService(),
    private val shell: ShellExecutor = ShellExecutor()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Search – variable-width values
    // ─────────────────────────────────────────────────────────────────────────

    /** Searches writable regions of [pid] for a 2-byte signed [value]. */
    open fun searchShort(pid: Int, value: Short): List<Long> =
        searchBytes(pid, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array())

    /** Searches writable regions of [pid] for an 8-byte signed [value]. */
    open fun searchLong(pid: Int, value: Long): List<Long> =
        searchBytes(pid, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())

    /** Searches writable regions of [pid] for a 4-byte IEEE-754 [value]. */
    open fun searchFloat(pid: Int, value: Float): List<Long> =
        searchBytes(pid, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())

    /** Searches writable regions of [pid] for an 8-byte IEEE-754 [value]. */
    open fun searchDouble(pid: Int, value: Double): List<Long> =
        searchBytes(pid, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())

    /** Searches writable regions of [pid] for a [value] encoded as [charset]. */
    open fun searchString(pid: Int, value: String, charset: Charset = Charsets.UTF_8): List<Long> =
        searchBytes(pid, value.toByteArray(charset))

    /**
     * Searches writable regions of [pid] for the raw byte pattern [needle].
     *
     * Uses the same `dd | grep -Pboa` pipeline as [ProcessMemoryService], but
     * with arbitrary-length needles.
     */
    open fun searchBytes(pid: Int, needle: ByteArray): List<Long> {
        val needleHex = bytesToGrepEscape(needle)
        val regions   = processMemory.readMemoryMaps(pid)
        val hits      = mutableListOf<Long>()
        regions.forEach { region ->
            val regionSize = region.endAddress - region.startAddress
            if (regionSize <= 0 || regionSize > ProcessMemoryService.MAX_SCAN_REGION_BYTES) return@forEach
            val cmd = "dd if=/proc/$pid/mem bs=1 skip=${region.startAddress} count=$regionSize 2>/dev/null" +
                    " | grep -Pboa '$needleHex'"
            val result = shell.execute(cmd, timeoutMs = 30_000L)
            if (!result.success || result.stdout.isBlank()) return@forEach
            result.stdout.lines().forEach { line ->
                val offsetStr = line.substringBefore(":", "").trim()
                offsetStr.toLongOrNull()?.let { byteOffset ->
                    hits += region.startAddress + byteOffset
                }
            }
        }
        return hits
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Patch – variable-width values
    // ─────────────────────────────────────────────────────────────────────────

    /** Writes a 2-byte signed [value] at [address] in process [pid]. */
    open fun patchShort(pid: Int, address: Long, value: Short): Boolean =
        patchBytes(pid, address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array())

    /** Writes an 8-byte signed [value] at [address] in process [pid]. */
    open fun patchLong(pid: Int, address: Long, value: Long): Boolean =
        patchBytes(pid, address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())

    /** Writes a 4-byte IEEE-754 [value] at [address] in process [pid]. */
    open fun patchFloat(pid: Int, address: Long, value: Float): Boolean =
        patchBytes(pid, address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())

    /** Writes an 8-byte IEEE-754 [value] at [address] in process [pid]. */
    open fun patchDouble(pid: Int, address: Long, value: Double): Boolean =
        patchBytes(pid, address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())

    /** Writes a [value] encoded as [charset] at [address] in process [pid]. */
    open fun patchString(pid: Int, address: Long, value: String, charset: Charset = Charsets.UTF_8): Boolean =
        patchBytes(pid, address, value.toByteArray(charset))

    /** Writes raw bytes [data] at [address] in process [pid].  Requires root. */
    open fun patchBytes(pid: Int, address: Long, data: ByteArray): Boolean {
        val escaped = bytesToPrintfEscape(data)
        val cmd = "printf '$escaped' | dd of=/proc/$pid/mem bs=1 seek=$address conv=notrunc 2>/dev/null"
        return shell.execute(cmd, asRoot = true, timeoutMs = 10_000L).success
    }

    /**
     * Convenience: scan for [currentValue] and overwrite with [newValue] when
     * exactly **one** address matches.  Uses the same safety logic as
     * [ProcessMemoryService.searchAndPatch], only for 32-bit IEEE-754 floats.
     */
    open fun searchAndPatchFloat(pid: Int, currentValue: Float, newValue: Float): PatchResult {
        val hits = searchFloat(pid, currentValue)
        return when {
            hits.isEmpty()  -> PatchResult.NOT_FOUND
            hits.size > 1   -> PatchResult.AMBIGUOUS(hits.size)
            else            -> if (patchFloat(pid, hits[0], newValue)) PatchResult.PATCHED(hits[0]) else PatchResult.WRITE_FAILED
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Byte-encoding helpers (shared with ProcessMemoryService style)
    // ─────────────────────────────────────────────────────────────────────────

    internal fun bytesToGrepEscape(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "\\x%02x".format(b.toInt() and 0xFF) }

    internal fun bytesToPrintfEscape(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "\\x%02x".format(b.toInt() and 0xFF) }
}
