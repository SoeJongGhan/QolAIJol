// Detection - YOLO output types
//
// Plain data only: no decision logic here. Coordinates are normalized to 0..1 relative to the
// camera frame, so consumers don't need to know the frame or model resolution.

package com.qolaijol.app.vision

/** Axis-aligned box, normalized to 0..1 of the frame width/height. */
data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float
    get() = right - left

  val height: Float
    get() = bottom - top

  val centerX: Float
    get() = (left + right) / 2f

  val centerY: Float
    get() = (top + bottom) / 2f

  fun iou(other: BoundingBox): Float {
    val ix = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val iy = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    val intersection = ix * iy
    val union = width * height + other.width * other.height - intersection
    return if (union > 0f) intersection / union else 0f
  }
}

/**
 * Coarse horizontal direction of an object, from the box center. A first approximation only: it
 * says where the object is in the camera image, not its physical bearing or distance.
 */
enum class HorizontalPosition {
  LEFT,
  CENTER,
  RIGHT;

  companion object {
    const val LEFT_EDGE = 0.33f
    const val RIGHT_EDGE = 0.66f

    fun of(centerX: Float): HorizontalPosition =
        when {
          centerX < LEFT_EDGE -> LEFT
          centerX > RIGHT_EDGE -> RIGHT
          else -> CENTER
        }
  }
}

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: BoundingBox,
) {
  val horizontalPosition: HorizontalPosition
    get() = HorizontalPosition.of(centerX)

  val centerX: Float
    get() = boundingBox.centerX

  val centerY: Float
    get() = boundingBox.centerY

  val width: Float
    get() = boundingBox.width

  val height: Float
    get() = boundingBox.height
}

/** Everything YOLO produced for one frame, plus timing for performance tuning. */
data class DetectionResult(
    val detections: List<Detection>,
    val frameWidth: Int,
    val frameHeight: Int,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val frameTimestampUs: Long,
)
