// MetaCameraManager - Meta DAT camera stream -> QolAIJol camera frames
//
// The only place where Meta's VideoFrame is turned into the SDK-independent CameraFrame used by
// the vision pipeline. It owns the stream configuration (raw, uncompressed frames) and validates
// every frame before it reaches YOLO.
//
// The DeviceSession / Camera / Stream lifecycle itself is still driven by CameraViewModel (reused
// from Meta's CameraAccess sample, unchanged); it moves here once the sample UI is replaced.

package com.qolaijol.app.meta

import android.util.Log
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.qolaijol.app.vision.CameraFrame

class MetaCameraManager {

  companion object {
    private const val TAG = "QolAIJol/Camera"
    const val FRAME_RATE = 24
    private const val INVALID_FRAME_LOG_EVERY = 100

    /**
     * Uncompressed stream: the SDK decodes the glasses' HEVC stream on the phone and delivers raw
     * YUV frames (the SDK's decoder requests MediaCodec COLOR_FormatYUV420Planar, i.e. I420).
     * MEDIUM quality is 504 x 896 portrait.
     */
    val STREAM_CONFIGURATION =
        StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = FRAME_RATE,
            compressVideo = false,
        )
  }

  // Reused frame buffer; only accessed on the single frame-collector thread.
  private var buffer = ByteArray(0)
  private var loggedFirstFrame = false
  private var loggedOversizedFrame = false
  private var loggedCompressedFrame = false
  private var invalidFrames = 0

  /**
   * Converts [videoFrame] to a [CameraFrame], or returns null if it can't be used. The returned
   * frame shares this manager's buffer: it is only valid until the next call.
   */
  fun toCameraFrame(videoFrame: VideoFrame): CameraFrame? {
    if (videoFrame.isCompressed) {
      if (!loggedCompressedFrame) {
        loggedCompressedFrame = true
        Log.w(TAG, "Received a compressed frame; expected raw frames (compressVideo = false)")
      }
      return null
    }

    val width = videoFrame.width
    val height = videoFrame.height
    val source = videoFrame.buffer.duplicate()
    val available = source.remaining()
    val expected = if (width > 0 && height > 0) CameraFrame.i420Size(width, height) else -1

    if (!loggedFirstFrame) {
      loggedFirstFrame = true
      Log.i(TAG, "First raw frame: ${width}x$height, $available bytes (I420 needs $expected)")
    }

    if (expected <= 0 || width % 2 != 0 || height % 2 != 0 || available < expected) {
      if (invalidFrames++ % INVALID_FRAME_LOG_EVERY == 0) {
        Log.w(
            TAG,
            "Dropping invalid frame ${width}x$height with $available bytes " +
                "(needs $expected); dropped so far: $invalidFrames",
        )
      }
      return null
    }
    if (available > expected && !loggedOversizedFrame) {
      loggedOversizedFrame = true
      Log.w(TAG, "Frame has $available bytes, more than I420's $expected; using the first $expected")
    }

    if (buffer.size != expected) buffer = ByteArray(expected)
    source.get(buffer, 0, expected)
    return CameraFrame(buffer, width, height, videoFrame.presentationTimeUs)
  }

  /** Resets per-stream logging so the next stream logs its first frame again. */
  fun reset() {
    loggedFirstFrame = false
    loggedOversizedFrame = false
    loggedCompressedFrame = false
    invalidFrames = 0
  }
}
