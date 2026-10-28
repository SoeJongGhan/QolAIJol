// SceneAnalyzer - answers "what is ahead / left / right?" from the latest detections
//
// Works offline from YOLO results only. Counts objects per zone ("Ahead: bus, 2 people.") and never
// claims more than the detector can know: an empty zone is "nothing detected", not "clear". Classes
// the app never announces (stairs, traffic lights, crosswalks) are left out here too. Plain Kotlin,
// unit tested on the JVM.

package com.qolaijol.app.decision

import com.qolaijol.app.vision.Detection
import com.qolaijol.app.vision.HorizontalPosition
import com.qolaijol.app.voice.SceneZone

object SceneAnalyzer {

  const val MIN_CONFIDENCE = 0.5f
  private const val MAX_GROUPS = 4

  fun answer(zone: SceneZone, detections: List<Detection>): String {
    val reliable =
        detections.filter {
          it.confidence >= MIN_CONFIDENCE && it.className !in DangerAnalyzer.NEVER_SPOKEN
        }
    return when (zone) {
      SceneZone.AHEAD -> zoneSentence(reliable, HorizontalPosition.CENTER)
      SceneZone.LEFT -> zoneSentence(reliable, HorizontalPosition.LEFT)
      SceneZone.RIGHT -> zoneSentence(reliable, HorizontalPosition.RIGHT)
      SceneZone.ALL -> {
        if (reliable.isEmpty()) return "Nothing detected."
        listOf(HorizontalPosition.CENTER, HorizontalPosition.LEFT, HorizontalPosition.RIGHT)
            .mapNotNull { position ->
              summary(reliable.filter { it.horizontalPosition == position })?.let {
                "${label(position)}: $it."
              }
            }
            .joinToString(" ")
      }
    }
  }

  /** "Ahead", "On the left", "On the right". */
  private fun label(position: HorizontalPosition): String =
      EnglishPhrases.positionWord(position).replaceFirstChar { it.uppercase() }

  private fun zoneSentence(detections: List<Detection>, position: HorizontalPosition): String {
    val text = summary(detections.filter { it.horizontalPosition == position })
    return if (text == null) {
      "Nothing detected ${EnglishPhrases.positionWord(position)}."
    } else {
      "${label(position)}: $text."
    }
  }

  /** "bus, 2 people" — most important classes first; null if nothing. */
  private fun summary(detections: List<Detection>): String? {
    if (detections.isEmpty()) return null
    return detections
        .groupBy { it.className }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<Detection>>> {
                  DangerAnalyzer.basePriority(it.key)?.ordinal ?: -1
                }
                .thenByDescending { it.value.size },
        )
        .take(MAX_GROUPS)
        .joinToString(", ") { (className, group) -> EnglishPhrases.countPhrase(className, group.size) }
  }
}
