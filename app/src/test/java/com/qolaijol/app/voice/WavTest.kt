package com.qolaijol.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavTest {

  private fun chunk(id: String, body: ByteArray): ByteArray =
      id.toByteArray() +
          ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() +
          body +
          (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

  private fun fmt(rate: Int, channels: Int = 1, bits: Int = 16, format: Int = 1): ByteArray =
      ByteBuffer.allocate(16)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putShort(format.toShort())
          .putShort(channels.toShort())
          .putInt(rate)
          .putInt(rate * channels * bits / 8)
          .putShort((channels * bits / 8).toShort())
          .putShort(bits.toShort())
          .array()

  private fun wav(vararg chunks: ByteArray): ByteArray {
    val body = "WAVE".toByteArray() + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    return "RIFF".toByteArray() +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() +
        body
  }

  @Test
  fun readsPlainWav() {
    val pcm = byteArrayOf(1, 2, 3, 4)
    val parsed = Wav.parse(wav(chunk("fmt ", fmt(16_000)), chunk("data", pcm)))!!
    assertEquals(16_000, parsed.sampleRate)
    assertEquals(1, parsed.channels)
    assertEquals(16, parsed.bitsPerSample)
    assertArrayEquals(pcm, parsed.pcm)
  }

  @Test
  fun skipsExtraChunksBeforeData() {
    val pcm = byteArrayOf(9, 8, 7, 6)
    val parsed =
        Wav.parse(
            wav(chunk("fmt ", fmt(22_050)), chunk("LIST", "abc".toByteArray()), chunk("data", pcm)),
        )!!
    assertEquals(22_050, parsed.sampleRate)
    assertArrayEquals(pcm, parsed.pcm)
  }

  @Test
  fun rejectsNonPcmOrGarbage() {
    assertNull(Wav.parse(wav(chunk("fmt ", fmt(16_000, format = 3)), chunk("data", byteArrayOf(0, 0)))))
    assertNull(Wav.parse("not a wav file".toByteArray()))
  }
}
