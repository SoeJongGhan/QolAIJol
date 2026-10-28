// TtsManager - speaks alerts with Android Text-to-Speech, in English, offline
//
// Warnings must work without internet, so only an installed (offline) voice is accepted; network
// voices are never selected. Plays through the phone's current audio route. When the glasses are
// the active Bluetooth audio device, Android routes speech there; explicit routing to the glasses
// via Meta DAT is a later step. If the offline voice is missing, [installVoiceIntent] opens the
// engine's voice download screen and [recheck] picks the voice up once it is installed.

package com.qolaijol.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TtsManager(
    context: Context,
    private val locale: Locale = Locale.US,
) {

  companion object {
    private const val TAG = "QolAIJol/TTS"
  }

  enum class Status {
    INITIALIZING,
    READY,
    /** The engine works, but no offline voice for [locale] is installed. */
    NO_OFFLINE_VOICE,
    UNAVAILABLE,
  }

  private val _status = MutableStateFlow(Status.INITIALIZING)
  val status: StateFlow<Status> = _status.asStateFlow()

  /** The last text actually spoken (alert, answer or instruction), for "repeat". */
  @Volatile
  var lastSpoken: String? = null
    private set

  private val utteranceIds = AtomicInteger(0)
  private val textToSpeech: TextToSpeech = TextToSpeech(context.applicationContext, ::onInit)

  private fun onInit(result: Int) {
    if (result != TextToSpeech.SUCCESS) {
      Log.e(TAG, "Text-to-speech engine failed to initialize (code $result)")
      _status.value = Status.UNAVAILABLE
      return
    }
    textToSpeech.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
    )
    textToSpeech.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String) {}

          override fun onDone(utteranceId: String) {
            Log.d(TAG, "Finished speaking ($utteranceId)")
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String) {
            Log.e(TAG, "Speech failed ($utteranceId)")
          }

          override fun onError(utteranceId: String, errorCode: Int) {
            Log.e(TAG, "Speech failed ($utteranceId), error $errorCode")
          }
        },
    )
    selectVoice()
  }

  /** Sets the language and the best offline voice; READY on success, else NO_OFFLINE_VOICE. */
  private fun selectVoice() {
    val languageResult = textToSpeech.setLanguage(locale)
    if (
        languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
    ) {
      Log.e(TAG, "Text-to-speech language $locale not available (code $languageResult)")
      _status.value = Status.NO_OFFLINE_VOICE
      return
    }
    val offlineVoice = pickOfflineVoice()
    if (offlineVoice == null) {
      Log.e(TAG, "No offline $locale voice installed; network voices are not used for warnings")
      _status.value = Status.NO_OFFLINE_VOICE
      return
    }
    textToSpeech.voice = offlineVoice
    Log.i(TAG, "Text-to-speech ready: ${offlineVoice.name} (offline, $locale)")
    _status.value = Status.READY
  }

  /** Looks for the offline voice again (e.g. after the user downloaded it). */
  fun recheck() {
    if (_status.value == Status.NO_OFFLINE_VOICE) selectVoice()
  }

  /** Opens the current engine's screen for downloading voice data. */
  fun installVoiceIntent(): Intent =
      Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
        textToSpeech.defaultEngine?.let { setPackage(it) }
      }

  /** Best installed voice for [locale] that needs no network, or null. */
  private fun pickOfflineVoice(): Voice? {
    val voices =
        try {
          textToSpeech.voices.orEmpty()
        } catch (e: Exception) {
          Log.e(TAG, "Could not list text-to-speech voices", e)
          emptySet()
        }
    val sameLanguage = voices.filter { it.locale.language == locale.language }
    val offline =
        sameLanguage.filter {
          !it.isNetworkConnectionRequired &&
              TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
    Log.d(
        TAG,
        "${locale.language} voices: ${sameLanguage.size} total, offline: " +
            offline.joinToString { "${it.name}(q=${it.quality})" },
    )
    return offline.maxWithOrNull(
        compareBy<Voice>({ it.locale.country == locale.country }, { it.quality }, { -it.latency }),
    )
  }

  /**
   * Speaks [text]. [interrupt] drops anything still queued or playing (for urgent alerts). Returns
   * false if speech is unavailable, so callers can fall back to on-screen text.
   */
  fun speak(text: String, interrupt: Boolean = false): Boolean {
    if (_status.value != Status.READY) {
      Log.w(TAG, "Not speaking \"$text\": text-to-speech is ${_status.value}")
      return false
    }
    val utteranceId = "alert-${utteranceIds.incrementAndGet()}"
    val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
    val result = textToSpeech.speak(text, queueMode, null, utteranceId)
    if (result != TextToSpeech.SUCCESS) {
      Log.e(TAG, "speak() rejected \"$text\" (code $result)")
      return false
    }
    Log.i(TAG, "Speaking: $text ($utteranceId)")
    lastSpoken = text
    return true
  }

  /** Stops speaking now (e.g. before listening to the user, so the mic doesn't hear the phone). */
  fun stop() {
    if (_status.value == Status.READY) textToSpeech.stop()
  }

  fun shutdown() {
    textToSpeech.stop()
    textToSpeech.shutdown()
    _status.value = Status.UNAVAILABLE
  }
}
