// OfflineSpeechRecognizer - Android's built-in on-device speech recognition as a fallback
//
// Used when there is no internet (or the cloud recognizer fails). It gets the same recording as
// the cloud path — fed through RecognizerIntent.EXTRA_AUDIO_SOURCE — so the glasses microphone and
// the push-to-talk button behave the same either way. Feeding our own audio needs Android 13
// (API 33); older phones get no offline fallback. The English on-device model may have to be
// downloaded once while online; this class can ask the system to do that.

package com.qolaijol.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class OfflineSpeechRecognizer(context: Context, private val language: String = "en-US") {

  companion object {
    private const val TAG = "QolAIJol/Voice"
    private const val RECOGNITION_TIMEOUT_MS = 15_000L
    private const val RETRY_DELAY_MS = 500L
    /** Service disconnected, busy, or client error: usually fixed by a fresh recognizer. */
    private val RETRYABLE_ERRORS =
        setOf(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT,
        )
  }

  enum class Availability {
    READY,
    /** Supported, but the language model isn't installed yet (download needs internet). */
    MODEL_MISSING,
    /** Android < 13, no on-device recognizer, or the language isn't supported on-device. */
    UNSUPPORTED,
  }

  private val appContext = context.applicationContext

  private fun baseIntent(): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
          .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

  /** Whether offline recognition of [language] can be used right now. Main-thread safe. */
  suspend fun availability(): Availability =
      withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
          return@withContext Availability.UNSUPPORTED
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        try {
          val support =
              withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<RecognitionSupport?> { continuation ->
                  recognizer.checkRecognitionSupport(
                      baseIntent(),
                      appContext.mainExecutor,
                      object : RecognitionSupportCallback {
                        override fun onSupportResult(result: RecognitionSupport) {
                          if (continuation.isActive) continuation.resume(result)
                        }

                        override fun onError(error: Int) {
                          Log.w(TAG, "Offline recognition support check failed: $error")
                          if (continuation.isActive) continuation.resume(null)
                        }
                      },
                  )
                }
              } ?: return@withContext Availability.UNSUPPORTED
          val lang = language.lowercase()
          fun List<String>.has() = any { it.lowercase() == lang || it.lowercase().startsWith("${lang.take(2)}-") }
          when {
            support.installedOnDeviceLanguages.has() -> Availability.READY
            support.pendingOnDeviceLanguages.has() || support.supportedOnDeviceLanguages.has() ->
                Availability.MODEL_MISSING
            else -> Availability.UNSUPPORTED
          }
        } finally {
          recognizer.destroy()
        }
      }

  /** Asks the system to download the on-device model for [language] (needs internet). */
  suspend fun requestModelDownload() =
      withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        try {
          recognizer.triggerModelDownload(baseIntent())
          Log.i(TAG, "Requested download of the on-device $language speech model")
        } catch (e: Exception) {
          Log.w(TAG, "Could not request the on-device speech model", e)
        } finally {
          recognizer.destroy()
        }
      }

  // One recognizer reused across commands: creating and destroying one per command made the next
  // command fail with ERROR_SERVER_DISCONNECTED while the service was still shutting down.
  private var recognizer: SpeechRecognizer? = null

  private sealed interface Attempt {
    data class Text(val text: String?) : Attempt

    data class Error(val code: Int) : Attempt
  }

  /** Recognizes speech in [wav] on the device. Returns the text, or null on any failure. */
  suspend fun transcribe(wav: Wav): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (wav.bitsPerSample != 16) return null
    return withContext(Dispatchers.Main) {
      var attempt = attempt(wav)
      if (attempt is Attempt.Error && attempt.code in RETRYABLE_ERRORS) {
        Log.w(TAG, "Offline recognizer error ${attempt.code}; recreating it and retrying once")
        recognizer?.destroy()
        recognizer = null
        delay(RETRY_DELAY_MS)
        attempt = attempt(wav)
      }
      val text = (attempt as? Attempt.Text)?.text
      if (text == null) Log.w(TAG, "Offline recognition gave no text ($attempt)")
      text
    }
  }

  fun release() {
    recognizer?.destroy()
    recognizer = null
  }

  /** One recognition run on the main thread with a fresh audio pipe. */
  private suspend fun attempt(wav: Wav): Attempt {
    val current =
        recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { recognizer = it }
    val pipe = ParcelFileDescriptor.createPipe()
    val readEnd = pipe[0]
    // Feed the recording from a background thread; closing the write end marks the end of audio.
    Thread({
          try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(wav.pcm) }
          } catch (e: Exception) {
            Log.w(TAG, "Feeding audio to the offline recognizer failed: ${e.message}")
          }
        }, "QolAIJolOfflineAudio")
        .start()
    try {
      // Last non-empty partial result: the final result sometimes comes back empty (seen with the
      // Google on-device recognizer), the partial one still has the words.
      var lastPartial: String? = null
      return withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
        suspendCancellableCoroutine<Attempt> { continuation ->
          current.setRecognitionListener(
              object : RecognitionListener {
                override fun onResults(results: Bundle) {
                  val hypotheses = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                  Log.d(TAG, "Offline results: ${hypotheses?.size ?: 0} hypotheses")
                  val text = hypotheses?.firstOrNull { it.isNotBlank() } ?: lastPartial
                  if (continuation.isActive) continuation.resume(Attempt.Text(text))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                  partialResults
                      ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                      ?.firstOrNull { it.isNotBlank() }
                      ?.let { lastPartial = it }
                }

                override fun onError(error: Int) {
                  Log.w(TAG, "Offline recognition error $error (partial: ${lastPartial != null})")
                  val result = lastPartial?.let { Attempt.Text(it) } ?: Attempt.Error(error)
                  if (continuation.isActive) continuation.resume(result)
                }

                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
              },
          )
          current.startListening(
              baseIntent()
                  .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                  .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                  .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, wav.channels)
                  .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                  .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, wav.sampleRate),
          )
          continuation.invokeOnCancellation { current.cancel() }
        }
      } ?: Attempt.Text(lastPartial)
    } finally {
      runCatching { readEnd.close() }
    }
  }
}
