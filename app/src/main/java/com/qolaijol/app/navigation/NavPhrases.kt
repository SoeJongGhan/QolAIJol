// NavPhrases - English wording for walking navigation (plain Kotlin, unit tested)

package com.qolaijol.app.navigation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

object NavPhrases {

  /** Spoken distance, rounded so it sounds natural: "20 meters", "350 meters", "1.2 kilometers". */
  fun distance(meters: Double): String {
    val m = meters.coerceAtLeast(0.0)
    return when {
      m < 100 -> "${(((m / 10).roundToInt()) * 10).coerceAtLeast(10)} meters"
      m < 1000 -> {
        val rounded = ((m / 50).roundToInt()) * 50
        if (rounded >= 1000) "1 kilometer" else "$rounded meters"
      }
      else -> {
        val km = (m / 100).roundToInt() / 10.0
        if (km % 1.0 == 0.0) {
          val whole = km.toInt()
          "$whole ${plural(whole, "kilometer", "kilometers")}"
        } else {
          "$km kilometers"
        }
      }
    }
  }

  fun minutes(seconds: Int): String {
    val min = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
    return "$min ${plural(min, "minute", "minutes")}"
  }

  fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many

  /** "turn left", "bear right", "stairs going up", "pedestrian overpass". */
  fun feature(feature: RouteFeature): String =
      when (feature) {
        is RouteFeature.Turn -> {
          val side = if (feature.angleDeg > 0) "right" else "left"
          when {
            abs(feature.angleDeg) < 60 -> "bear $side"
            abs(feature.angleDeg) <= 135 -> "turn $side"
            else -> "turn sharp $side"
          }
        }
        is RouteFeature.Passage ->
            when (feature.kind) {
              RouteFeature.Passage.Kind.STAIRS ->
                  when (feature.up) {
                    true -> "stairs going up"
                    false -> "stairs going down"
                    null -> "stairs"
                  }
              RouteFeature.Passage.Kind.OVERPASS -> "pedestrian overpass"
              RouteFeature.Passage.Kind.UNDERPASS -> "pedestrian underpass"
            }
      }

  fun prepare(feature: RouteFeature, inMeters: Double): String =
      "In ${distance(inMeters)}, ${feature(feature)}."

  fun now(feature: RouteFeature): String {
    val text = feature(feature).replaceFirstChar { it.uppercase() }
    return if (feature is RouteFeature.Passage) "$text. Careful." else "$text."
  }
}
