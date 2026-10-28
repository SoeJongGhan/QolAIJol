// PreviewRenderer - draws raw camera frames and detection boxes onto the preview Surface
//
// Replaces the sample's HEVC Surface decoder now that the stream delivers raw I420 frames. Uses one
// reused Bitmap and pixel array, and is throttled so the preview never costs more than needed.

package com.qolaijol.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.qolaijol.app.decision.EnglishPhrases
import com.qolaijol.app.utils.i420PixelArgb
import com.qolaijol.app.vision.CameraFrame
import com.qolaijol.app.vision.Detection
import kotlin.math.roundToInt

class PreviewRenderer(private val minFrameIntervalMs: Long = PREVIEW_INTERVAL_MS) {

  companion object {
    private const val TAG = "QolAIJol/Camera"

    /** ~20 fps preview; the stream itself runs at 24 fps. */
    const val PREVIEW_INTERVAL_MS = 50L
  }

  // Guards the Surface: set from the main thread, drawn on from the frame thread.
  private val lock = Any()
  private var surface: Surface? = null
  private var bitmap: Bitmap? = null
  private var pixels = IntArray(0)
  private var lastRenderMs = 0L

  private val destination = RectF()
  private val boxPaint =
      Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.GREEN
        isAntiAlias = true
      }
  private val labelBackgroundPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
  private val labelPaint =
      Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
      }

  fun setSurface(newSurface: Surface?) {
    synchronized(lock) {
      surface = newSurface
      lastRenderMs = 0L
    }
  }

  /** Draws [frame] with [detections] if the preview interval has elapsed. Frame thread only. */
  fun render(frame: CameraFrame, detections: List<Detection>) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastRenderMs < minFrameIntervalMs) return
    synchronized(lock) {
      val target = surface ?: return
      if (!target.isValid) return
      lastRenderMs = now

      val frameBitmap = toBitmap(frame)
      val canvas =
          try {
            target.lockHardwareCanvas()
          } catch (e: Exception) {
            // Surface released between the validity check and the lock; the next frame retries.
            Log.w(TAG, "Preview surface unavailable: ${e.message}")
            return
          }
      try {
        draw(canvas, frameBitmap, detections)
      } finally {
        target.unlockCanvasAndPost(canvas)
      }
    }
  }

  private fun toBitmap(frame: CameraFrame): Bitmap {
    val width = frame.width
    val height = frame.height
    val current = bitmap
    val target =
        if (current != null && current.width == width && current.height == height) {
          current
        } else {
          current?.recycle()
          pixels = IntArray(width * height)
          Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
    val data = frame.i420
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until width) {
        pixels[row + x] = i420PixelArgb(data, width, height, x, y)
      }
    }
    target.setPixels(pixels, 0, width, 0, 0, width, height)
    return target
  }

  private fun draw(canvas: Canvas, frameBitmap: Bitmap, detections: List<Detection>) {
    canvas.drawColor(Color.BLACK)
    // Fit-center the frame into the surface.
    val scale =
        minOf(
            canvas.width.toFloat() / frameBitmap.width,
            canvas.height.toFloat() / frameBitmap.height,
        )
    val drawnWidth = frameBitmap.width * scale
    val drawnHeight = frameBitmap.height * scale
    val left = (canvas.width - drawnWidth) / 2f
    val top = (canvas.height - drawnHeight) / 2f
    destination.set(left, top, left + drawnWidth, top + drawnHeight)
    canvas.drawBitmap(frameBitmap, null, destination, null)

    for (detection in detections) {
      val box = detection.boundingBox
      val boxLeft = left + box.left * drawnWidth
      val boxTop = top + box.top * drawnHeight
      canvas.drawRect(
          boxLeft,
          boxTop,
          left + box.right * drawnWidth,
          top + box.bottom * drawnHeight,
          boxPaint,
      )
      val label =
          "${EnglishPhrases.objectName(detection.className)} " +
              "${(detection.confidence * 100).roundToInt()}%"
      val labelWidth = labelPaint.measureText(label)
      val labelTop = (boxTop - labelPaint.textSize - 8f).coerceAtLeast(top)
      canvas.drawRect(
          boxLeft,
          labelTop,
          boxLeft + labelWidth + 16f,
          labelTop + labelPaint.textSize + 8f,
          labelBackgroundPaint,
      )
      canvas.drawText(label, boxLeft + 8f, labelTop + labelPaint.textSize - 2f, labelPaint)
    }
  }

  /** Frees the preview bitmap. */
  fun release() {
    synchronized(lock) {
      surface = null
      bitmap?.recycle()
      bitmap = null
      pixels = IntArray(0)
    }
  }
}
