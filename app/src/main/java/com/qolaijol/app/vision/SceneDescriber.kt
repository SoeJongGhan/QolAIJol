// SceneDescriber - "Describe what's around": one camera frame -> short spoken English description
//
// Only ever called on an explicit user request (never per frame). The frame is shrunk to at most
// 640 px (~1000 image tokens; the Groq key allows ~8000 tokens/minute) and sent to the vision model
// with a prompt based on the Python prototype's, tightened after testing (Phase 5):
// no invented distances, at most three sentences, never "it is safe to cross".

package com.qolaijol.app.vision

import android.graphics.Rect
import android.graphics.YuvImage
import com.qolaijol.app.network.GroqClient
import com.qolaijol.app.utils.i420ToNv21Scaled
import java.io.ByteArrayOutputStream

class SceneDescriber(private val groq: GroqClient = GroqClient()) {

  companion object {
    private const val MAX_SIDE = 640
    private const val JPEG_QUALITY = 80

    const val PROMPT =
        """You assist a blind person. This is a frame from the camera on their glasses; they are looking ahead.
Briefly say only what matters for safety and orientation:
1. Obstacles and hazards in the way.
2. People and vehicles.
3. Doors, stairs, steps, curbs, crosswalks.
4. Important signs and labels — read them out, translated into English, if they are clearly visible.
Give directions with the words "ahead", "on the left", "on the right".
Do not give distances in meters or steps: you cannot measure them. You may say "right in front of you" if something is clearly very close.
For people, say only where they are. Do not describe appearance, clothing or faces.
Never say that it is safe to cross the road or to walk on.
Do not mention that this is an image, photo or frame.
If nothing important is visible, say so.
Answer in English, in no more than three short sentences."""

    /** Removes leaked reasoning and layout so the text reads well aloud. */
    fun sanitize(text: String): String =
        text
            .replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), "")
            .replace(Regex("[*#_`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Target size: longest side at most [MAX_SIDE], both sides even. */
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
      val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
      val w = (width * scale).toInt() and 1.inv()
      val h = (height * scale).toInt() and 1.inv()
      return w to h
    }
  }

  /** Small JPEG of an I420 camera frame. */
  fun toJpeg(frame: CameraFrame): ByteArray {
    val (w, h) = scaledSize(frame.width, frame.height)
    val nv21 = i420ToNv21Scaled(frame.i420, frame.width, frame.height, w, h)
    return ByteArrayOutputStream().use { out ->
      YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
          .compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
      out.toByteArray()
    }
  }

  /** Describes the scene in [jpeg]. Throws GroqClient.GroqException on failure. */
  suspend fun describe(jpeg: ByteArray): String = sanitize(groq.describeImage(jpeg, PROMPT))
}
