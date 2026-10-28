// Wav - reads the PCM samples and format out of a WAV file (plain Kotlin, unit tested)
//
// The recorder writes a plain 44-byte header, but WAV files from other tools may carry extra chunks
// ("LIST", "fact", ...), so the chunks are walked instead of assuming a fixed header size.

package com.qolaijol.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

class Wav(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val pcm: ByteArray) {

  companion object {
    /** Parses [bytes]; null if it is not a PCM WAV file. */
    fun parse(bytes: ByteArray): Wav? {
      if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      var offset = 12
      var sampleRate = 0
      var channels = 0
      var bits = 0
      while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4)
        val size = buffer.getInt(offset + 4)
        val body = offset + 8
        if (size < 0 || body + size > bytes.size && id != "data") return null
        when (id) {
          "fmt " -> {
            if (buffer.getShort(body).toInt() != 1) return null // 1 = uncompressed PCM
            channels = buffer.getShort(body + 2).toInt()
            sampleRate = buffer.getInt(body + 4)
            bits = buffer.getShort(body + 14).toInt()
          }
          "data" -> {
            if (sampleRate == 0) return null
            val end = minOf(bytes.size, body + size)
            return Wav(sampleRate, channels, bits, bytes.copyOfRange(body, end))
          }
        }
        offset = body + size + (size and 1) // chunks are word-aligned
      }
      return null
    }
  }
}
