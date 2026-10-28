// EnglishPhrases - spoken object names and alert sentences
//
// The detector reports class names from the model's labels file; everything the user hears or reads
// goes through here. Covers both supported models: the 80 COCO classes of the base YOLO26n and the
// 9 QolAIJol classes of the fine-tuned model. Most labels are already plain English and are used
// as they are; only the ones that sound odd aloud are renamed. Plain Kotlin, unit tested on the JVM.

package com.qolaijol.app.decision

import com.qolaijol.app.vision.HorizontalPosition

object EnglishPhrases {

  private val renamed: Map<String, String> =
      mapOf(
          // QolAIJol fine-tuned classes.
          "pedestrian" to "person",
          "rider" to "cyclist",
          // COCO classes.
          "sports ball" to "ball",
          "dining table" to "table",
          "tv" to "TV",
          "mouse" to "computer mouse",
          "remote" to "remote control",
          "cell phone" to "phone",
          "hair drier" to "hair dryer",
      )

  // Plurals the -s / -es / -ies rule gets wrong.
  private val irregularPlurals: Map<String, String> =
      mapOf(
          "person" to "people",
          "knife" to "knives",
          "sheep" to "sheep",
          "skis" to "skis",
          "stairs" to "stairs",
          "scissors" to "scissors",
          "broccoli" to "broccoli",
          "computer mouse" to "computer mice",
      )

  /** Spoken name of a detected class; unknown classes fall back to the model's own name. */
  fun objectName(className: String): String = renamed[className] ?: className

  /** "on the left", "ahead", "on the right" — for on-screen lists. */
  fun positionWord(position: HorizontalPosition): String =
      when (position) {
        HorizontalPosition.LEFT -> "on the left"
        HorizontalPosition.CENTER -> "ahead"
        HorizontalPosition.RIGHT -> "on the right"
      }

  /** Spoken alert: "Person ahead.", "Car on the left.", "Pole on the right." */
  fun alert(className: String, position: HorizontalPosition): String {
    val name = objectName(className).replaceFirstChar { it.uppercase() }
    return "$name ${positionWord(position)}."
  }

  /** "bus", "2 people", "5 cars". */
  fun countPhrase(className: String, count: Int): String {
    val name = objectName(className)
    return if (count == 1) name else "$count ${plural(name)}"
  }

  fun plural(noun: String): String {
    irregularPlurals[noun]?.let { return it }
    return when {
      noun.endsWith("s") || noun.endsWith("x") || noun.endsWith("z") ||
          noun.endsWith("ch") || noun.endsWith("sh") -> "${noun}es"
      noun.length > 1 && noun.endsWith("y") && noun[noun.length - 2] !in "aeiou" ->
          "${noun.dropLast(1)}ies"
      else -> "${noun}s"
    }
  }
}
