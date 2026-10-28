// DangerAnalyzer - how important is a detected object?
//
// Base priority comes from the object class; an object in the CENTER (the walking path) is one
// level more important. Classes not listed are not announced at all. Covers the class names of
// both supported models (COCO YOLO26n and the fine-tuned QolAIJol YOLO26n).
//
// Deliberately NOT announced (shown on screen only):
// - stairs, traffic light: the research evaluation (SANPO test AP50 0.08 / 0.05) says these must
//   not be offered to users on the basis of the current models;
// - crosswalk: a false "crosswalk ahead" could lead the user onto the road.

package com.qolaijol.app.decision

import com.qolaijol.app.vision.Detection
import com.qolaijol.app.vision.HorizontalPosition

enum class Priority {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  fun raised(): Priority = entries[minOf(ordinal + 1, entries.lastIndex)]
}

object DangerAnalyzer {

  private val classPriority: Map<String, Priority> =
      mapOf(
          // Fine-tuned QolAIJol classes.
          "vehicle" to Priority.HIGH,
          "rider" to Priority.HIGH,
          "pedestrian" to Priority.MEDIUM,
          "animal" to Priority.MEDIUM,
          "obstacle" to Priority.LOW,
          "pole" to Priority.LOW,
          // COCO vehicles: can move fast and hurt.
          "car" to Priority.HIGH,
          "bus" to Priority.HIGH,
          "truck" to Priority.HIGH,
          "motorcycle" to Priority.HIGH,
          "bicycle" to Priority.HIGH,
          "train" to Priority.HIGH,
          // COCO people and animals.
          "person" to Priority.MEDIUM,
          "dog" to Priority.MEDIUM,
          "horse" to Priority.MEDIUM,
          "stop sign" to Priority.MEDIUM,
          // COCO static obstacles: only matter when they are in the way.
          "bench" to Priority.LOW,
          "chair" to Priority.LOW,
          "couch" to Priority.LOW,
          "potted plant" to Priority.LOW,
          "dining table" to Priority.LOW,
          "fire hydrant" to Priority.LOW,
          "parking meter" to Priority.LOW,
          "suitcase" to Priority.LOW,
      )

  /**
   * Classes that are never spoken, not even in answers to "what is ahead?": too unreliable (stairs,
   * traffic light) or misleading if wrong (crosswalk). See the header comment.
   */
  val NEVER_SPOKEN: Set<String> = setOf("stairs", "traffic light", "crosswalk")

  /** Class priority ignoring position, or null if the class is never announced as a warning. */
  fun basePriority(className: String): Priority? = classPriority[className]

  /** Priority of [detection], or null if this kind of object is never announced. */
  fun priorityOf(detection: Detection): Priority? {
    val base = classPriority[detection.className] ?: return null
    return if (detection.horizontalPosition == HorizontalPosition.CENTER) base.raised() else base
  }
}
