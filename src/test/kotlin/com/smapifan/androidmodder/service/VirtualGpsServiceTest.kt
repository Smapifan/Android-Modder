package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.VirtualLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [VirtualGpsService].
 */
class VirtualGpsServiceTest {

    private val pkg    = "com.gram.mergedragons"
    private val berlin = VirtualLocation(52.5200, 13.4050, 34.0, 10.0f, "Berlin")

    // ─────────────────────────────────────────────────────────────────────────
    //  setLocation / getLocation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getLocation returns null when no location set`() {
        assertNull(VirtualGpsService().getLocation(pkg))
    }

    @Test
    fun `setLocation stores location and getLocation returns it`() {
        val svc = VirtualGpsService()
        svc.setLocation(pkg, berlin)
        assertEquals(berlin, svc.getLocation(pkg))
    }

    @Test
    fun `setLocation overwrites previous location`() {
        val svc  = VirtualGpsService()
        val nyc  = VirtualLocation(40.7128, -74.0060, label = "New York")
        svc.setLocation(pkg, berlin)
        svc.setLocation(pkg, nyc)
        assertEquals(nyc, svc.getLocation(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  hasLocation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasLocation returns false before any location is set`() {
        assertFalse(VirtualGpsService().hasLocation(pkg))
    }

    @Test
    fun `hasLocation returns true after setLocation`() {
        val svc = VirtualGpsService()
        svc.setLocation(pkg, berlin)
        assertTrue(svc.hasLocation(pkg))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  clearLocation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearLocation removes the stored location`() {
        val svc = VirtualGpsService()
        svc.setLocation(pkg, berlin)
        svc.clearLocation(pkg)
        assertFalse(svc.hasLocation(pkg))
        assertNull(svc.getLocation(pkg))
    }

    @Test
    fun `clearLocation on unknown package does not throw`() {
        VirtualGpsService().clearLocation("com.not.known")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  listSessions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `listSessions returns empty map when no locations set`() {
        assertTrue(VirtualGpsService().listSessions().isEmpty())
    }

    @Test
    fun `listSessions reflects all set locations`() {
        val svc  = VirtualGpsService()
        val nyc  = VirtualLocation(40.7128, -74.0060)
        svc.setLocation("com.game.a", berlin)
        svc.setLocation("com.game.b", nyc)

        val sessions = svc.listSessions()
        assertEquals(2, sessions.size)
        assertEquals(berlin, sessions["com.game.a"])
        assertEquals(nyc,    sessions["com.game.b"])
    }

    @Test
    fun `listSessions returns immutable snapshot`() {
        val svc      = VirtualGpsService()
        svc.setLocation(pkg, berlin)
        val snapshot = svc.listSessions()
        svc.clearLocation(pkg)
        // snapshot must still contain the original entry
        assertEquals(berlin, snapshot[pkg])
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  toNmeaGga
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `toNmeaGga returns null when no location set`() {
        assertNull(VirtualGpsService().toNmeaGga(pkg))
    }

    @Test
    fun `toNmeaGga returns valid NMEA GGA sentence`() {
        val svc = VirtualGpsService()
        svc.setLocation(pkg, berlin)

        val nmea = svc.toNmeaGga(pkg)
        assertNotNull(nmea)
        assertTrue(nmea.startsWith("\$GPGGA,"), "Sentence must start with \$GPGGA,")
        assertTrue(nmea.contains("*"), "Sentence must contain checksum delimiter *")
    }

    @Test
    fun `toNmeaGga sentence contains correct hemisphere indicators for positive coordinates`() {
        val svc = VirtualGpsService()
        // Berlin: lat N, lon E
        svc.setLocation(pkg, berlin)
        val nmea = svc.toNmeaGga(pkg)!!
        assertTrue(nmea.contains(",N,"), "Northern hemisphere location must have 'N' hemisphere")
        assertTrue(nmea.contains(",E,"), "Eastern hemisphere location must have 'E' hemisphere")
    }

    @Test
    fun `toNmeaGga sentence contains correct hemisphere indicators for negative coordinates`() {
        val svc = VirtualGpsService()
        // Sydney: lat S, lon E
        val sydney = VirtualLocation(-33.8688, 151.2093, label = "Sydney")
        svc.setLocation(pkg, sydney)
        val nmea = svc.toNmeaGga(pkg)!!
        assertTrue(nmea.contains(",S,"), "Southern hemisphere location must have 'S' hemisphere")
        assertTrue(nmea.contains(",E,"), "Eastern hemisphere location must have 'E' hemisphere")
    }

    @Test
    fun `toNmeaGga checksum format is correct`() {
        val svc = VirtualGpsService()
        svc.setLocation(pkg, berlin)
        val nmea = svc.toNmeaGga(pkg)!!
        // Checksum is two uppercase hex digits after the last '*'
        val checksumPart = nmea.substringAfterLast("*")
        assertEquals(2, checksumPart.length)
        assertTrue(checksumPart.all { it.isDigit() || it in 'A'..'F' },
            "Checksum must be two uppercase hex digits, got: $checksumPart")
    }
}
