/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraViewModel - DAT camera lifecycle, capture, and recording
//
// Drives the camera screen by exercising the SDK's camera lifecycle as explicit steps: create and
// start a DeviceSession, add and start a Stream, capture a photo or record video, stop the stream,
// end the session. Owns the screen-scoped pieces — the DeviceSession, its Stream, the on-device
// HEVC preview decoder, and the passthrough video recorder. The UI binds to the SDK's own
// DeviceSessionState / StreamState so the sample shows the real state machine.
//
// QolAIJol: Start / Stop take the session and stream steps together, and the phone buttons, the
// glasses gestures and the notification buttons all go through onControl. Camera state changes and
// errors are spoken, since a blind user can't see the screen.

package com.qolaijol.app.camera

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.qolaijol.app.R
import com.qolaijol.app.audio.TtsManager
import com.qolaijol.app.config.ApiKeys
import com.qolaijol.app.control.ControlAction
import com.qolaijol.app.control.ControlPhrases
import com.qolaijol.app.control.GlassesGestures
import com.qolaijol.app.control.NotificationActionReceiver
import com.qolaijol.app.decision.DecisionEngine
import com.qolaijol.app.decision.Priority
import com.qolaijol.app.decision.SceneAnalyzer
import com.qolaijol.app.meta.MetaCameraManager
import com.qolaijol.app.navigation.NavigationManager
import com.qolaijol.app.stream.AudioInputHandler
import com.qolaijol.app.stream.HevcDecoder
import com.qolaijol.app.stream.HevcParameterSetCollector
import com.qolaijol.app.stream.RecordingResult
import com.qolaijol.app.stream.StreamingService
import com.qolaijol.app.stream.VideoRecorder
import com.qolaijol.app.usb.UsbCameraManager
import com.qolaijol.app.vision.CameraFrame
import com.qolaijol.app.vision.DetectionResult
import com.qolaijol.app.vision.ObjectTracker
import com.qolaijol.app.vision.VisionPipeline
import com.qolaijol.app.voice.OfflineSpeechRecognizer
import com.qolaijol.app.voice.SceneZone
import com.qolaijol.app.voice.VoiceAssistant
import com.qolaijol.app.voice.VoiceCommand
import com.qolaijol.app.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CameraViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:CameraViewModel"
    private const val DECISION_TAG = "QolAIJol/Decision"
    private const val CONFIG_TAG = "QolAIJol/Config"
    private const val CONTROL_TAG = "QolAIJol/Control"
    private const val FRAME_REQUEST_TIMEOUT_MS = 2_000L
    private const val LOCATION_PERMISSION_NEEDED =
        "Navigation needs precise location access. Please allow it in the app settings."
    // After "Download" in the language prompt: look for the new voice / speech model this often,
    // for up to 3 minutes (the downloads run in the background).
    private const val LANGUAGE_CHECK_INTERVAL_MS = 5_000L
    private const val LANGUAGE_CHECKS = 36
    private const val KEYFRAME_WAIT_STEP_MS = 25L
    private const val KEYFRAME_WAIT_MAX_MS = 500L
    // The chosen camera (glasses or USB webcam) is remembered between runs.
    const val PREFS_NAME = "qolaijol_settings"
    private const val KEY_CAMERA_SOURCE = "camera_source"
  }

  private class RecorderFrame(
      val data: ByteArray,
      val presentationTimeUs: Long,
      val width: Int,
      val height: Int,
      val isCodecConfig: Boolean,
  )

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector

  private val _uiState = MutableStateFlow(CameraUiState())
  val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  // Recording pieces. The single compressed-HEVC stream feeds both the on-screen decoder and the
  // passthrough MP4 writer.
  private val audioInputHandler = AudioInputHandler(application)
  private val videoRecorder = VideoRecorder(application, viewModelScope)

  // Preview decode runs off the main thread; single-threaded so frames stay in order.
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)

  // Recording muxes on a serialized dispatcher fed by an unbounded channel, so it never stalls the
  // live preview and keeps frames in order.
  private val recorderDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val recorderChannel = Channel<RecorderFrame>(Channel.UNLIMITED)

  // Guards decoder create/teardown so a frame can't bind a new decoder to a Surface that
  // setSurface(null) just released — the @Volatile refs alone can't fix that check-then-act.
  private val decoderLock = Any()

  // @Volatile: single refs shared by the frame-collector and main threads, nulled at teardown.
  @Volatile private var hevcDecoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null

  // Accumulates the stream's HEVC parameter sets (VPS/SPS/PPS) so a recording (or decoder) started
  // mid-stream can be primed with a complete format. The SDK emits the VPS once at stream start, so
  // a partial set yields an unfinalizable file ("Missing codec specific data"). Internally
  // synchronized.
  private val csdCollector = HevcParameterSetCollector()

  // QolAIJol raw-frame pipeline: Meta frames -> CameraFrame -> preview + YOLO.
  private val metaCameraManager = MetaCameraManager()
  private val previewRenderer = PreviewRenderer()
  private val visionPipeline = VisionPipeline(application)

  // QolAIJol assistance: detections -> tracked objects -> alert -> speech.
  private val objectTracker = ObjectTracker()
  private val decisionEngine = DecisionEngine()
  private val ttsManager = TtsManager(application)

  // QolAIJol walking navigation (2GIS); quiet while a voice command is being handled.
  private val navigationManager: NavigationManager =
      NavigationManager(application, viewModelScope, ttsManager, isQuiet = { voiceAssistant.isBusy })

  // Set by the camera screen while it exists. The activity shows a permission dialog only while it
  // is in the foreground; a glasses gesture can come while the phone is locked.
  private var requestRecordAudioPermission: (suspend () -> Boolean)? = null
  private var requestLocationPermission: (suspend () -> Boolean)? = null

  private suspend fun ensureMicPermission(): Boolean =
      ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED || requestRecordAudioPermission?.invoke() == true

  private suspend fun ensureLocationPermission(): Boolean {
    if (navigationManager.hasPermission()) return true
    if (requestLocationPermission?.invoke() != true) return false
    // Granted just now: the foreground service re-enters the foreground so the route keeps
    // following the user while the phone is locked.
    if (_uiState.value.hasStream) StreamingService.refresh(getApplication())
    return true
  }

  // QolAIJol: double tap / swipe / camera button on the glasses.
  private val glassesGestures = GlassesGestures(viewModelScope, ::onControl)

  // Set by Start: the stream starts as soon as the session does, and the session ends with the
  // stream, so for the user it is a single on / off.
  private var startTogether = false
  // Start is waiting for the microphone permission dialog; the session isn't created yet.
  private var startPending = false

  // QolAIJol: a USB webcam instead of the glasses camera. Created only while that source is
  // chosen, so glasses users don't load the UVC library.
  private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private var usbCamera: UsbCameraManager? = null
  private var usbJobs: List<Job> = emptyList()

  // QolAIJol voice commands (push-to-talk button).
  private val voiceAssistant: VoiceAssistant =
      VoiceAssistant(
          application,
          viewModelScope,
          ttsManager,
          object : VoiceAssistant.Actions {
            override fun sceneAnswer(zone: SceneZone): String {
              val state = _uiState.value
              if (!state.isCameraOn) return VoiceAssistant.CAMERA_OFF
              val result = state.vision.latestResult ?: return VoiceAssistant.NO_IMAGE
              return SceneAnalyzer.answer(zone, result.detections)
            }

            override fun setAssistanceEnabled(enabled: Boolean) {
              if (enabled) {
                // Start fresh so objects already in view are announced again.
                objectTracker.reset()
                decisionEngine.reset()
              }
              _uiState.update { it.copy(assistanceEnabled = enabled) }
              Log.i(DECISION_TAG, "Assistance ${if (enabled) "enabled" else "disabled"} by the user")
            }

            override suspend fun captureFrame(): VoiceAssistant.FrameCapture {
              if (!_uiState.value.isCameraOn) return VoiceAssistant.FrameCapture.CameraOff
              val request = CompletableDeferred<CameraFrame>()
              frameRequest = request
              val frame = withTimeoutOrNull(FRAME_REQUEST_TIMEOUT_MS) { request.await() }
              frameRequest = null
              return frame?.let { VoiceAssistant.FrameCapture.Ok(it) }
                  ?: VoiceAssistant.FrameCapture.NoFrame
            }

            override suspend fun findPlace(place: String): String =
                if (ensureLocationPermission()) navigationManager.find(place)
                else LOCATION_PERMISSION_NEEDED

            override suspend fun navigate(place: String?): String =
                if (ensureLocationPermission()) navigationManager.navigate(place)
                else LOCATION_PERMISSION_NEEDED

            override fun stopNavigation(): String = navigationManager.stop()

            override fun navigationStatus(): String = navigationManager.status()
          },
      )

  // Set by captureFrame(); the frame thread answers it with a copy of the next frame.
  @Volatile private var frameRequest: CompletableDeferred<CameraFrame>? = null

  private var languageCheckJob: Job? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var videoJob: Job? = null
  private var streamStateJob: Job? = null
  private var streamErrorJob: Job? = null

  init {
    videoRecorder.setAudioInputHandler(audioInputHandler)

    // Which cloud keys this build has (never their values). Local warnings don't need any.
    Log.i(CONFIG_TAG, "Cloud API keys: ${ApiKeys.describe()}")

    // Load the YOLO model in the background right away so it's ready when the stream starts.
    visionPipeline.start()
    viewModelScope.launch {
      visionPipeline.state.collect { vision -> _uiState.update { it.copy(vision = vision) } }
    }
    // One collector, so tracker and decision engine always see results in order.
    viewModelScope.launch(Dispatchers.Default) {
      visionPipeline.results.collect { result -> onDetectionResult(result) }
    }
    viewModelScope.launch {
      ttsManager.status.collect { status -> _uiState.update { it.copy(voiceStatus = status) } }
    }
    viewModelScope.launch {
      voiceAssistant.state.collect { voice -> _uiState.update { it.copy(voice = voice) } }
    }
    viewModelScope.launch {
      voiceAssistant.offlineModel.collect { model ->
        _uiState.update { it.copy(offlineSpeech = model) }
      }
    }
    viewModelScope.launch {
      navigationManager.state.collect { nav -> _uiState.update { it.copy(navigation = nav) } }
    }

    // Spoken, since a blind user can't see the screen: camera on, paused, resumed, off.
    viewModelScope.launch {
      var previous = CameraStatus.OFF
      uiState
          .map { it.cameraStatus }
          .distinctUntilChanged()
          .collect { status ->
            // Only "Paused" cuts in: "Camera off" queues, so an error that explains it (USB camera
            // unplugged) is still heard.
            ControlPhrases.cameraStatusChange(previous, status, _uiState.value.assistanceEnabled)
                ?.let { ttsManager.speak(it, interrupt = status == CameraStatus.PAUSED) }
            previous = status
          }
    }
    if (prefs.getString(KEY_CAMERA_SOURCE, null) == CameraSource.USB.name) {
      useCameraSource(CameraSource.USB)
    }
    // Glasses gestures: shown on screen, and explained by voice once per run (counted only once
    // actually spoken: the voice may still be starting up).
    viewModelScope.launch {
      var toldOn = false
      var toldUnavailable = false
      glassesGestures.status.collect { status ->
        _uiState.update { it.copy(gestures = status) }
        if (status == GlassesGestures.Status.ON && !toldOn) {
          toldOn = ttsManager.speak(ControlPhrases.GESTURES_ON)
        } else if (status == GlassesGestures.Status.UNAVAILABLE && !toldUnavailable) {
          toldUnavailable = ttsManager.speak(ControlPhrases.GESTURES_UNAVAILABLE)
        }
      }
    }
    viewModelScope.launch { NotificationActionReceiver.actions.collect { onControl(it) } }

    // Drain recording frames off the preview thread.
    viewModelScope.launch(recorderDispatcher) {
      for (frame in recorderChannel) {
        videoRecorder.writeCompressedFrame(
            frame.data,
            frame.presentationTimeUs,
            frame.width,
            frame.height,
            frame.isCodecConfig,
        )
      }
    }

    // Mirror the recorder's intent/elapsed into UI state.
    viewModelScope.launch {
      videoRecorder.isRecording.collect { recording ->
        _uiState.update { it.copy(isRecording = recording) }
      }
    }
    viewModelScope.launch {
      videoRecorder.recordingElapsedSeconds.collect { seconds ->
        _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
      }
    }
    viewModelScope.launch {
      videoRecorder.audioActive.collect { active ->
        if (!active && videoRecorder.isRecording.value && _uiState.value.includeAudioInStream) {
          _uiState.update { it.copy(includeAudioInStream = false) }
        }
      }
    }

    // Keyed on the same predicate the scaffold navigates on, so a cancelled unregistration — which
    // reverts to REGISTERED — leaves an in-flight session untouched.
    viewModelScope.launch {
      wearablesViewModel.uiState
          .map { it.isRegistered }
          .distinctUntilChanged()
          .collect { isRegistered -> if (!isRegistered) handleUnregistered() }
    }
  }

  // MARK: - Surface

  fun setSurface(surface: Surface?) {
    previewRenderer.setSurface(surface)
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        hevcDecoder?.stop()
        hevcDecoder = null
      }
    }
  }

  // MARK: - Lifecycle step 1: session

  /** Creates and starts a [DeviceSession] (no stream yet). */
  fun startSession() {
    if (_uiState.value.hasSession) return
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to start session: ${error.description}")
          reportError(error.getLocalizedDescription(getApplication()))
          cleanupSession()
        }
  }

  /**
   * Ends the device session. The stream and any in-progress recording are not torn down here
   * directly — stopping the session drives the SDK's stream to a terminal state, which the
   * stream-state collector observes (see [onStreamTerminated]) to stop recording and release stream
   * resources.
   */
  fun endSession() {
    val current = session ?: return
    _uiState.update { it.copy(sessionState = DeviceSessionState.STOPPING) }
    current.stop()
  }

  private fun observeSession(session: DeviceSession) {
    sessionStateJob = viewModelScope.launch {
      session.state.collect { state ->
        _uiState.update { it.copy(sessionState = state) }
        when (state) {
          DeviceSessionState.STARTED -> {
            glassesGestures.attach(session)
            if (startTogether && stream == null) startStreaming()
          }
          DeviceSessionState.STOPPED -> cleanupSession()
          else -> Unit
        }
      }
    }
    sessionErrorJob = viewModelScope.launch {
      session.errors.collect { error ->
        // All session errors surface through the snackbar and speech, including
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED, which the SDK delivers as a one-shot event.
        Log.e(TAG, "Session error: ${error.description}")
        reportError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun cleanupSession() {
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    session = null
    glassesGestures.detach()
    startTogether = false
  }

  // MARK: - QolAIJol: one-step Start / Stop and the shared controls

  /**
   * The phone's Start button: connects to the glasses and turns the camera on in one step (or opens
   * the USB webcam, if that is the chosen camera). Asks for the microphone first, so the foreground
   * service can take voice commands while the phone is locked.
   */
  fun startAssistance() {
    if (_uiState.value.cameraSource == CameraSource.USB) {
      startUsbCamera()
      return
    }
    val state = _uiState.value
    if (state.hasStream || state.isBusy || startPending) return
    if (!wearablesViewModel.uiState.value.hasActiveDevice) {
      ttsManager.speak(ControlPhrases.NO_GLASSES, interrupt = true)
      return
    }
    ttsManager.speak(ControlPhrases.STARTING, interrupt = true)
    startTogether = true
    if (state.isSessionActive) {
      startStreaming()
      return
    }
    startPending = true
    viewModelScope.launch {
      try {
        ensureMicPermission()
      } finally {
        startPending = false
      }
      startSession()
    }
  }

  /** The phone's Stop button and the notification's Stop: camera off, session ended. */
  fun stopAssistance() {
    if (_uiState.value.cameraSource == CameraSource.USB) {
      usbCamera?.stop()
      return
    }
    if (session != null) endSession() else stopStreaming()
  }

  /** The camera choice button: glasses <-> USB webcam. Only while everything is off. */
  fun toggleCameraSource() {
    val state = _uiState.value
    if (state.isActive || startPending) return
    val next =
        if (state.cameraSource == CameraSource.GLASSES) CameraSource.USB else CameraSource.GLASSES
    useCameraSource(next)
    prefs.edit { putString(KEY_CAMERA_SOURCE, next.name) }
    ttsManager.speak(
        if (next == CameraSource.USB) ControlPhrases.SOURCE_USB else ControlPhrases.SOURCE_GLASSES,
        interrupt = true,
    )
  }

  private fun useCameraSource(source: CameraSource) {
    _uiState.update { it.copy(cameraSource = source) }
    if (source == CameraSource.GLASSES) {
      usbJobs.forEach { it.cancel() }
      usbJobs = emptyList()
      usbCamera?.release()
      usbCamera = null
      _uiState.update {
        it.copy(usbCamera = UsbCameraManager.State.OFF, usbCameraAttached = false)
      }
      return
    }
    if (usbCamera != null) return
    val manager = UsbCameraManager(getApplication(), ::onCameraFrame, ::onUsbProblem)
    usbCamera = manager
    usbJobs =
        listOf(
            viewModelScope.launch {
              manager.attached.collect { attached ->
                _uiState.update { it.copy(usbCameraAttached = attached) }
              }
            },
            viewModelScope.launch {
              var previous = UsbCameraManager.State.OFF
              manager.state.collect { state ->
                _uiState.update { it.copy(usbCamera = state) }
                if (state == UsbCameraManager.State.OFF && previous != state) onUsbCameraStopped()
                previous = state
              }
            },
        )
  }

  private fun startUsbCamera() {
    val manager = usbCamera ?: return
    if (manager.state.value != UsbCameraManager.State.OFF || startPending) return
    if (!manager.attached.value) {
      onUsbProblem(UsbCameraManager.Problem.NOT_CONNECTED)
      return
    }
    ttsManager.speak(ControlPhrases.STARTING, interrupt = true)
    startPending = true
    viewModelScope.launch {
      try {
        ensureMicPermission()
      } finally {
        startPending = false
      }
      // As with the glasses: alerts and voice commands keep going while the phone is locked.
      StreamingService.start(getApplication())
      manager.start()
      if (manager.state.value == UsbCameraManager.State.OFF) StreamingService.stop(getApplication())
    }
  }

  private fun onUsbCameraStopped() {
    StreamingService.stop(getApplication())
    visionPipeline.clearResults()
    // The next start begins fresh: objects get announced again.
    objectTracker.reset()
    decisionEngine.reset()
    _uiState.update { it.copy(hasReceivedFirstFrame = false, latestAlert = null) }
  }

  private fun onUsbProblem(problem: UsbCameraManager.Problem) {
    reportError(
        when (problem) {
          UsbCameraManager.Problem.NOT_CONNECTED -> ControlPhrases.USB_NOT_CONNECTED
          UsbCameraManager.Problem.PERMISSION_DENIED -> ControlPhrases.USB_PERMISSION_DENIED
          UsbCameraManager.Problem.OPEN_FAILED -> ControlPhrases.USB_OPEN_FAILED
          UsbCameraManager.Problem.DISCONNECTED -> ControlPhrases.USB_DISCONNECTED
        },
    )
  }

  /** One entry point for the phone buttons, the glasses gestures and the notification buttons. */
  fun onControl(action: ControlAction) {
    Log.i(CONTROL_TAG, "Control: $action")
    when (action) {
      ControlAction.SPEAK -> voiceAssistant.onButton(::ensureMicPermission)
      ControlAction.WHATS_AROUND -> voiceAssistant.runCommand(VoiceCommand.Scene(SceneZone.ALL))
      ControlAction.DESCRIBE -> voiceAssistant.runCommand(VoiceCommand.Describe)
      ControlAction.REPEAT -> voiceAssistant.runCommand(VoiceCommand.Repeat)
      ControlAction.TOGGLE_ALERTS ->
          voiceAssistant.runCommand(
              if (_uiState.value.assistanceEnabled) VoiceCommand.StopAssistance
              else VoiceCommand.StartAssistance,
          )
      ControlAction.STOP -> stopAssistance()
    }
  }

  /** Registered by the camera screen for its lifetime; null clears them. */
  fun setPermissionRequests(
      recordAudio: (suspend () -> Boolean)?,
      location: (suspend () -> Boolean)?,
  ) {
    requestRecordAudioPermission = recordAudio
    requestLocationPermission = location
  }

  /** The camera couldn't be turned on after Start: end the session too, back to plain "off". */
  private fun onStreamStartFailed() {
    if (startTogether) endSession()
  }

  /** Shows [message] and says it: a blind user can't read the snackbar. */
  private fun reportError(message: String) {
    wearablesViewModel.setRecentError(message)
    ttsManager.speak(message, interrupt = true)
  }

  // MARK: - Lifecycle step 2: stream (preview)

  /**
   * Starts the camera stream (preview). Requires an active session. Camera permission is checked
   * first (a query, no redirect); if it isn't granted, the actual request — which redirects to the
   * Meta AI app — is deferred to [confirmCameraPermissionRedirect] so the app-switch is confirmed.
   */
  fun startStreaming() {
    if (!_uiState.value.isSessionActive) {
      reportError(getApplication<Application>().getString(R.string.error_start_session_first))
      return
    }
    if (stream != null || _uiState.value.isStartingStream) return
    _uiState.update { it.copy(isStartingStream = true) }
    viewModelScope.launch {
      try {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
              if (status == PermissionStatus.Granted) {
                beginStream()
              } else {
                _uiState.update { it.copy(showCameraPermissionRedirectConfirm = true) }
              }
            }
            .onFailure { error, _ ->
              Log.e(TAG, "Failed to check camera permission: ${error.description}")
              reportError(error.getLocalizedDescription(getApplication()))
              onStreamStartFailed()
            }
      } finally {
        _uiState.update { it.copy(isStartingStream = false) }
      }
    }
  }

  /** Confirmed from the permission prompt: requests camera access, then starts the stream. */
  fun confirmCameraPermissionRedirect(
      requestPermission: suspend (Permission) -> PermissionStatus,
  ) {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    if (!_uiState.value.isSessionActive || stream != null) return
    viewModelScope.launch {
      val status = requestPermission(Permission.CAMERA)
      if (status == PermissionStatus.Granted) {
        beginStream()
      } else {
        reportError(
            getApplication<Application>().getString(R.string.error_camera_permission_denied),
        )
        onStreamStartFailed()
      }
    }
  }

  fun cancelCameraPermissionRedirect() {
    _uiState.update { it.copy(showCameraPermissionRedirectConfirm = false) }
    onStreamStartFailed()
  }

  private fun beginStream() {
    val current = session ?: return
    if (stream != null) return
    // Foreground service keeps the stream/recording alive while backgrounded.
    StreamingService.start(getApplication())
    current
        // QolAIJol: raw (uncompressed) frames for the vision pipeline. HEVC recording/preview is
        // intentionally not used; the recording code below stays in place but receives no frames.
        .addCamera(MetaCameraManager.STREAM_CONFIGURATION)
        .onSuccess { addedCamera ->
          camera = addedCamera
          val added = addedCamera.stream
          stream = added
          // Subscribe before start() so no initial transitions are missed.
          setupStreamListeners(added)
          _uiState.update { it.copy(streamState = StreamState.STARTING) }
          added.start().onFailure { error, _ ->
            Log.e(TAG, "Failed to start stream: ${error.description}")
            reportError(error.getLocalizedDescription(getApplication()))
            // A failed start leaves the stream attached and the FGS running — tear both down so the
            // UI doesn't stick at STARTING, matching the addCamera() failure path below.
            clearStreamResources()
            onStreamStartFailed()
          }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to add camera: ${error.description}")
          StreamingService.stop(getApplication())
          reportError(error.getLocalizedDescription(getApplication()))
          onStreamStartFailed()
        }
  }

  /** Stops the camera stream but keeps the [DeviceSession] connected. */
  fun stopStreaming() {
    val current = camera ?: return
    _uiState.update { it.copy(streamState = StreamState.STOPPING) }
    current.stop()
    // The stream-state collector converges teardown when STOPPED/CLOSED arrives.
  }

  private fun setupStreamListeners(stream: Stream) {
    videoJob =
        viewModelScope.launch(frameDispatcher) {
          stream.videoStream.collect { handleVideoFrame(it) }
        }
    streamStateJob = viewModelScope.launch {
      // state replays its current value (STOPPED) on subscribe, and we subscribe before start().
      var hasBeenActive = false
      stream.state.collect { state ->
        _uiState.update { it.copy(streamState = state) }
        val isTerminal = state == StreamState.STOPPED || state == StreamState.CLOSED
        if (!isTerminal) {
          if (!hasBeenActive && _uiState.value.includeAudioInStream) {
            viewModelScope.launch {
              try {
                videoRecorder.prewarmGlassesAudio()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.e(TAG, "stream-active glasses audio prewarm failed", e)
              }
            }
          }
          hasBeenActive = true
        } else if (hasBeenActive) {
          hasBeenActive = false
          onStreamTerminated()
        }
      }
    }
    streamErrorJob = viewModelScope.launch {
      stream.errorStream.collect { error ->
        Log.e(TAG, "Stream error: ${error.description}")
        // Not spoken: most stream errors are informational, and one that ends the stream is
        // announced as "Camera off".
        wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    if (!videoFrame.isCompressed) {
      handleRawFrame(videoFrame)
      return
    }

    val buffer = videoFrame.buffer
    val width = videoFrame.width
    val height = videoFrame.height
    val presentationTimeUs = videoFrame.presentationTimeUs

    val byteArray = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    // Accumulate the parameter sets so a recording (or decoder) started after stream start can be
    // primed with a complete VPS+SPS+PPS set.
    csdCollector.offer(byteArray)

    // Feed the preview decoder first so recorder muxing never delays it.
    // Lazily create the decoder once a Surface is available; it renders directly to it. Prime it
    // with the cached config in case the surface arrived after the config frame. Guarded so a
    // concurrent setSurface(null) can't leave a decoder bound to a released Surface.
    synchronized(decoderLock) {
      val surface = decoderSurface
      if (hevcDecoder == null && surface != null) {
        hevcDecoder =
            HevcDecoder().also { decoder ->
              decoder.start(width, height, surface)
              csdCollector.complete()?.let { decoder.decodeFrame(it, 0) }
            }
      }
      // Feed under the lock so teardown can't null the decoder between check and feed.
      hevcDecoder?.decodeFrame(byteArray, presentationTimeUs)
    }

    // Hand off to the recorder thread; never blocks the preview.
    if (videoRecorder.isRecording.value) {
      recorderChannel.trySend(
          RecorderFrame(byteArray, presentationTimeUs, width, height, videoFrame.isCodecConfig),
      )
    }

    if (!videoFrame.isCodecConfig && !_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  private fun handleRawFrame(videoFrame: VideoFrame) {
    metaCameraManager.toCameraFrame(videoFrame)?.let(::onCameraFrame)
  }

  /**
   * One frame from the glasses (on frameDispatcher) or the USB webcam (on its capture thread).
   * Only one source runs at a time, each on a single thread, so the reused frame buffer is never
   * shared.
   */
  private fun onCameraFrame(frame: CameraFrame) {
    previewRenderer.render(frame, visionPipeline.latestDetections)
    visionPipeline.onFrame(frame)
    frameRequest?.let { request ->
      // The frame buffer is reused for the next frame, so hand over a copy.
      if (!request.isCompleted) {
        request.complete(CameraFrame(frame.i420.copyOf(), frame.width, frame.height, frame.timestampUs))
      }
    }
    if (!_uiState.value.hasReceivedFirstFrame) {
      _uiState.update { it.copy(hasReceivedFirstFrame = true) }
    }
  }

  private fun onDetectionResult(result: DetectionResult) {
    // A result can land just after the user stopped the preview; don't speak for a dead stream.
    if (!_uiState.value.isCameraOn) return
    val nowMs = SystemClock.elapsedRealtime()
    // Keep tracking even while quiet: otherwise objects are forgotten during a voice command and
    // announced again as "new" right after the answer.
    val visible = objectTracker.update(result.detections, nowMs)
    // Quiet while the user gives a voice command, and when they said "stop".
    if (voiceAssistant.isBusy || !_uiState.value.assistanceEnabled) return
    val alert = decisionEngine.process(visible, nowMs) ?: return
    Log.i(
        DECISION_TAG,
        "Alert generated: ${alert.message} (priority=${alert.priority}, track=${alert.trackId})",
    )
    ttsManager.speak(alert.message, interrupt = alert.priority == Priority.CRITICAL)
    _uiState.update { it.copy(latestAlert = alert.message) }
  }

  /** "End route" button. */
  fun stopNavigation() {
    ttsManager.speak(navigationManager.stop(), interrupt = true)
  }

  /** The text-to-speech engine's screen for downloading the English voice. */
  fun englishVoiceInstallIntent(): Intent = ttsManager.installVoiceIntent()

  /**
   * "Download" in the language prompt (the screen has already opened the voice installer if the
   * voice was missing): requests the offline speech model, then keeps checking for a while so the
   * new voice and model are used as soon as they arrive.
   */
  fun onLanguageDownloadRequested() {
    _uiState.update { it.copy(languagePromptAnswered = true) }
    languageCheckJob?.cancel()
    languageCheckJob =
        viewModelScope.launch {
          if (_uiState.value.needsOfflineSpeech) voiceAssistant.downloadOfflineModel()
          repeat(LANGUAGE_CHECKS) {
            delay(LANGUAGE_CHECK_INTERVAL_MS)
            recheckLanguageDataNow()
            val state = _uiState.value
            if (!state.needsEnglishVoice && !state.needsOfflineSpeech) return@launch
          }
        }
  }

  fun dismissLanguagePrompt() {
    _uiState.update { it.copy(languagePromptAnswered = true) }
  }

  /** Picks up a voice or speech model installed while the app was in the background. */
  fun recheckLanguageData() {
    viewModelScope.launch { recheckLanguageDataNow() }
  }

  private suspend fun recheckLanguageDataNow() {
    ttsManager.recheck()
    if (_uiState.value.offlineSpeech == OfflineSpeechRecognizer.Availability.MODEL_MISSING) {
      voiceAssistant.checkOfflineModel()
    }
  }

  private fun onStreamTerminated() {
    // Finalize an in-progress recording before releasing the foreground service / wake lock, so the
    // MP4 mux on Dispatchers.IO isn't cut off when the stream stops while backgrounded.
    viewModelScope.launch {
      if (_uiState.value.isRecording) {
        stopVideoRecording()
      }
      try {
        videoRecorder.releaseGlassesAudioPrewarm()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "onStreamTerminated glasses audio release failed", e)
      }
      clearStreamResources()
      // Turned on with Start: the session goes with the stream, so on / off stays simple.
      val sessionState = _uiState.value.sessionState
      if (
          startTogether &&
              (sessionState == DeviceSessionState.STARTED ||
                  sessionState == DeviceSessionState.PAUSED)
      ) {
        endSession()
      }
    }
  }

  private fun clearStreamResources() {
    videoJob?.cancel()
    videoJob = null
    streamStateJob?.cancel()
    streamStateJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    synchronized(decoderLock) {
      hevcDecoder?.stop()
      hevcDecoder = null
    }
    csdCollector.reset()
    metaCameraManager.reset()
    visionPipeline.clearResults()
    // A new stream starts fresh: objects get announced again.
    objectTracker.reset()
    decisionEngine.reset()
    StreamingService.stop(getApplication())
    // STOPPED is restartable, so only stop() detaches the capability; without it the next
    // addCamera() fails with "a capability of this type is already active". Stopping the camera
    // cascades to its stream child.
    stopCamera(camera)
    camera = null
    stream = null
    _uiState.update {
      it.copy(streamState = StreamState.STOPPED, hasReceivedFirstFrame = false, latestAlert = null)
    }
  }

  /**
   * Stops the camera by closing it. Accepting a [java.io.Closeable] parameter satisfies the
   * AutoCloseableUse detector, which skips methods that take an AutoCloseable argument — the camera
   * outlives any single `use {}` block, so it is stopped explicitly here rather than auto-closed.
   */
  private fun stopCamera(capability: java.io.Closeable?) {
    capability?.close()
  }

  // MARK: - Capture

  fun capturePhoto() {
    if (_uiState.value.isCapturingPhoto || !_uiState.value.isStreaming) return
    _uiState.update { it.copy(isCapturingPhoto = true) }
    viewModelScope.launch {
      stream
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            // Decode/rotate is CPU-bound and blocking; keep it off the main thread so capture
            // doesn't jank the UI. Resumes on main for the state update.
            val bitmap = withContext(Dispatchers.Default) { decodePhoto(photoData) }
            if (bitmap != null) {
              _uiState.update {
                it.copy(isCapturingPhoto = false, activePreview = CapturePreview.Photo(bitmap))
              }
            } else {
              _uiState.update { it.copy(isCapturingPhoto = false) }
              wearablesViewModel.setRecentError(
                  getApplication<Application>().getString(R.string.error_photo_capture_failed),
              )
            }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Failed to capture photo: ${error.description}")
            _uiState.update { it.copy(isCapturingPhoto = false) }
            wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
          } ?: _uiState.update { it.copy(isCapturingPhoto = false) }
    }
  }

  // MARK: - Recording

  fun toggleRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (_uiState.value.isRecording) {
      viewModelScope.launch { stopVideoRecording() }
    } else {
      startVideoRecording(requestRecordAudioPermission)
    }
  }

  fun startVideoRecording(requestRecordAudioPermission: suspend () -> Boolean) {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    viewModelScope.launch {
      // Request the mic permission only when sound-in-video is on; record video-only if it's off or
      // the user declines. The prompt appears in context on the first record with the mic on.
      val includeAudio = _uiState.value.includeAudioInStream && requestRecordAudioPermission()
      if (!_uiState.value.isStreaming || _uiState.value.isRecording) return@launch
      // If the user wanted sound but denied the mic, reflect it so the mic icon doesn't stay "on".
      if (_uiState.value.includeAudioInStream && !includeAudio) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
      videoRecorder.setIncludeAudio(includeAudio)
      val audioEnabled = videoRecorder.startRecording(csdCollector.complete())
      // Glasses HFP audio may be unavailable even with the mic on; flip the icon off so it doesn't
      // imply audio is being captured.
      if (includeAudio && !audioEnabled) {
        _uiState.update { it.copy(includeAudioInStream = false) }
      }
    }
  }

  suspend fun stopVideoRecording() {
    if (!_uiState.value.isRecording) return
    // The writer starts on the first keyframe. If stop lands just before that frame, wait briefly
    // so even a quick recording finalizes to a file instead of being discarded.
    var waited = 0L
    while (!videoRecorder.hasStartedWriting.value && waited < KEYFRAME_WAIT_MAX_MS) {
      delay(KEYFRAME_WAIT_STEP_MS)
      waited += KEYFRAME_WAIT_STEP_MS
    }
    when (val result = videoRecorder.stopRecording()) {
      is RecordingResult.Completed ->
          _uiState.update { it.copy(activePreview = CapturePreview.Video(result.uri)) }
      RecordingResult.NoRecording ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_too_short),
          )
      RecordingResult.Failed ->
          wearablesViewModel.setRecentError(
              getApplication<Application>().getString(R.string.error_recording_save_failed),
          )
    }
  }

  fun toggleMic() {
    if (!_uiState.value.isStreaming || _uiState.value.isRecording) return
    val enabled = !_uiState.value.includeAudioInStream
    _uiState.update { it.copy(includeAudioInStream = enabled) }
    viewModelScope.launch {
      try {
        if (enabled) videoRecorder.prewarmGlassesAudio()
        else videoRecorder.releaseGlassesAudioPrewarm()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "toggleMic glasses audio prewarm/release failed", e)
      }
    }
  }

  // MARK: - Dismissers

  fun dismissCapturePreview() {
    val preview = _uiState.value.activePreview
    _uiState.update { it.copy(activePreview = null) }
    if (preview is CapturePreview.Video) {
      // The clip lives in the cache dir, exposed as a FileProvider content URI; delete through the
      // resolver so it resolves back to the real cache file (the URI's path is the provider
      // mapping,
      // not a filesystem path). Photos are held in memory — nothing on disk to clean up.
      viewModelScope.launch(Dispatchers.IO) {
        runCatching {
          getApplication<Application>().contentResolver.delete(preview.uri, null, null)
        }
            .onFailure { Log.w(TAG, "Failed to delete temp recording", it) }
      }
    }
  }

  // MARK: - Photo decoding

  private fun decodePhoto(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
      }

  // The glasses store orientation in an EXIF tag that BitmapFactory/ImageDecoder don't apply for
  // HEIC, so read TAG_ORIENTATION and rotate — otherwise the preview and shared image are sideways.
  private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
    val buffer = data.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      bitmap?.recycle()
      Log.e(TAG, "Failed to decode captured photo")
      return null
    }

    val matrix = exifOrientationMatrix(bytes)
    if (matrix.isIdentity) return bitmap

    // Rotating allocates a second full-size bitmap; recycle the source, and fall back to the
    // unrotated image if the device is too low on memory to make the copy.
    return try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
      }
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to rotate captured photo", e)
      bitmap
    }
  }

  private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
    val orientation =
        try {
          ByteArrayInputStream(bytes).use { input ->
            ExifInterface(input)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          }
        } catch (e: IOException) {
          Log.w(TAG, "Failed to read EXIF orientation", e)
          ExifInterface.ORIENTATION_NORMAL
        }
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return matrix
  }

  // MARK: - Unregistration

  /**
   * Ends the session once the app is no longer registered. Unregistering does not stop a live
   * [DeviceSession] — the SDK leaves that to the app — and this view model is scoped to the
   * activity rather than the camera screen, so nothing else would tear the session down before the
   * next registration brought the screen back mid-stream.
   *
   * Stopping is enough on its own: it closes the attached capabilities, and the resulting terminal
   * session and stream states drive the existing collectors through [onStreamTerminated] and
   * [cleanupSession].
   */
  private fun handleUnregistered() {
    session?.stop()
  }

  override fun onCleared() {
    super.onCleared()
    clearStreamResources()
    session?.stop()
    cleanupSession()
    audioInputHandler.cleanup()
    videoRecorder.close()
    recorderChannel.close()
    visionPipeline.close()
    previewRenderer.release()
    voiceAssistant.release()
    navigationManager.release()
    usbCamera?.release()
    ttsManager.shutdown()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST")
        return CameraViewModel(application, wearablesViewModel) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
