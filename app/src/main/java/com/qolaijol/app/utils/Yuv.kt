// Yuv - I420 pixel access and YUV -> RGB conversion
//
// Shared by the preview renderer and the YOLO preprocessor. Uses the BT.601 limited-range integer
// approximation, which matches what Android video decoders output.

package com.qolaijol.app.utils

/** Converts one YUV sample to a packed opaque ARGB int. */
@Suppress("NOTHING_TO_INLINE")
inline fun yuvToArgb(y: Int, u: Int, v: Int): Int {
  val c = 298 * (y - 16).coerceAtLeast(0)
  val d = u - 128
  val e = v - 128
  val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
  val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
  val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
  return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Converts an I420 image to NV21 (the layout android.graphics.YuvImage understands) while resizing
 * it to [dstWidth] x [dstHeight] (both even) with nearest-neighbour sampling. Used to send a small
 * JPEG of the current frame to the scene-description service.
 */
fun i420ToNv21Scaled(
    i420: ByteArray,
    width: Int,
    height: Int,
    dstWidth: Int,
    dstHeight: Int,
): ByteArray {
  require(dstWidth % 2 == 0 && dstHeight % 2 == 0) { "NV21 needs even dimensions" }
  val out = ByteArray(dstWidth * dstHeight * 3 / 2)
  for (y in 0 until dstHeight) {
    val sy = y * height / dstHeight
    for (x in 0 until dstWidth) {
      out[y * dstWidth + x] = i420[sy * width + x * width / dstWidth]
    }
  }
  val chromaWidth = width / 2
  val uOffset = width * height
  val vOffset = uOffset + chromaWidth * (height / 2)
  var o = dstWidth * dstHeight
  for (y in 0 until dstHeight / 2) {
    val sy = y * (height / 2) / (dstHeight / 2)
    for (x in 0 until dstWidth / 2) {
      val sx = x * chromaWidth / (dstWidth / 2)
      out[o++] = i420[vOffset + sy * chromaWidth + sx] // NV21: V first
      out[o++] = i420[uOffset + sy * chromaWidth + sx]
    }
  }
  return out
}

/**
 * Converts an NV21 image (Y plane, then interleaved V/U) to I420 (Y, then U, then V planes) in
 * [out], keeping every [step]-th pixel in both directions. The result is
 * [downscaledWidth] x [downscaledHeight]; [out] must hold that many pixels * 3 / 2 bytes. Used for
 * USB webcam frames, so HD webcams don't cost more than the detector can use.
 */
fun nv21ToI420(nv21: ByteArray, width: Int, height: Int, out: ByteArray, step: Int = 1) {
  val outWidth = downscaledWidth(width, step)
  val outHeight = downscaledHeight(height, step)
  val ySize = outWidth * outHeight
  val chromaSize = ySize / 4
  if (step == 1) {
    System.arraycopy(nv21, 0, out, 0, ySize)
  } else {
    for (y in 0 until outHeight) {
      val srcRow = y * step * width
      val dstRow = y * outWidth
      for (x in 0 until outWidth) out[dstRow + x] = nv21[srcRow + x * step]
    }
  }
  val srcChroma = width * height
  val chromaWidth = outWidth / 2
  for (cy in 0 until outHeight / 2) {
    val srcRow = srcChroma + cy * step * width // a chroma row is width bytes (V/U pairs)
    for (cx in 0 until chromaWidth) {
      val src = srcRow + cx * step * 2
      val i = cy * chromaWidth + cx
      out[ySize + chromaSize + i] = nv21[src] // V
      out[ySize + i] = nv21[src + 1] // U
    }
  }
}

/** Width after keeping every [step]-th pixel, rounded down to even (I420 needs even sizes). */
fun downscaledWidth(width: Int, step: Int): Int = (width / step) and 1.inv()

/** Height after keeping every [step]-th row, rounded down to even. */
fun downscaledHeight(height: Int, step: Int): Int = (height / step) and 1.inv()

/**
 * Reads the pixel at ([x], [y]) of an I420 image as packed ARGB.
 *
 * Plane layout: Y (width * height), then U and V (each (width / 2) * (height / 2)).
 */
@Suppress("NOTHING_TO_INLINE")
inline fun i420PixelArgb(i420: ByteArray, width: Int, height: Int, x: Int, y: Int): Int {
  val chromaWidth = width / 2
  val uOffset = width * height
  val vOffset = uOffset + chromaWidth * (height / 2)
  val chromaIndex = (y / 2) * chromaWidth + (x / 2)
  return yuvToArgb(
      i420[y * width + x].toInt() and 0xFF,
      i420[uOffset + chromaIndex].toInt() and 0xFF,
      i420[vOffset + chromaIndex].toInt() and 0xFF,
  )
}
