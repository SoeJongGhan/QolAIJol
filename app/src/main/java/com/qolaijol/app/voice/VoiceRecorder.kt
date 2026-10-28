// VoiceRecorder - records one voice command as a WAV file in memory
//
// Prefers the glasses microphone (Bluetooth SCO / HFP, same routing as the sample's
// AudioInputHandler) and falls back to the phone microphone, so commands also work without
// glasses. 16 kHz mono PCM is plenty for speech recognition and keeps uploads small.

package com.qolaijol.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class VoiceRecorder(context: Context) {

  companion object {
    private const val TAG = "QolAIJol/Voice"
    private const val SAMPLE_RATE = 16_000
    private const val SCO_CONNECT_TIMEOUT_MS = 2_000L
  }

  enum class Source {
    GLASSES,
    PHONE,
  }

  class Recording(val wav: ByteArray, val durationMs: Long, val peak: Int, val source: Source)

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile private var record: AudioRecord? = null
  @Volatile private var active = false
  private var thread: Thread? = null
  private var pcm = ByteArrayOutputStream()
  @Volatile private var peak = 0
  private var startedMs = 0L
  private var source = Source.PHONE
  private var routedForGlasses = false
  private var previousMode = AudioManager.MODE_NORMAL

  /** Starts recording. Returns the microphone used; throws IllegalStateException if none works. */
  suspend fun start(): Source {
    check(record == null) { "Already recording" }
    check(
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    ) {
      "RECORD_AUDIO not granted"
    }

    val glassesInput = routeToGlasses()
    source = if (glassesInput != null) Source.GLASSES else Source.PHONE
    val minBuffer =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val newRecord =
        try {
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              minBuffer * 4,
          )
        } catch (e: SecurityException) {
          releaseRoute()
          throw IllegalStateException("Microphone permission denied", e)
        }
    if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
      newRecord.release()
      releaseRoute()
      throw IllegalStateException("Microphone could not be opened")
    }
    glassesInput?.let { newRecord.setPreferredDevice(it) }

    pcm = ByteArrayOutputStream()
    peak = 0
    record = newRecord
    active = true
    startedMs = SystemClock.elapsedRealtime()
    newRecord.startRecording()
    thread =
        Thread({
              val buffer = ShortArray(minBuffer)
              val bytes = ByteBuffer.allocate(minBuffer * 2).order(ByteOrder.LITTLE_ENDIAN)
              while (active) {
                val read = newRecord.read(buffer, 0, buffer.size)
                if (read < 0) {
                  Log.w(TAG, "Microphone read error $read")
                  break
                }
                bytes.clear()
                for (i in 0 until read) {
                  val sample = buffer[i].toInt()
                  if (abs(sample) > peak) peak = abs(sample)
                  bytes.putShort(buffer[i])
                }
                synchronized(pcm) { pcm.write(bytes.array(), 0, read * 2) }
              }
            }, "QolAIJolVoiceRecorder")
            .also { it.start() }
    Log.i(TAG, "Recording started from the ${source.name.lowercase()} microphone")
    return source
  }

  /** Stops recording and returns the audio; null if nothing was recording. */
  fun stop(): Recording? {
    val current = record ?: return null
    active = false
    thread?.join(1_000)
    thread = null
    runCatching { current.stop() }
    current.release()
    record = null
    releaseRoute()
    val data = synchronized(pcm) { pcm.toByteArray() }
    val durationMs = SystemClock.elapsedRealtime() - startedMs
    Log.i(TAG, "Recording stopped: ${durationMs} ms, ${data.size / 1024} KB, peak=$peak")
    return Recording(wav(data), durationMs, peak, source)
  }

  fun release() {
    stop()
  }

  // Same routing approach as the sample's AudioInputHandler: communication mode + SCO device.
  private suspend fun routeToGlasses(): AudioDeviceInfo? {
    val sco =
        runCatching {
              audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
              }
            }
            .getOrNull() ?: return null
    return try {
      previousMode = audioManager.mode
      routedForGlasses = true
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
      val routed =
          audioManager.setCommunicationDevice(sco) &&
              withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
                while (audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                  delay(100)
                }
                true
              } == true
      if (!routed) {
        releaseRoute()
        return null
      }
      audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
      } ?: run {
        releaseRoute()
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Glasses microphone unavailable, using the phone microphone", e)
      releaseRoute()
      null
    }
  }

  private fun releaseRoute() {
    if (!routedForGlasses) return
    runCatching { audioManager.clearCommunicationDevice() }
    runCatching { audioManager.mode = previousMode }
    routedForGlasses = false
  }

  /** 16-bit mono PCM -> WAV file bytes. */
  private fun wav(data: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(data.size)
    return header.array() + data
  }
}
