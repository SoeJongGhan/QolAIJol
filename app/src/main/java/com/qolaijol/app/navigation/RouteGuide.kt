// RouteGuide - turns GPS fixes along a WalkingRoute into spoken instructions
//
// For every location fix: find where on the route the user is, then decide what to say:
//   "In 40 meters, turn left." -> "Turn left." -> "Continue straight for 300 meters."
//   "In 20 meters, stairs going up." -> "Stairs going up. Careful."
//   off the route twice in a row -> reroute;  walking backwards -> "Turn around";  at the end ->
//   "You have arrived". Plain Kotlin with no clock or Android types, so it is unit tested with
//   simulated walks.

package com.qolaijol.app.navigation

import kotlin.math.max

class RouteGuide(private val route: WalkingRoute) {

  sealed interface Event {
    data class Say(val text: String, val urgent: Boolean = false) : Event

    /** The user left the route; the caller should build a new one from here. */
    data object Reroute : Event

    data object Arrived : Event
  }

  companion object {
    const val OFF_ROUTE_M = 25.0
    const val ARRIVAL_M = 15.0
    const val PREPARE_M = 40.0
    const val NOW_M = 10.0
    const val WRONG_WAY_M = 20.0
    /** After a turn, say "continue straight for N meters" only if the next thing is this far. */
    private const val STRAIGHT_HINT_M = 80.0
    private const val SEARCH_BACK_M = 40.0
    private const val SEARCH_AHEAD_M = 150.0
  }

  var progressM = 0.0
    private set

  val remainingM: Double
    get() = (route.lengthM - progressM).coerceAtLeast(0.0)

  val destinationName: String
    get() = route.destinationName

  private val prepared = HashSet<Int>()
  private val announced = HashSet<Int>()
  private var passedIndex = -1
  private var maxProgress = 0.0
  private var offRouteFixes = 0
  private var wrongWayWarned = false
  private var arrived = false

  /** First announcement: summary plus what comes first. */
  fun start(): Event.Say {
    val summary =
        "Route to ${route.destinationName}: ${NavPhrases.distance(route.lengthM)}, " +
            "about ${NavPhrases.minutes(route.totalDurationS)}."
    return Event.Say("$summary ${nextInstruction(0.0)}")
  }

  /** "Next: in 40 meters, turn left." — for the "how far is it" question. */
  fun status(): String =
      "${NavPhrases.distance(remainingM)} left to ${route.destinationName}. " +
          "Next: ${nextInstruction(progressM).replaceFirstChar { it.lowercase() }}"

  fun onLocation(position: LatLon, accuracyM: Float = 10f): List<Event> {
    if (arrived || route.points.size < 2) return emptyList()
    val (distanceToRoute, s) = project(position)

    if (distanceToRoute > max(OFF_ROUTE_M, accuracyM.toDouble())) {
      offRouteFixes++
      return if (offRouteFixes >= 2) {
        offRouteFixes = 0
        listOf(Event.Say("You are off the route. Building a new one."), Event.Reroute)
      } else {
        emptyList()
      }
    }
    offRouteFixes = 0

    val events = ArrayList<Event>()
    if (route.lengthM - s <= ARRIVAL_M || Geo.distanceM(position, route.points.last()) <= ARRIVAL_M) {
      arrived = true
      progressM = route.lengthM
      events += Event.Say("You have arrived at ${route.destinationName}.")
      events += Event.Arrived
      return events
    }

    if (s < maxProgress - WRONG_WAY_M && !wrongWayWarned) {
      wrongWayWarned = true
      events += Event.Say("You seem to be going the wrong way. Turn around.", urgent = true)
    }
    if (s >= maxProgress) wrongWayWarned = false
    maxProgress = max(maxProgress, s)
    progressM = s

    // A feature that is now behind the user: optionally tell how far it is to the next thing.
    while (passedIndex + 1 < route.features.size && route.features[passedIndex + 1].atM < s - 3) {
      passedIndex++
      val nextAt = route.features.getOrNull(passedIndex + 1)?.atM ?: route.lengthM
      if (passedIndex in announced && nextAt - s >= STRAIGHT_HINT_M) {
        events += Event.Say("Continue straight for ${NavPhrases.distance(nextAt - s)}.")
      }
    }

    val index = passedIndex + 1
    val feature = route.features.getOrNull(index) ?: return events
    val d = feature.atM - s
    if (d <= NOW_M && index !in announced) {
      announced += index
      prepared += index
      events += Event.Say(NavPhrases.now(feature), urgent = feature is RouteFeature.Passage)
    } else if (d <= PREPARE_M && index !in prepared) {
      prepared += index
      events += Event.Say(NavPhrases.prepare(feature, d))
    }
    return events
  }

  private fun nextInstruction(s: Double): String {
    val next = route.features.firstOrNull { it.atM > s + 3 }
    return if (next == null) {
      "Go straight for ${NavPhrases.distance(route.lengthM - s)} to the end of the route."
    } else {
      val d = next.atM - s
      if (d <= PREPARE_M) NavPhrases.prepare(next, d)
      else "Go straight for ${NavPhrases.distance(d)}, then ${NavPhrases.feature(next)}."
    }
  }

  /**
   * Closest point of the route to [p], searched near the current progress first (so a route that
   * passes the same street twice doesn't make the user "jump"). Returns (distance, position along).
   */
  private fun project(p: LatLon): Pair<Double, Double> {
    val near = projectBetween(p, progressM - SEARCH_BACK_M, progressM + SEARCH_AHEAD_M)
    if (near.first <= OFF_ROUTE_M) return near
    val anywhere = projectBetween(p, 0.0, route.lengthM)
    return if (anywhere.first < near.first) anywhere else near
  }

  private fun projectBetween(p: LatLon, fromM: Double, toM: Double): Pair<Double, Double> {
    val origin = p
    var best = Double.MAX_VALUE to progressM
    val pts = route.points
    for (i in 1 until pts.size) {
      if (route.cumulative[i] < fromM || route.cumulative[i - 1] > toM) continue
      val (ax, ay) = Geo.toLocal(origin, pts[i - 1])
      val (bx, by) = Geo.toLocal(origin, pts[i])
      val dx = bx - ax
      val dy = by - ay
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
      val tc = t.coerceIn(0.0, 1.0)
      val cx = ax + tc * dx
      val cy = ay + tc * dy
      val dist = kotlin.math.sqrt(cx * cx + cy * cy)
      if (dist < best.first) {
        best = dist to (route.cumulative[i - 1] + tc * (route.cumulative[i] - route.cumulative[i - 1]))
      }
    }
    return best
  }
}
