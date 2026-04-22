package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [VirtualClockService].
 */
class VirtualClockServiceTest {

    private val pkg = "com.gram.mergedragons"

    // ─────────────────────────────────────────────────────────────────────────
    //  currentTimeMillis – no config
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `currentTimeMillis returns system time when no config set`() {
        val before = System.currentTimeMillis()
        val result = VirtualClockService().currentTimeMillis(pkg)
        val after  = System.currentTimeMillis()
        assertTrue(result in before..after)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  setOffset
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setOffset applies positive offset`() {
        val svc        = VirtualClockService()
        val offsetMs   = 3_600_000L // +1 hour
        svc.setOffset(pkg, offsetMs)

        val before = System.currentTimeMillis()
        val result = svc.currentTimeMillis(pkg)
        val after  = System.currentTimeMillis()

        assertTrue(result >= before + offsetMs)
        assertTrue(result <= after  + offsetMs)
    }

    @Test
    fun `setOffset applies negative offset`() {
        val svc      = VirtualClockService()
        val offsetMs = -3_600_000L // -1 hour
        svc.setOffset(pkg, offsetMs)

        val before = System.currentTimeMillis()
        val result = svc.currentTimeMillis(pkg)
        val after  = System.currentTimeMillis()

        assertTrue(result <= before + offsetMs + 50) // allow 50 ms slack
        assertTrue(result >= after  + offsetMs - 50)
    }

    @Test
    fun `setOffset clears any fixed time`() {
        val svc = VirtualClockService()
        svc.setFixedTime(pkg, 1_000_000L)
        svc.setOffset(pkg, 0L) // clear fixed time by setting offset

        // Should now track real clock, not the fixed value
        assertNotEquals(1_000_000L, svc.currentTimeMillis(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  setFixedTime
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setFixedTime always returns the fixed value`() {
        val svc       = VirtualClockService()
        val fixedTime = 1_700_000_000_000L
        svc.setFixedTime(pkg, fixedTime)

        assertEquals(fixedTime, svc.currentTimeMillis(pkg))
        // Wait a bit and call again – still fixed
        Thread.sleep(10)
        assertEquals(fixedTime, svc.currentTimeMillis(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  setTimezone
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getTimezoneId returns null when no timezone set`() {
        assertNull(VirtualClockService().getTimezoneId(pkg))
    }

    @Test
    fun `setTimezone stores timezone and getTimezoneId returns it`() {
        val svc = VirtualClockService()
        svc.setTimezone(pkg, "Europe/Berlin")
        assertEquals("Europe/Berlin", svc.getTimezoneId(pkg))
    }

    @Test
    fun `setTimezone does not change time value`() {
        val svc    = VirtualClockService()
        val before = System.currentTimeMillis()
        svc.setTimezone(pkg, "America/New_York")
        val result = svc.currentTimeMillis(pkg)
        val after  = System.currentTimeMillis()
        assertTrue(result in before..after)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  getOffsetMs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getOffsetMs returns 0 when no config set`() {
        assertEquals(0L, VirtualClockService().getOffsetMs(pkg))
    }

    @Test
    fun `getOffsetMs returns 0 when fixed time is active`() {
        val svc = VirtualClockService()
        svc.setFixedTime(pkg, 1_000_000L)
        assertEquals(0L, svc.getOffsetMs(pkg))
    }

    @Test
    fun `getOffsetMs returns configured offset`() {
        val svc = VirtualClockService()
        svc.setOffset(pkg, 5_000L)
        assertEquals(5_000L, svc.getOffsetMs(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  hasVirtualClock
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasVirtualClock returns false before any config`() {
        assertFalse(VirtualClockService().hasVirtualClock(pkg))
    }

    @Test
    fun `hasVirtualClock returns true after setOffset`() {
        val svc = VirtualClockService()
        svc.setOffset(pkg, 1000L)
        assertTrue(svc.hasVirtualClock(pkg))
    }

    @Test
    fun `hasVirtualClock returns true after setFixedTime`() {
        val svc = VirtualClockService()
        svc.setFixedTime(pkg, 1000L)
        assertTrue(svc.hasVirtualClock(pkg))
    }

    @Test
    fun `hasVirtualClock returns true after setTimezone only`() {
        val svc = VirtualClockService()
        svc.setTimezone(pkg, "Asia/Tokyo")
        assertTrue(svc.hasVirtualClock(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  clearClock
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearClock removes all config for package`() {
        val svc = VirtualClockService()
        svc.setOffset(pkg, 99_000L)
        svc.setTimezone(pkg, "Asia/Tokyo")
        svc.clearClock(pkg)

        assertFalse(svc.hasVirtualClock(pkg))
        assertNull(svc.getTimezoneId(pkg))
        assertEquals(0L, svc.getOffsetMs(pkg))
    }

    @Test
    fun `clearClock on unknown package does not throw`() {
        VirtualClockService().clearClock("com.not.configured")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listSessions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listSessions returns empty map when no sessions configured`() {
        assertTrue(VirtualClockService().listSessions().isEmpty())
    }

    @Test
    fun `listSessions returns all configured packages`() {
        val svc = VirtualClockService()
        svc.setOffset("com.game.a", 1_000L)
        svc.setFixedTime("com.game.b", 2_000L)

        val sessions = svc.listSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.containsKey("com.game.a"))
        assertEquals(2_000L, sessions["com.game.b"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Independence across packages
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `virtual clocks for different packages are independent`() {
        val svc  = VirtualClockService()
        val pkgA = "com.game.a"
        val pkgB = "com.game.b"

        svc.setFixedTime(pkgA, 1_000L)
        svc.setFixedTime(pkgB, 2_000L)

        assertEquals(1_000L, svc.currentTimeMillis(pkgA))
        assertEquals(2_000L, svc.currentTimeMillis(pkgB))
    }
}
