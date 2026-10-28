package com.qolaijol.app.vision

import com.qolaijol.app.network.GroqClient
import com.qolaijol.app.utils.i420ToNv21Scaled
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneDescriberTest {

  @Test
  fun i420ToNv21HalvesAndInterleavesVu() {
    // 4x4 I420: Y = 0..15, U (2x2) = 100..103, V (2x2) = 200..203.
    val y = ByteArray(16) { it.toByte() }
    val u = byteArrayOf(100, 101, 102, 103)
    val v = byteArrayOf(-56, -55, -54, -53) // 200..203 as signed bytes
    val nv21 = i420ToNv21Scaled(y + u + v, 4, 4, 2, 2)
    // Y sampled at (0,0) (2,0) (0,2) (2,2); then one V,U pair from chroma (0,0).
    assertArrayEquals(byteArrayOf(0, 2, 8, 10, -56, 100), nv21)
  }

  @Test
  fun i420ToNv21SameSizeKeepsPlanes() {
    val y = ByteArray(4) { (it + 1).toByte() }
    val nv21 = i420ToNv21Scaled(y + byteArrayOf(7) + byteArrayOf(9), 2, 2, 2, 2)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 9, 7), nv21)
  }

  @Test
  fun scaledSizeKeepsAspectAndEvenSides() {
    assertEquals(360 to 640, SceneDescriber.scaledSize(540, 960)) // MockDeviceKit clip
    assertEquals(360 to 640, SceneDescriber.scaledSize(504, 896)) // glasses MEDIUM
    assertEquals(320 to 240, SceneDescriber.scaledSize(320, 240)) // never upscaled
  }

  @Test
  fun sanitizeRemovesReasoningAndMarkup() {
    assertEquals(
        "A bus ahead. A person on the left.",
        SceneDescriber.sanitize("<think>hard to see</think>\n**A bus** ahead.\n\nA person on the left."),
    )
    assertEquals("A pole ahead.", SceneDescriber.sanitize("A pole ahead. <think>unclosed reasoning"))
  }

  @Test
  fun rateLimitResetParsing() {
    assertEquals(34, GroqClient.parseResetSeconds("33.217s"))
    assertEquals(260, GroqClient.parseResetSeconds("4m19.2s"))
    assertEquals(7, GroqClient.parseResetSeconds("7"))
    assertEquals(3600, GroqClient.parseResetSeconds("1h"))
    assertNull(GroqClient.parseResetSeconds(null))
    assertNull(GroqClient.parseResetSeconds("soon"))
  }
}
