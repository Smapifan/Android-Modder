// Provides an isolated virtual GPS location per guest-app session without affecting the real device GPS.
// Stellt eine isolierte virtuelle GPS-Position pro Gast-App-Sitzung bereit, ohne die echte Geräte-GPS zu beeinflussen.

package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.VirtualLocation

// ═════════════════════════════════════════════════════════════════════════════
//  VirtualGpsService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Provides an isolated GPS location per guest-app session.
 *
 * Each virtual session can have its own GPS coordinates, completely independent
 * of the device's real GPS.  Setting a virtual location here does **not** change
 * Android's `LocationManager` or affect any other app.
 *
 * ## Use cases
 *
 * - Spoofing location in location-based games without triggering Mock Location
 *   detection on the system level.
 * - Testing location-sensitive features with different coordinates.
 * - Running an app as if it were in a different city or country.
 *
 * ## How it integrates with the launcher
 *
 * Before the launcher calls `am start`, it can write the virtual location into
 * the external-storage mod-launcher script (`mod_launcher.sh`) so that the
 * injected smali bootstrap can intercept `LocationManager.getLastKnownLocation()`
 * and return the virtual coordinates instead of the real ones.
 *
 * Without the smali hook, the virtual location can still be used for
 * workspace-based games that store a location in their save file (the launcher
 * patches the save file with the virtual coordinates before launch).
 */
class VirtualGpsService {

    private val sessionLocations = mutableMapOf<String, VirtualLocation>()

    // ─────────────────────────────────────────────────────────────────────────
    //  Session management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets a virtual GPS location for [packageName].
     *
     * Replaces any previously set location for the same package.
     */
    fun setLocation(packageName: String, location: VirtualLocation) {
        sessionLocations[packageName] = location
    }

    /**
     * Returns the virtual GPS location for [packageName], or `null` if none is set.
     */
    fun getLocation(packageName: String): VirtualLocation? = sessionLocations[packageName]

    /**
     * Clears the virtual GPS location for [packageName].
     *
     * After this call the real device GPS is used for that package again.
     */
    fun clearLocation(packageName: String) {
        sessionLocations.remove(packageName)
    }

    /**
     * Returns `true` if a virtual GPS location is currently set for [packageName].
     */
    fun hasLocation(packageName: String): Boolean = sessionLocations.containsKey(packageName)

    /**
     * Returns an immutable snapshot of all active virtual GPS sessions.
     */
    fun listSessions(): Map<String, VirtualLocation> = sessionLocations.toMap()

    // ─────────────────────────────────────────────────────────────────────────
    //  NMEA helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates an NMEA GGA sentence for the virtual location of [packageName].
     *
     * Useful for apps that consume raw NMEA data from the GPS hardware.
     * Returns `null` if no virtual location is set for [packageName].
     *
     * Example output:
     * ```
     * $GPGGA,000000.00,5232.43860,N,01322.72340,E,1,08,1.0,34.0,M,0.0,M,,*XX
     * ```
     */
    fun toNmeaGga(packageName: String): String? {
        val loc = sessionLocations[packageName] ?: return null
        return buildNmeaGga(loc)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNmeaGga(loc: VirtualLocation): String {
        val absLat  = Math.abs(loc.latitude)
        val latDeg  = absLat.toInt()
        val latMin  = (absLat - latDeg) * 60.0
        val latHemi = if (loc.latitude >= 0) "N" else "S"

        val absLng  = Math.abs(loc.longitude)
        val lngDeg  = absLng.toInt()
        val lngMin  = (absLng - lngDeg) * 60.0
        val lngHemi = if (loc.longitude >= 0) "E" else "W"

        val body = "\$GPGGA,000000.00,%02d%08.5f,%s,%03d%08.5f,%s,1,08,1.0,%.1f,M,0.0,M,,".format(
            latDeg, latMin, latHemi, lngDeg, lngMin, lngHemi, loc.altitudeMeters
        )
        val checksum = body.drop(1).fold(0) { acc, c -> acc xor c.code }
        return "%s*%02X".format(body, checksum)
    }
}
