// VoiceAssistant - push-to-talk voice commands
//
// Press (phone Speak button, double tap on the glasses, or the notification's Speak) -> beep ->
// record (glasses mic, else phone mic) -> second press or 10 s -> Groq Whisper (en) -> local
// CommandParser -> cloud LLM only if the phrase wasn't understood -> spoken answer. Every failure is
// spoken too ("no internet", "didn't catch that"), so a blind user always gets feedback. Phone
// buttons and glasses gestures run the common commands directly with runCommand, without speech.
// Warnings from the camera pipeline are paused while a command is being handled.

package com.qolaijol.app.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.qolaijol.app.BuildConfig
import com.qolaijol.app.audio.TtsManager
import com.qolaijol.app.network.GroqClient
import com.qolaijol.app.vision.CameraFrame
import com.qolaijol.app.vision.SceneDescriber
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VoiceAssistant(
    context: Context,
    private val scope: CoroutineScope,
    private val tts: TtsManager,
    private val actions: Actions,
    private val groq: GroqClient = GroqClient(),
) {

  /** What the rest of the app provides to answer commands. */
  interface Actions {
    fun sceneAnswer(zone: SceneZone): String

    fun setAssistanceEnabled(enabled: Boolean)

    /** A copy of the next camera frame, for "describe". */
    suspend fun captureFrame(): FrameCapture

    /** "Find a pharmacy" -> spoken result. */
    suspend fun findPlace(place: String): String

    /** "Take me to…" ([place]) or "go" (null = last found place) -> spoken route start. */
    suspend fun navigate(place: String?): String

    fun stopNavigation(): String

    fun navigationStatus(): String
  }

  sealed interface FrameCapture {
    data class Ok(val frame: CameraFrame) : FrameCapture

    data object CameraOff : FrameCapture

    data object NoFrame : FrameCapture
  }

  enum class Phase {
    IDLE,
    LISTENING,
    RECOGNIZING,
    UNDERSTANDING,
    DESCRIBING,
    SEARCHING,
  }

  data class State(
      val phase: Phase = Phase.IDLE,
      val heard: String? = null,
      /** True if [heard] came from the on-device (offline) recognizer. */
      val heardOffline: Boolean = false,
      val answer: String? = null,
  )

  private sealed interface Recognition {
    data class Text(val text: String, val offline: Boolean) : Recognition

    data class Failed(val spokenReason: String) : Recognition
  }

  companion object {
    private const val TAG = "QolAIJol/Voice"
    const val MAX_RECORDING_MS = 10_000L
    private const val MIN_RECORDING_MS = 400L
    private const val SILENCE_PEAK = 300 // of 32767
    /**
     * Debug builds only: a file "voice_test*.wav" in the app's external files dir replaces the
     * microphone for one button press (newest file first). Use a new name for every test file:
     * Android's storage layer may briefly not see a file re-created under a just-deleted name.
     */
    const val DEBUG_TEST_WAV_PREFIX = "voice_test"

    const val HELP_TEXT =
        "Double tap the glasses, or press Speak on the phone, and say: what's ahead, what's on " +
            "the left, what's on the right, what do you see. Say describe for a detailed " +
            "description; it needs the internet. Say find a pharmacy, or take me to the bus " +
            "stop, to navigate. How far is it, and stop route. Say stop to turn the alerts off, " +
            "and start to turn them on. Say repeat to hear the last message again. " +
            "On the glasses, swipe to hear what's around. Press the camera button for a " +
            "description, press it twice to repeat, and hold it to turn the alerts off or on. " +
            "Tap once to pause or resume. Without internet, these work: what's ahead, left, " +
            "right, stop, start and repeat."

    const val CAMERA_OFF = "The camera is off. Press Start on the phone."
    const val NO_IMAGE = "No image from the camera."
    private const val DIDNT_CATCH = "I didn't catch that. Please try again."
  }

  private val appContext = context.applicationContext
  private val recorder = VoiceRecorder(appContext)
  private val describer = SceneDescriber(groq)
  private val offlineRecognizer = OfflineSpeechRecognizer(appContext)
  private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

  // Whether the offline fallback is there for when the internet isn't; null until checked. The
  // camera screen offers to download a missing model (see downloadOfflineModel).
  private val _offlineModel = MutableStateFlow<OfflineSpeechRecognizer.Availability?>(null)
  val offlineModel: StateFlow<OfflineSpeechRecognizer.Availability?> = _offlineModel.asStateFlow()

  init {
    scope.launch { checkOfflineModel() }
  }

  /** Re-checks whether the on-device English model is installed. */
  suspend fun checkOfflineModel(): OfflineSpeechRecognizer.Availability {
    val availability = offlineRecognizer.availability()
    Log.i(TAG, "Offline speech recognition: $availability")
    _offlineModel.value = availability
    return availability
  }

  /** Asks the system to download the on-device English model (may show a system prompt). */
  suspend fun downloadOfflineModel() {
    offlineRecognizer.requestModelDownload()
  }
  private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
  private var autoStopJob: Job? = null

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  /** True while listening or handling a command; camera warnings stay quiet meanwhile. */
  val isBusy: Boolean
    get() = _state.value.phase != Phase.IDLE

  /** The big button: starts listening, or stops and handles the command. */
  fun onButton(requestMicPermission: suspend () -> Boolean) {
    when (_state.value.phase) {
      Phase.IDLE -> scope.launch { startListening(requestMicPermission) }
      Phase.LISTENING -> scope.launch { finishListening() }
      Phase.RECOGNIZING,
      Phase.UNDERSTANDING,
      Phase.DESCRIBING,
      Phase.SEARCHING -> Unit // already working on the last command
    }
  }

  /**
   * Runs [command] without listening, for phone buttons and glasses gestures. Ignored while a
   * command is in progress, like a press of the button would be.
   */
  fun runCommand(command: VoiceCommand) {
    if (isBusy) return
    if (command == VoiceCommand.Describe) {
      _state.update { it.copy(phase = Phase.DESCRIBING, heard = null, answer = null) }
      // describeScene() says "One moment, looking"; queue the description after it.
      scope.launch { answer(describeScene(), interrupt = false) }
    } else {
      _state.update { it.copy(heard = null) }
      scope.launch { answer(execute(command)) }
    }
  }

  private suspend fun startListening(requestMicPermission: suspend () -> Boolean) {
    debugTestWav()?.let { file ->
      Log.w(TAG, "DEBUG: using ${file.name} instead of the microphone")
      val wav = file.readBytes()
      file.delete()
      _state.update { it.copy(phase = Phase.RECOGNIZING, heard = null, answer = null) }
      handle(wav, durationMs = MAX_RECORDING_MS, peak = Short.MAX_VALUE.toInt())
      return
    }
    if (!requestMicPermission()) {
      answer("I need microphone access. Please allow it in the app settings.")
      return
    }
    tts.stop()
    try {
      _state.update { it.copy(phase = Phase.LISTENING, heard = null, answer = null) }
      beep(ToneGenerator.TONE_PROP_BEEP)
      recorder.start()
    } catch (e: Exception) {
      Log.e(TAG, "Could not start recording", e)
      answer("Couldn't turn on the microphone.")
      return
    }
    autoStopJob =
        scope.launch {
          delay(MAX_RECORDING_MS)
          finishListening()
        }
  }

  private suspend fun finishListening() {
    if (_state.value.phase != Phase.LISTENING) return
    autoStopJob?.cancel()
    autoStopJob = null
    val recording = recorder.stop()
    beep(ToneGenerator.TONE_PROP_ACK)
    _state.update { it.copy(phase = Phase.RECOGNIZING) }
    if (recording == null) {
      answer("Couldn't record sound.")
      return
    }
    handle(recording.wav, recording.durationMs, recording.peak)
  }

  private suspend fun handle(wav: ByteArray, durationMs: Long, peak: Int) {
    if (durationMs < MIN_RECORDING_MS || peak < SILENCE_PEAK) {
      answer(DIDNT_CATCH)
      return
    }
    val recognition = recognize(wav)
    if (recognition is Recognition.Failed) {
      answer(recognition.spokenReason)
      return
    }
    val (text, offline) = recognition as Recognition.Text
    Log.i(TAG, "Heard${if (offline) " (offline)" else ""}: \"$text\"")
    _state.update { it.copy(heard = text, heardOffline = offline) }
    if (text.isBlank()) {
      answer(DIDNT_CATCH)
      return
    }

    var command = CommandParser.parse(text)
    // The cloud LLM fallback only makes sense online.
    if (command is VoiceCommand.Unknown && !offline) {
      _state.update { it.copy(phase = Phase.UNDERSTANDING) }
      command = classifyWithLlm(text) ?: command
    }
    Log.i(TAG, "Command: $command")
    if (command == VoiceCommand.Describe) {
      // "One moment, looking" is already playing; queue the description after it.
      answer(describeScene(), interrupt = false)
    } else {
      answer(execute(command))
    }
  }

  /**
   * Cloud Whisper when online (best quality); Android's on-device recognizer with the same
   * recording when offline or when the cloud fails.
   */
  private suspend fun recognize(wav: ByteArray): Recognition {
    var cloudError: GroqClient.GroqException? = null
    if (isOnline()) {
      try {
        return Recognition.Text(groq.transcribe(wav), offline = false)
      } catch (e: CancellationException) {
        throw e
      } catch (e: GroqClient.GroqException) {
        Log.w(TAG, "Cloud speech recognition failed (${e.kind}); trying on-device")
        cloudError = e
      }
    }
    val availability = offlineRecognizer.availability()
    if (availability == OfflineSpeechRecognizer.Availability.READY) {
      val parsed = Wav.parse(wav)
      val text = parsed?.let { offlineRecognizer.transcribe(it) }
      if (text != null) return Recognition.Text(text, offline = true)
      return Recognition.Failed(DIDNT_CATCH)
    }
    cloudError?.let { return Recognition.Failed(errorPhrase(it, "Voice control")) }
    return Recognition.Failed(
        if (availability == OfflineSpeechRecognizer.Availability.MODEL_MISSING) {
          "No internet, and offline English recognition isn't downloaded yet. Connect to the " +
              "internet to download it. Obstacle alerts still work."
        } else {
          "No internet. Voice commands don't work, but obstacle alerts still work."
        },
    )
  }

  private fun isOnline(): Boolean =
      connectivity
          ?.getNetworkCapabilities(connectivity.activeNetwork)
          ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

  /** Sends one fresh camera frame to the vision model. Only on explicit request. */
  private suspend fun describeScene(): String {
    _state.update { it.copy(phase = Phase.DESCRIBING) }
    val frame =
        when (val capture = actions.captureFrame()) {
          is FrameCapture.Ok -> capture.frame
          FrameCapture.CameraOff -> return CAMERA_OFF
          FrameCapture.NoFrame -> return NO_IMAGE
        }
    tts.speak("One moment, looking.", interrupt = true)
    return try {
      val jpeg = withContext(Dispatchers.Default) { describer.toJpeg(frame) }
      val started = SystemClock.elapsedRealtime()
      val text = describer.describe(jpeg)
      Log.i(
          TAG,
          "Scene described in ${SystemClock.elapsedRealtime() - started} ms " +
              "(${frame.width}x${frame.height} frame, ${jpeg.size / 1024} KB JPEG)",
      )
      text.ifBlank { "Couldn't describe the surroundings. Please try again." }
    } catch (e: CancellationException) {
      throw e
    } catch (e: GroqClient.GroqException) {
      Log.w(TAG, "Scene description failed: ${e.kind} ${e.message}")
      errorPhrase(e, "Scene description")
    }
  }

  private suspend fun execute(command: VoiceCommand): String =
      when (command) {
        is VoiceCommand.Scene -> actions.sceneAnswer(command.zone)
        VoiceCommand.StopAssistance -> {
          actions.setAssistanceEnabled(false)
          "Alerts are off. Say start to turn them back on."
        }
        VoiceCommand.StartAssistance -> {
          actions.setAssistanceEnabled(true)
          "Alerts are on."
        }
        VoiceCommand.Repeat -> tts.lastSpoken ?: "Nothing to repeat yet."
        VoiceCommand.Help -> HELP_TEXT
        VoiceCommand.Describe -> error("Describe is handled by describeScene()")
        is VoiceCommand.FindNearest -> {
          _state.update { it.copy(phase = Phase.SEARCHING) }
          actions.findPlace(command.place)
        }
        is VoiceCommand.Navigate -> {
          _state.update { it.copy(phase = Phase.SEARCHING) }
          actions.navigate(command.place)
        }
        VoiceCommand.NavigateToFound -> {
          _state.update { it.copy(phase = Phase.SEARCHING) }
          actions.navigate(null)
        }
        VoiceCommand.StopNavigation -> actions.stopNavigation()
        VoiceCommand.NavigationStatus -> actions.navigationStatus()
        is VoiceCommand.Unknown ->
            "Sorry, I didn't understand. Say help to hear the list of commands."
      }

  /** Cloud fallback for phrases the local parser didn't understand; null on any failure. */
  private suspend fun classifyWithLlm(text: String): VoiceCommand? =
      try {
        val json = groq.chatJson(intentPrompt(text))
        val place = json.optString("place").takeIf { it.isNotBlank() && it != "null" }
        when (json.optString("command")) {
          "scene_ahead" -> VoiceCommand.Scene(SceneZone.AHEAD)
          "scene_left" -> VoiceCommand.Scene(SceneZone.LEFT)
          "scene_right" -> VoiceCommand.Scene(SceneZone.RIGHT)
          "scene_all" -> VoiceCommand.Scene(SceneZone.ALL)
          "describe" -> VoiceCommand.Describe
          "find" -> place?.let { VoiceCommand.FindNearest(it) }
          "navigate" -> place?.let { VoiceCommand.Navigate(it) } ?: VoiceCommand.NavigateToFound
          "nav_stop" -> VoiceCommand.StopNavigation
          "nav_status" -> VoiceCommand.NavigationStatus
          "stop" -> VoiceCommand.StopAssistance
          "start" -> VoiceCommand.StartAssistance
          "repeat" -> VoiceCommand.Repeat
          "help" -> VoiceCommand.Help
          else -> null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "LLM command fallback failed: ${e.message}")
        null
      }

  private fun answer(text: String, interrupt: Boolean = true) {
    _state.update { it.copy(phase = Phase.IDLE, answer = text) }
    Log.i(TAG, "Answer: $text")
    tts.speak(text, interrupt = interrupt)
  }

  /** Spoken explanation of a cloud failure; [feature] is e.g. "Voice control". */
  private fun errorPhrase(e: GroqClient.GroqException, feature: String): String =
      when (e.kind) {
        GroqClient.Kind.NO_KEY ->
            "$feature is unavailable: the service key isn't configured. Alerts still work."
        GroqClient.Kind.NO_NETWORK ->
            "No internet. $feature doesn't work, but obstacle alerts still work."
        GroqClient.Kind.RATE_LIMITED ->
            "The service is busy. Wait ${secondsPhrase(e.retryAfterSeconds ?: 30)} and try again."
        GroqClient.Kind.HTTP,
        GroqClient.Kind.BAD_RESPONSE -> "The service didn't respond. Please try again."
      }

  private fun secondsPhrase(seconds: Int): String {
    val n = seconds.coerceIn(1, 3600)
    return if (n == 1) "1 second" else "$n seconds"
  }

  private fun beep(toneType: Int) {
    runCatching { tone?.startTone(toneType, 150) }
  }

  private fun debugTestWav(): File? =
      if (BuildConfig.DEBUG) {
        appContext
            .getExternalFilesDir(null)
            ?.listFiles { f -> f.name.startsWith(DEBUG_TEST_WAV_PREFIX) && f.name.endsWith(".wav") }
            ?.maxByOrNull { it.lastModified() }
      } else {
        null
      }

  fun release() {
    autoStopJob?.cancel()
    recorder.release()
    offlineRecognizer.release()
    tone?.release()
  }

  private fun intentPrompt(text: String): String =
      """
      You interpret a voice command from a blind user. Return only JSON like {"command": "<one value from the list>", "place": <string or null>}.
      Values of command:
      scene_ahead — asks what is ahead or in front of them;
      scene_left — what is on the left;
      scene_right — what is on the right;
      scene_all — briefly, what is visible in general;
      describe — asks for a detailed description of the surroundings;
      find — find the nearest place (place — what to look for, 1–3 words, singular, no article);
      navigate — build a route or guide them somewhere (place — where to, 1–3 words, singular, no article; null if no place is named);
      nav_stop — stop or cancel the route;
      nav_status — how much of the route is left;
      stop — turn the alerts off;
      start — turn the alerts on;
      repeat — repeat the last message;
      help — asks what the assistant can do;
      unknown — none of these.
      Command: «${text.replace("»", "").take(300)}»
      """
          .trimIndent()
}
