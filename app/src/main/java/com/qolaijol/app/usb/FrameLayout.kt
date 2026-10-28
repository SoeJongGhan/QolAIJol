// FrameLayout - the real width and height of a USB webcam frame, worked out from its pixels
//
// The UVC library sizes each NV21 frame buffer for the resolution it *asked* for, but fills it with
// the resolution the webcam *chose* while negotiating, and some webcams pick another mode without
// saying so. Read at the asked-for width, a narrower frame shows side-by-side copies of a squashed
// picture with noise below them. The byte count can't tell the two apart, the pixels can: at the
// right width, each row lies right above the next one in the scene, so neighbouring rows are
// nearly the same; at a wrong width they are several rows apart or shifted sideways. The height is
// where the luma rows end and the chroma bytes begin. Plain Kotlin, unit tested on the JVM.

package com.qolaijol.app.usb

import kotlin.math.abs

object FrameLayout {

  data class Size(val width: Int, val height: Int) {
    /** Bytes of an NV21 frame of this size. */
    val nv21Bytes: Int
      get() = width * height * 3 / 2
  }

  // Common webcam sizes, in case the webcam's own list is missing one.
  val COMMON_SIZES =
      listOf(
          Size(640, 480),
          Size(320, 240),
          Size(160, 120),
          Size(352, 288),
          Size(176, 144),
          Size(640, 360),
          Size(800, 600),
          Size(1024, 768),
          Size(1280, 720),
          Size(1280, 960),
          Size(1920, 1080),
      )

  /** Rows compared when measuring how well a width fits. */
  private const val SAMPLE_ROWS = 48
  /** Every n-th pixel of a row is compared; plenty for a reliable average. */
  private const val SAMPLE_STEP = 3
  /** The preferred width is kept unless another one fits clearly better (flat scenes, noise). */
  private const val KEEP_PREFERRED_MARGIN = 1.15
  /** Luma -> chroma at the true height changes the bytes this much more than an ordinary row. */
  private const val CHROMA_JUMP = 2.0

  /**
   * The size of the NV21 frame in the first [length] bytes of [nv21], among the [candidates] (the
   * webcam's own sizes; common sizes are added) that fit in [length]. [preferred] (the size found
   * before, else the one the library reports) wins ties, so flat scenes don't change it. Null if no
   * candidate fits.
   */
  fun detect(nv21: ByteArray, length: Int, preferred: Size?, candidates: List<Size>): Size? {
    val fitting =
        (listOfNotNull(preferred) + candidates + COMMON_SIZES).distinct().filter {
          it.width > 0 && it.height > 0 && it.nv21Bytes <= length
        }
    if (fitting.isEmpty()) return null

    val scores =
        fitting.groupBy { it.width }.mapValues { (width, sizes) ->
          rowDifference(nv21, width, minOf(SAMPLE_ROWS, sizes.minOf { it.height }))
        }
    val best = scores.minByOrNull { it.value }!!
    val preferredScore = preferred?.let { scores[it.width] }
    val width =
        if (
            preferred != null &&
                preferredScore != null &&
                preferredScore <= best.value * KEEP_PREFERRED_MARGIN
        ) {
          preferred.width
        } else {
          best.key
        }

    val heights = fitting.filter { it.width == width }.map { it.height }.distinct().sorted()
    val height =
        when {
          preferred != null && preferred.width == width && preferred.height in heights ->
              preferred.height
          heights.size == 1 -> heights[0]
          else -> chromaStart(nv21, width, heights, scores.getValue(width))
        }
    return Size(width, height)
  }

  /** Mean difference between vertically neighbouring luma bytes if rows are [width] long. */
  fun rowDifference(nv21: ByteArray, width: Int, rows: Int): Double {
    var sum = 0L
    var count = 0
    for (y in 0 until rows - 1) {
      val row = y * width
      var x = 0
      while (x < width) {
        sum += abs((nv21[row + x].toInt() and 0xFF) - (nv21[row + width + x].toInt() and 0xFF))
        count++
        x += SAMPLE_STEP
      }
    }
    return if (count == 0) Double.MAX_VALUE else sum.toDouble() / count
  }

  /**
   * The first of [heights] (ascending) where the bytes change from luma to chroma: the row there
   * differs from the one above far more than ordinary rows ([rowScore]) do. Else the largest.
   */
  private fun chromaStart(nv21: ByteArray, width: Int, heights: List<Int>, rowScore: Double): Int {
    for (height in heights) {
      var sum = 0L
      var count = 0
      val last = (height - 1) * width
      var x = 0
      while (x < width) {
        sum += abs((nv21[last + x].toInt() and 0xFF) - (nv21[last + width + x].toInt() and 0xFF))
        count++
        x += SAMPLE_STEP
      }
      if (sum.toDouble() / count >= CHROMA_JUMP * maxOf(rowScore, 1.0)) return height
    }
    return heights.last()
  }
}
