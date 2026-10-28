package com.qolaijol.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class YuvTest {

  @Test
  fun nv21ToI420SplitsTheInterleavedChroma() {
    // 4x2 image: 8 luma bytes, then 2 chroma pairs (V, U) for the two 2x2 blocks.
    val y = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val nv21 = y + byteArrayOf(50, 10, 60, 20) // V0 U0 V1 U1
    val out = ByteArray(nv21.size)

    nv21ToI420(nv21, width = 4, height = 2, out = out)

    // I420: all of Y, then the U plane, then the V plane.
    assertArrayEquals(y + byteArrayOf(10, 20) + byteArrayOf(50, 60), out)
  }

  @Test
  fun nv21ToI420KeepsEveryStepThPixel() {
    // 8x4 image, luma value = row * 10 + column; chroma pairs (V, U) = (100 + i, 200 + i).
    val width = 8
    val height = 4
    val luma = ByteArray(width * height) { i -> ((i / width) * 10 + i % width).toByte() }
    val chroma = ByteArray(width * height / 2)
    for (i in 0 until chroma.size / 2) {
      chroma[2 * i] = (100 + i).toByte()
      chroma[2 * i + 1] = (200 + i).toByte()
    }
    val step = 2
    assertEquals(4, downscaledWidth(width, step))
    assertEquals(2, downscaledHeight(height, step))
    val out = ByteArray(4 * 2 * 3 / 2)

    nv21ToI420(luma + chroma, width, height, out, step)

    // Luma: columns 0, 2, 4, 6 of rows 0 and 2.
    val expectedY = byteArrayOf(0, 2, 4, 6, 20, 22, 24, 26)
    // Chroma: the source has 4x2 chroma pairs (0..7); keep pairs 0 and 2 of chroma row 0.
    val expectedU = byteArrayOf(200.toByte(), 202.toByte())
    val expectedV = byteArrayOf(100, 102)
    assertArrayEquals(expectedY + expectedU + expectedV, out)
  }

  @Test
  fun downscaledSizesStayEven() {
    assertEquals(640, downscaledWidth(1920, 3))
    assertEquals(360, downscaledHeight(1080, 3))
    // 1000 / 3 = 333, rounded down to even.
    assertEquals(332, downscaledWidth(1000, 3))
  }
}
