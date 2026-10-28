/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraUiState - Camera screen state
//
// Stores the SDK's own DeviceSessionState / StreamState directly and derives booleans from
// them, so the sample surfaces the real SDK state machine rather than a remapped one.

package com.qolaijol.app.camera

import android.graphics.Bitmap
import android.net.Uri
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.qolaijol.app.audio.TtsManager
import com.qolaijol.app.control.GlassesGestures
import com.qolaijol.app.navigation.NavigationManager
import com.qolaijol.app.usb.UsbCameraManager
import com.qolaijol.app.vision.VisionPipeline
import com.qolaijol.app.voice.OfflineSpeechRecognizer
import com.qolaijol.app.voice.VoiceAssistant

/** QolAIJol: where the frames come from. */
enum class CameraSource {
  /** The Meta glasses camera, through a DAT session. */
  GLASSES,
  /** A USB (UVC) webcam on the phone's USB-C port. */
  USB,
}

/** QolAIJol: whether the camera, and with it the obstacle alerts, is working. */
enum class CameraStatus {
  OFF,
  STARTING,
  ON,
  /** Paused by the glasses (a single tap, or taken off); a tap resumes it. */
  PAUSED,
}

/** A capture awaiting preview/share — a still photo or a recorded video file. */
sealed interface CapturePreview {
  data class Photo(val bitmap: Bitmap) : CapturePreview

  data class Video(val uri: Uri) : CapturePreview
}

data class CameraUiState(
    // Bound directly to the SDK state machines.
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    val streamState: StreamState = StreamState.STOPPED,
    // Flips once when the first preview frame arrives; drives the loading→preview swap. A one-shot
    // boolean (not a per-frame counter) keeps the screen from recomposing at the frame rate — the
    // SurfaceView renders frames independently of Compose.
    val hasReceivedFirstFrame: Boolean = false,
    // Capture / recording intent.
    val isCapturingPhoto: Boolean = false,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Long = 0L,
    // Sound-in-video toggle (glasses mic over HFP), locked once recording starts. Off in QolAIJol:
    // recording is not used with the raw-frame stream, so the glasses mic shouldn't be prewarmed.
    val includeAudioInStream: Boolean = false,
    // The capture currently shown in the shared preview/share sheet (photo or video).
    val activePreview: CapturePreview? = null,
    // Drives the confirm prompt shown before the camera-permission redirect to the Meta AI app.
    val showCameraPermissionRedirectConfirm: Boolean = false,
    // True while the stream-start flow is in flight before the SDK stream state turns STARTING —
    // i.e. while the camera-permission check runs. Folded into isBusy so the Preview button stays
    // disabled for the whole flow, closing the gap where the SDK state machine hasn't moved yet.
    val isStartingStream: Boolean = false,
    // QolAIJol: YOLO model status and latest detections.
    val vision: VisionPipeline.State = VisionPipeline.State(),
    // QolAIJol: text-to-speech status and the last alert the decision engine produced.
    val voiceStatus: TtsManager.Status = TtsManager.Status.INITIALIZING,
    // QolAIJol: on-device English speech recognition (null until checked), and whether the
    // "download English" prompt was already answered this run.
    val offlineSpeech: OfflineSpeechRecognizer.Availability? = null,
    val languagePromptAnswered: Boolean = false,
    val latestAlert: String? = null,
    // QolAIJol: voice commands and whether warnings are on ("stop" / "start").
    val voice: VoiceAssistant.State = VoiceAssistant.State(),
    val assistanceEnabled: Boolean = true,
    // QolAIJol: walking navigation.
    val navigation: NavigationManager.State = NavigationManager.State(),
    // QolAIJol: double tap / swipe / camera button on the glasses.
    val gestures: GlassesGestures.Status = GlassesGestures.Status.OFF,
    // QolAIJol: glasses or USB webcam, and the webcam's state.
    val cameraSource: CameraSource = CameraSource.GLASSES,
    val usbCamera: UsbCameraManager.State = UsbCameraManager.State.OFF,
    val usbCameraAttached: Boolean = false,
) {
  /** On / paused / off, as the user is told; stopping already counts as off. */
  val cameraStatus: CameraStatus
    get() =
        when {
          cameraSource == CameraSource.USB ->
              when (usbCamera) {
                UsbCameraManager.State.OFF -> CameraStatus.OFF
                UsbCameraManager.State.CONNECTING -> CameraStatus.STARTING
                UsbCameraManager.State.ON -> CameraStatus.ON
              }
          streamState == StreamState.PAUSED ||
              (hasStream && sessionState == DeviceSessionState.PAUSED) -> CameraStatus.PAUSED
          streamState == StreamState.STREAMING -> CameraStatus.ON
          isSessionStarting ||
              isStartingStream ||
              streamState == StreamState.STARTING ||
              streamState == StreamState.STARTED -> CameraStatus.STARTING
          else -> CameraStatus.OFF
        }

  /** The offline English text-to-speech voice is not installed. */
  val needsEnglishVoice: Boolean
    get() = voiceStatus == TtsManager.Status.NO_OFFLINE_VOICE

  /** Offline English speech recognition is supported but its model is not downloaded. */
  val needsOfflineSpeech: Boolean
    get() = offlineSpeech == OfflineSpeechRecognizer.Availability.MODEL_MISSING

  /** Show the "download English" prompt by itself, once per run, while something is missing. */
  val showLanguagePrompt: Boolean
    get() = !languagePromptAnswered && (needsEnglishVoice || needsOfflineSpeech)

  /** A session exists and is connected (or connecting); a stream can be started. */
  val hasSession: Boolean
    get() =
        sessionState == DeviceSessionState.STARTING ||
            sessionState == DeviceSessionState.STARTED ||
            sessionState == DeviceSessionState.PAUSED ||
            sessionState == DeviceSessionState.STOPPING

  val isSessionStarting: Boolean
    get() = sessionState == DeviceSessionState.STARTING

  val isSessionActive: Boolean
    get() = sessionState == DeviceSessionState.STARTED

  val isStreaming: Boolean
    get() = streamState == StreamState.STREAMING

  /**
   * The stream is paused by the device (single cap-touch tap); the last frame stays frozen on
   * screen.
   */
  val isPaused: Boolean
    get() = streamState == StreamState.PAUSED

  /** A stream is attached and not in a terminal state. */
  val hasStream: Boolean
    get() = streamState != StreamState.STOPPED && streamState != StreamState.CLOSED

  /** QolAIJol: frames are arriving from the selected camera (glasses stream or USB webcam). */
  val isCameraOn: Boolean
    get() = isStreaming || usbCamera == UsbCameraManager.State.ON

  /** QolAIJol: the preview surface is needed (a glasses stream or a USB webcam is active). */
  val hasPreview: Boolean
    get() = hasStream || usbCamera != UsbCameraManager.State.OFF

  /** QolAIJol: anything is connected or connecting, so Stop is offered. */
  val isActive: Boolean
    get() = hasSession || hasStream || usbCamera != UsbCameraManager.State.OFF

  /**
   * A session or stream start/stop is in flight. The UI shows a spinner and disables controls until
   * the SDK settles on a stable state, so transient states never need their own controls.
   */
  val isBusy: Boolean
    get() =
        isSessionStarting ||
            sessionState == DeviceSessionState.STOPPING ||
            isStartingStream ||
            streamState == StreamState.STARTING ||
            streamState == StreamState.STARTED ||
            streamState == StreamState.STOPPING

  /** Human-readable session/stream state for the status chips. */
  val sessionStateText: String
    get() = sessionState.name.lowercase()

  val streamStateText: String
    get() = streamState.name.lowercase()
}
