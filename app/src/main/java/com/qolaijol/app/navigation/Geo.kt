// Geo - small geometry helpers for walking navigation (plain Kotlin, unit tested)
//
// Distances are short (a walking route), so a local flat projection around a reference point is
// accurate to well under a metre and keeps the maths simple.

package com.qolaijol.app.navigation

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
  private const val EARTH_RADIUS_M = 6_371_000.0

  fun distanceM(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
  }

  /** Compass bearing from [a] to [b], 0..360 degrees (0 = north, 90 = east). */
  fun bearingDeg(a: LatLon, b: LatLon): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
  }

  /** Signed turn from [fromBearing] to [toBearing] in -180..180 (positive = right). */
  fun turnDeg(fromBearing: Double, toBearing: Double): Double {
    var d = (toBearing - fromBearing) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
  }

  /** x = east, y = north, in metres around [origin]. */
  fun toLocal(origin: LatLon, p: LatLon): Pair<Double, Double> {
    val x = Math.toRadians(p.lon - origin.lon) * EARTH_RADIUS_M * cos(Math.toRadians(origin.lat))
    val y = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M
    return x to y
  }

  /** Point [distanceM] metres from [from] in direction [bearingDeg] (for tests and simulation). */
  fun offset(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
    val b = bearingDeg * PI / 180
    val dLat = distanceM * cos(b) / EARTH_RADIUS_M
    val dLon = distanceM * sin(b) / (EARTH_RADIUS_M * cos(Math.toRadians(from.lat)))
    return LatLon(from.lat + Math.toDegrees(dLat), from.lon + Math.toDegrees(dLon))
  }
}
