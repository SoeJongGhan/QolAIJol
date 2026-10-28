// WalkingRoute - a pedestrian route as a polyline plus the turns and special places along it
//
// 2GIS returns walking routes without turn-by-turn maneuvers (only "start" and "finish", checked
// with real requests in Phase 6), but with the full geometry split into parts that carry a style
// (normal, stairway, overgroundway, ...) and a level. So the turns are computed here from the
// geometry, and stairs / overpasses / underpasses become their own announcements.

package com.qolaijol.app.navigation

import kotlin.math.abs

/** One piece of the route geometry as 2GIS returns it. */
data class RoutePart(val points: List<LatLon>, val style: String, val zlevel: String)

sealed interface RouteFeature {
  /** Distance from the route start, metres. */
  val atM: Double

  data class Turn(override val atM: Double, val angleDeg: Double) : RouteFeature

  /** Stairs, overpass or underpass starting at [atM]. [up]: going up (null if unknown). */
  data class Passage(override val atM: Double, val kind: Kind, val up: Boolean?) : RouteFeature {
    enum class Kind {
      STAIRS,
      OVERPASS,
      UNDERPASS,
    }
  }
}

class WalkingRoute(
    parts: List<RoutePart>,
    val destinationName: String,
    val totalDistanceM: Int,
    val totalDurationS: Int,
) {
  companion object {
    /** Heading changes smaller than this are "keep going straight". */
    const val MIN_TURN_DEG = 30.0
    /** Headings are measured over this distance before/after a vertex to ignore GPS-scale wiggles. */
    private const val LOOK_M = 12.0
    /** Turns closer than this merge into one. */
    private const val MERGE_M = 15.0
  }

  /** All points in order, consecutive duplicates removed. */
  val points: List<LatLon>
  /** cumulative[i] = distance along the route from points[0] to points[i]. */
  val cumulative: DoubleArray
  val lengthM: Double
  /** Turns and passages ordered by position along the route. */
  val features: List<RouteFeature>

  init {
    val pts = ArrayList<LatLon>()
    val partStarts = ArrayList<Pair<Int, RoutePart>>() // index of the part's first point in pts
    for (part in parts) {
      if (part.points.isEmpty()) continue
      val startIndex =
          if (pts.isNotEmpty() && Geo.distanceM(pts.last(), part.points.first()) < 0.5) pts.lastIndex
          else pts.size
      partStarts += startIndex to part
      for (p in part.points) if (pts.isEmpty() || Geo.distanceM(pts.last(), p) >= 0.5) pts += p
    }
    points = pts
    cumulative = DoubleArray(pts.size)
    for (i in 1 until pts.size) cumulative[i] = cumulative[i - 1] + Geo.distanceM(pts[i - 1], pts[i])
    lengthM = cumulative.lastOrNull() ?: 0.0
    features = (computeTurns() + computePassages(partStarts)).sortedBy { it.atM }
  }

  /** Point at [s] metres along the route. */
  fun pointAt(s: Double): LatLon {
    if (points.isEmpty()) error("Empty route")
    val d = s.coerceIn(0.0, lengthM)
    var i = cumulative.indexOfFirst { it >= d }.coerceAtLeast(1)
    if (i >= points.size) i = points.lastIndex
    val segment = cumulative[i] - cumulative[i - 1]
    if (segment <= 0) return points[i]
    val t = (d - cumulative[i - 1]) / segment
    val a = points[i - 1]
    val b = points[i]
    return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
  }

  private fun computeTurns(): List<RouteFeature.Turn> {
    val turns = ArrayList<RouteFeature.Turn>()
    for (i in 1 until points.lastIndex) {
      val s = cumulative[i]
      if (s < LOOK_M || lengthM - s < LOOK_M) continue
      val before = pointAt(s - LOOK_M)
      val after = pointAt(s + LOOK_M)
      val angle = Geo.turnDeg(Geo.bearingDeg(before, points[i]), Geo.bearingDeg(points[i], after))
      if (abs(angle) < MIN_TURN_DEG) continue
      val last = turns.lastOrNull()
      if (last != null && s - last.atM < MERGE_M) {
        // Same corner seen from two vertices: keep the stronger reading.
        if (abs(angle) > abs(last.angleDeg)) turns[turns.lastIndex] = RouteFeature.Turn(s, angle)
      } else {
        turns += RouteFeature.Turn(s, angle)
      }
    }
    return turns
  }

  /**
   * One announcement per block of consecutive same-kind parts (a staircase is often several parts).
   * Up/down compares the level before the block with the level right after it: real example
   * normal -> [stairway normal, stairway positive] -> overpass positive = stairs going up.
   */
  private fun computePassages(partStarts: List<Pair<Int, RoutePart>>): List<RouteFeature.Passage> {
    val result = ArrayList<RouteFeature.Passage>()
    var levelBefore = 0
    var k = 0
    while (k < partStarts.size) {
      val (index, part) = partStarts[k]
      val kind = kindOf(part.style)
      if (kind == null) {
        levelBefore = level(part.zlevel)
        k++
        continue
      }
      var end = k
      while (end + 1 < partStarts.size && kindOf(partStarts[end + 1].second.style) == kind) end++
      val levelAfter =
          level((partStarts.getOrNull(end + 1) ?: partStarts[end]).second.zlevel)
      val up =
          when {
            levelAfter > levelBefore -> true
            levelAfter < levelBefore -> false
            else -> null
          }
      result += RouteFeature.Passage(cumulative[index.coerceIn(0, cumulative.lastIndex)], kind, up)
      levelBefore = level(partStarts[end].second.zlevel)
      k = end + 1
    }
    return result
  }

  private fun kindOf(style: String): RouteFeature.Passage.Kind? =
      when {
        style.contains("stair") -> RouteFeature.Passage.Kind.STAIRS
        style.contains("overground") -> RouteFeature.Passage.Kind.OVERPASS
        style.contains("underground") -> RouteFeature.Passage.Kind.UNDERPASS
        else -> null
      }

  private fun level(z: String) =
      when {
        z.contains("positive") -> 1
        z.contains("negative") -> -1
        else -> 0
      }
}
