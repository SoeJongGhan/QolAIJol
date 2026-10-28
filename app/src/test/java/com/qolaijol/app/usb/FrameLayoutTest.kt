package com.qolaijol.app.usb

import com.qolaijol.app.usb.FrameLayout.Size
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameLayoutTest {

  private val vga = Size(640, 480)
  private val qvga = Size(320, 240)
  private val hd = Size(1280, 720)

  /** A smooth NV21 scene of [size] at the start of a [bufferBytes] buffer; the rest is noise. */
  private fun frame(size: Size, bufferBytes: Int): ByteArray {
    val buffer = ByteArray(bufferBytes).also { Random(1).nextBytes(it) }
    val (w, h) = size
    for (y in 0 until h) {
      for (x in 0 until w) {
        val value = 128 + 50 * sin(x / 7.0) + 40 * cos(y / 5.0) + 30 * sin((x + y) / 11.0)
        buffer[y * w + x] = value.toInt().coerceIn(0, 255).toByte()
      }
    }
    for (i in 0 until w * h / 2) {
      buffer[w * h + i] = (128 + 20 * sin(i / 13.0)).toInt().toByte()
    }
    return buffer
  }

  @Test
  fun narrowerFrameInABufferSizedForTheAskedResolution() {
    // The webcam was asked for 640x480 but sends 320x240; the buffer is sized for 640x480. Read as
    // 640 wide this showed side-by-side copies.
    val buffer = frame(qvga, vga.nv21Bytes)
    assertEquals(qvga, FrameLayout.detect(buffer, buffer.size, vga, listOf(vga, qvga, hd)))
  }

  @Test
  fun keepsTheReportedSizeWhenItIsRight() {
    val buffer = frame(vga, vga.nv21Bytes)
    assertEquals(vga, FrameLayout.detect(buffer, buffer.size, vga, listOf(vga, qvga, hd)))
  }

  @Test
  fun flatSceneKeepsThePreferredSize() {
    // A covered lens gives no clue: nothing changes.
    val buffer = ByteArray(vga.nv21Bytes) { 100 }
    assertEquals(vga, FrameLayout.detect(buffer, buffer.size, vga, listOf(vga, qvga)))
  }

  @Test
  fun heightIsWhereTheChromaStarts() {
    val wide = Size(320, 180)
    val buffer = frame(wide, vga.nv21Bytes)
    assertEquals(wide, FrameLayout.detect(buffer, buffer.size, vga, listOf(vga, qvga, wide)))
  }

  @Test
  fun sizesThatDontFitTheBufferAreIgnored() {
    val buffer = ByteArray(1000)
    assertEquals(null, FrameLayout.detect(buffer, buffer.size, vga, listOf(vga, hd)))
  }
}
