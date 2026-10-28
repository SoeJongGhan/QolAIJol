// CameraFrame - SDK-independent camera frame
//
// The vision layer only sees this type, never the Meta DAT VideoFrame, so YOLO code stays isolated
// from the Meta integration (see meta/MetaCameraManager.kt, which produces these).

package com.qolaijol.app.vision

/**
 * One decoded camera frame in I420 layout (planar YUV 4:2:0): the full-resolution Y plane, followed
 * by the quarter-resolution U plane, followed by the V plane.
 *
 * [i420] is a buffer reused by the producer: it is only valid for the duration of the callback that
 * receives the frame. Consumers that need the pixels later must copy them.
 */
class CameraFrame(
    val i420: ByteArray,
    val width: Int,
    val height: Int,
    val timestampUs: Long,
) {
  companion object {
    fun i420Size(width: Int, height: Int): Int = width * height * 3 / 2
  }
}
