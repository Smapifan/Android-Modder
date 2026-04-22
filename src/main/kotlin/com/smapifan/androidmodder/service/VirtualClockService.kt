package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  VirtualClockService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Provides an isolated time / clock per guest-app session.
 *
 * Each virtual session can have:
 * - A **time offset** (real clock + offset) – the virtual clock ticks at the
 *   same speed as the real one but shifted forward or backward.
 * - A **fixed time** – the virtual clock always returns the same epoch
 *   millisecond value (useful for freeze-frame testing).
 * - A **virtual timezone** – an IANA timezone ID that is returned instead of
 *   the device's default timezone.
 *
 * None of these settings affect `System.currentTimeMillis()`, the system clock,
 * or any other app on the device.
 *
 * ## Use cases
 *
 * - Unlocking time-gated daily rewards in games by advancing the virtual clock.
 * - Running an app as if in a different timezone without changing the system
 *   timezone.
 * - Freezing time for reproducible testing.
 *
 * ## Integration with the launcher
 *
 * The virtual clock value is written into `mod_launcher.sh` before `am start`.
 * The injected smali bootstrap intercepts `System.currentTimeMillis()` and
 * `java.util.TimeZone.getDefault()` inside the game process and returns the
 * virtual values instead.
 */
class VirtualClockService {

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal state
    // ─────────────────────────────────────────────────────────────────────────

    private data class ClockConfig(
        val offsetMs: Long   = 0L,
        val fixedTimeMs: Long? = null,
        val timezoneId: String? = null
    )

    private val sessionClocks = mutableMapOf<String, ClockConfig>()

    // ─────────────────────────────────────────────────────────────────────────
    //  Configuration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets a time offset for [packageName].
     *
     * The virtual time will be `System.currentTimeMillis() + offsetMs`.
     * A positive [offsetMs] moves time forward; a negative value moves it back.
     *
     * Clears any previously set fixed time for this package.
     */
    fun setOffset(packageName: String, offsetMs: Long) {
        sessionClocks[packageName] = (sessionClocks[packageName] ?: ClockConfig())
            .copy(offsetMs = offsetMs, fixedTimeMs = null)
    }

    /**
     * Sets a completely fixed time for [packageName].
     *
     * The virtual time will always return [fixedTimeMs] regardless of the real
     * clock.  Useful for reproducing time-sensitive game states.
     */
    fun setFixedTime(packageName: String, fixedTimeMs: Long) {
        sessionClocks[packageName] = (sessionClocks[packageName] ?: ClockConfig())
            .copy(fixedTimeMs = fixedTimeMs)
    }

    /**
     * Sets the virtual timezone for [packageName].
     *
     * @param timezoneId IANA timezone ID, e.g. `"Europe/Berlin"`,
     *                   `"America/New_York"`, `"Asia/Tokyo"`
     */
    fun setTimezone(packageName: String, timezoneId: String) {
        sessionClocks[packageName] = (sessionClocks[packageName] ?: ClockConfig())
            .copy(timezoneId = timezoneId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Queries
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the virtual current time in milliseconds since the Unix epoch
     * for [packageName].
     *
     * Resolution order:
     * 1. If a fixed time is set → return fixed time.
     * 2. If an offset is set → return `System.currentTimeMillis() + offset`.
     * 3. No configuration → return `System.currentTimeMillis()` (real clock).
     */
    fun currentTimeMillis(packageName: String): Long {
        val config = sessionClocks[packageName] ?: return System.currentTimeMillis()
        return config.fixedTimeMs ?: (System.currentTimeMillis() + config.offsetMs)
    }

    /**
     * Returns the virtual timezone ID for [packageName], or `null` if no virtual
     * timezone is configured.
     */
    fun getTimezoneId(packageName: String): String? = sessionClocks[packageName]?.timezoneId

    /**
     * Returns the time offset in milliseconds for [packageName].
     *
     * Returns `0` if no offset is configured (or a fixed time is active).
     */
    fun getOffsetMs(packageName: String): Long =
        sessionClocks[packageName]?.let {
            if (it.fixedTimeMs != null) 0L else it.offsetMs
        } ?: 0L

    /**
     * Returns `true` if any virtual clock configuration is set for [packageName].
     */
    fun hasVirtualClock(packageName: String): Boolean = sessionClocks.containsKey(packageName)

    /**
     * Clears all virtual clock settings for [packageName].
     *
     * After this call the guest app sees the real system time.
     */
    fun clearClock(packageName: String) {
        sessionClocks.remove(packageName)
    }

    /**
     * Returns a snapshot of all active virtual clock sessions, mapping each
     * package name to its current virtual time in milliseconds.
     */
    fun listSessions(): Map<String, Long> =
        sessionClocks.mapValues { (pkg, _) -> currentTimeMillis(pkg) }
}
