// Data class for a virtual GPS location (latitude, longitude, altitude) used with VirtualGpsService.
// Datenklasse für eine virtuelle GPS-Position (Breite, Länge, Höhe) zur Verwendung mit dem VirtualGpsService.

package com.smapifan.androidmodder.model

/**
 * A virtual GPS location for use with
 * [com.smapifan.androidmodder.service.VirtualGpsService].
 *
 * Represents an isolated location coordinate that is completely independent of
 * the real device GPS.  Setting a virtual location does **not** affect the
 * system location or any other app.
 *
 * @param latitude       WGS-84 latitude in decimal degrees (range −90 .. 90)
 * @param longitude      WGS-84 longitude in decimal degrees (range −180 .. 180)
 * @param altitudeMeters altitude above sea level in metres (default `0.0`)
 * @param accuracyMeters horizontal accuracy radius in metres (default `10.0`)
 * @param label          optional human-readable label, e.g. `"Berlin, Germany"`
 */
data class VirtualLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    val accuracyMeters: Float = 10.0f,
    val label: String = ""
)
