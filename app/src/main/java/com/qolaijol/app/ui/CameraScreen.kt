/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraScreen - DAT camera capture screen
//
// A full-bleed camera preview with controls overlaid on a scrim, and the live DeviceSessionState /
// StreamState so the state machine is legible.
//
// QolAIJol: built for a blind user with TalkBack. One Start / Stop button takes the session and
// preview steps together; big buttons do what the glasses gestures do (Speak, What's around,
// Repeat, Describe, Alerts); a status line comes first in TalkBack order, before the details.

package com.qolaijol.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.qolaijol.app.R
import com.qolaijol.app.camera.CameraSource
import com.qolaijol.app.camera.CameraStatus
import com.qolaijol.app.camera.CameraUiState
import com.qolaijol.app.audio.TtsManager
import com.qolaijol.app.camera.CameraViewModel
import com.qolaijol.app.control.ControlAction
import com.qolaijol.app.control.GlassesGestures
import com.qolaijol.app.decision.EnglishPhrases
import com.qolaijol.app.navigation.NavPhrases
import com.qolaijol.app.vision.HorizontalPosition
import com.qolaijol.app.vision.VisionPipeline
import com.qolaijol.app.voice.VoiceAssistant
import com.qolaijol.app.wearables.WearablesViewModel
import kotlin.math.roundToInt

// Scrims behind the top/bottom bars so the white controls stay legible over the live feed. Hoisted
// so they're allocated once instead of on every bar recomposition (the recording timer ticks).
private val TopScrimBrush =
    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))
private val BottomScrimBrush =
    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
private const val PREVIEW_ASPECT_RATIO = 9f / 16f

// The sample's photo / video controls. QolAIJol streams raw frames for YOLO, so the HEVC video
// recording has no input, and a photo preview is of no use to a blind user ("Describe" says what
// the camera sees). Hidden until they are reworked.
private const val SAMPLE_CAPTURE_CONTROLS_ENABLED = false
private const val MAX_LISTED_DETECTIONS = 5

@Composable
fun CameraScreen(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onRequestRecordAudioPermission: suspend () -> Boolean,
    onRequestLocationPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    cameraViewModel: CameraViewModel = viewModel(
        factory =
            CameraViewModel.Factory(
                application = (LocalActivity.current as ComponentActivity).application,
                wearablesViewModel = wearablesViewModel,
            ),
    ),
) {
  val ui by cameraViewModel.uiState.collectAsStateWithLifecycle()
  val wearablesUi by wearablesViewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  var showSettingsMenu by remember { mutableStateOf(false) }

  val isUpdateRequired = wearablesUi.isFirmwareUpdateRequired

  // Back from the voice download screen (or any other app): use a newly installed voice right away.
  LifecycleResumeEffect(Unit) {
    cameraViewModel.recheckLanguageData()
    onPauseOrDispose {}
  }

  // Voice commands can come from the glasses or the notification too, so the view model asks for
  // permissions itself; the activity shows the dialog only while it is in the foreground.
  DisposableEffect(cameraViewModel) {
    cameraViewModel.setPermissionRequests(onRequestRecordAudioPermission, onRequestLocationPermission)
    onDispose { cameraViewModel.setPermissionRequests(null, null) }
  }

  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
    PreviewBackground(
        ui = ui,
        hasActiveDevice = wearablesUi.hasActiveDevice,
        isUpdateRequired = isUpdateRequired,
        onSurfaceChanged = cameraViewModel::setSurface,
    )

    // Tap outside the open settings menu dismisses it.
    if (showSettingsMenu) {
      Box(
          modifier =
              Modifier.fillMaxSize().clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
              ) {
                showSettingsMenu = false
              },
      )
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopBar(
          ui = ui,
          isDisconnectEnabled = wearablesUi.registrationState == RegistrationState.REGISTERED,
          showSettingsMenu = showSettingsMenu,
          onToggleSettings = { showSettingsMenu = !showSettingsMenu },
          onDisconnect = {
            activity?.let { wearablesViewModel.startUnregistration(it) }
            showSettingsMenu = false
          },
          // Above the details below, so they don't take the taps meant for the Disconnect popover.
          modifier = Modifier.zIndex(1f),
      )

      // Takes only the space the controls leave, and scrolls if the details don't fit.
      Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        DetectionPanel(ui = ui)
      }

      ControlPanel(
          ui = ui,
          isUpdateRequired = isUpdateRequired,
          hasActiveDevice = wearablesUi.hasActiveDevice,
          onStart = cameraViewModel::startAssistance,
          onStop = cameraViewModel::stopAssistance,
          onControl = cameraViewModel::onControl,
          onCapturePhoto = cameraViewModel::capturePhoto,
          onToggleRecording = { cameraViewModel.toggleRecording(onRequestRecordAudioPermission) },
          onToggleMic = cameraViewModel::toggleMic,
          onUpdateFirmware = { activity?.let { wearablesViewModel.openFirmwareUpdate(it) } },
          onStopNavigation = cameraViewModel::stopNavigation,
          onToggleCameraSource = cameraViewModel::toggleCameraSource,
      )
    }

    ui.activePreview?.let { preview ->
      CapturePreviewScreen(
          preview = preview,
          onDismiss = { cameraViewModel.dismissCapturePreview() },
      )
    }

    if (ui.showCameraPermissionRedirectConfirm) {
      AlertDialog(
          onDismissRequest = { cameraViewModel.cancelCameraPermissionRedirect() },
          title = { Text(stringResource(R.string.camera_permission_redirect_title)) },
          text = { Text(stringResource(R.string.camera_permission_redirect_message)) },
          confirmButton = {
            TextButton(
                onClick = {
                  cameraViewModel.confirmCameraPermissionRedirect(onRequestWearablesPermission)
                },
            ) {
              Text(stringResource(R.string.camera_permission_continue))
            }
          },
          dismissButton = {
            TextButton(onClick = { cameraViewModel.cancelCameraPermissionRedirect() }) {
              Text(stringResource(R.string.camera_permission_cancel))
            }
          },
      )
    }

    // Shown by itself while the English voice or offline speech model is missing.
    if (ui.showLanguagePrompt && !ui.showCameraPermissionRedirectConfirm) {
      LanguageDownloadDialog(
          ui = ui,
          onDownload = {
            if (ui.needsEnglishVoice) {
              activity?.let { openVoiceInstaller(it, cameraViewModel.englishVoiceInstallIntent()) }
            }
            cameraViewModel.onLanguageDownloadRequested()
          },
          onLater = cameraViewModel::dismissLanguagePrompt,
      )
    }
  }
}

// MARK: - Language download (QolAIJol)

@Composable
private fun LanguageDownloadDialog(ui: CameraUiState, onDownload: () -> Unit, onLater: () -> Unit) {
  AlertDialog(
      onDismissRequest = onLater,
      title = { Text(stringResource(R.string.language_prompt_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.language_prompt_message))
          if (ui.needsEnglishVoice) Text(stringResource(R.string.language_prompt_voice))
          if (ui.needsOfflineSpeech) Text(stringResource(R.string.language_prompt_speech))
          Text(stringResource(R.string.language_prompt_note))
        }
      },
      confirmButton = {
        TextButton(onClick = onDownload, modifier = Modifier.testTag("language_download")) {
          Text(stringResource(R.string.language_prompt_download))
        }
      },
      dismissButton = {
        TextButton(onClick = onLater) { Text(stringResource(R.string.language_prompt_later)) }
      },
      modifier = Modifier.testTag("language_prompt"),
  )
}

/** Opens the engine's voice download screen, or the system text-to-speech settings. */
private fun openVoiceInstaller(activity: Activity, installIntent: Intent) {
  try {
    activity.startActivity(installIntent)
  } catch (e: ActivityNotFoundException) {
    Log.w("QolAIJol/TTS", "No voice installer screen; opening text-to-speech settings")
    try {
      activity.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (e: ActivityNotFoundException) {
      Log.e("QolAIJol/TTS", "No text-to-speech settings screen either")
    }
  }
}

// MARK: - Preview background

@Composable
private fun PreviewBackground(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
    onSurfaceChanged: (android.view.Surface?) -> Unit,
) {
  val liveDescription = stringResource(R.string.live_preview)
  Box(modifier = Modifier.fillMaxSize()) {
    if (ui.hasPreview) {
      AndroidExternalSurface(
          modifier =
              Modifier.align(Alignment.Center)
                  .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true)
                  .fillMaxHeight()
                  .aspectRatio(PREVIEW_ASPECT_RATIO, matchHeightConstraintsFirst = true)
                  .semantics { contentDescription = liveDescription },
      ) {
        onSurface { surface, _, _ ->
          onSurfaceChanged(surface)
          surface.onDestroyed { onSurfaceChanged(null) }
        }
      }
    } else if (!ui.isBusy) {
      StatusPlaceholder(
          ui = ui,
          hasActiveDevice = hasActiveDevice,
          isUpdateRequired = isUpdateRequired,
      )
    }

    // Paused (device-initiated): the surface stays mounted (hasStream is true) so the last frame
    // freezes; dim it and badge it so a held frame reads as intentionally paused, not stalled.
    if (ui.isPaused) {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.35f))
                  .testTag("paused_overlay"),
          contentAlignment = Alignment.Center,
      ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
              imageVector = Icons.Filled.Pause,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(36.dp),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.paused_title),
              color = Color.White,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = stringResource(R.string.paused_subtitle),
              color = Color.White.copy(alpha = 0.7f),
              fontSize = 15.sp,
              textAlign = TextAlign.Center,
          )
        }
      }
    }

    if ((ui.isBusy || (ui.hasPreview && !ui.hasReceivedFirstFrame)) && !ui.isPaused) {
      Box(
          modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
          contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(color = Color.White)
      }
    }
  }
}

@Composable
private fun StatusPlaceholder(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
) {
  val title: String
  val subtitle: String?
  val showWaitingRow: Boolean
  when {
    ui.cameraSource == CameraSource.USB -> {
      title = stringResource(R.string.placeholder_usb_title)
      subtitle =
          stringResource(
              if (ui.usbCameraAttached) R.string.status_usb_off else R.string.status_usb_not_found,
          )
      showWaitingRow = false
    }
    !hasActiveDevice -> {
      title = stringResource(R.string.placeholder_put_on_glasses)
      subtitle = null
      showWaitingRow = true
    }
    isUpdateRequired -> {
      title = stringResource(R.string.update_required_title)
      subtitle = stringResource(R.string.update_required_subtitle)
      showWaitingRow = false
    }
    !ui.hasSession -> {
      title = stringResource(R.string.placeholder_ready_title)
      subtitle = stringResource(R.string.placeholder_ready_subtitle)
      showWaitingRow = false
    }
    else -> {
      title = stringResource(R.string.placeholder_session_started_title)
      subtitle = stringResource(R.string.placeholder_session_started_subtitle)
      showWaitingRow = false
    }
  }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        painter = painterResource(id = R.drawable.camera_access_icon),
        contentDescription = null, // decorative; TalkBack reads the status text instead
        tint = Color.White,
        modifier = Modifier.size(88.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
    if (showWaitingRow) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
          text = stringResource(R.string.waiting_for_active_device),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 14.sp,
      )
    }
  }
}

// MARK: - Top bar

@Composable
private fun TopBar(
    ui: CameraUiState,
    isDisconnectEnabled: Boolean,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(TopScrimBrush)
              .statusBarsPadding()
              .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StatusChip(
          label = stringResource(R.string.status_session),
          value = ui.sessionStateText,
          active = ui.isSessionActive,
          present = ui.hasSession,
      )
      StatusChip(
          label = stringResource(R.string.status_stream),
          value = ui.streamStateText,
          active = ui.isStreaming,
          present = ui.hasStream,
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    Box {
      // Pinned to TopEnd so it stays put when the Disconnect popover below widens this Box. 48 dp:
      // the minimum touch target, also for TalkBack.
      Box(
          modifier =
              Modifier.align(Alignment.TopEnd).size(48.dp).clickable(onClick = onToggleSettings),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = Icons.Filled.LinkOff,
            contentDescription = stringResource(R.string.unregister_button_title),
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
      }
      if (showSettingsMenu) {
        SwitchButton(
            label = stringResource(R.string.unregister_button_title),
            onClick = onDisconnect,
            modifier = Modifier.align(Alignment.TopEnd).offset(y = 52.dp).width(150.dp),
            isDestructive = true,
            enabled = isDisconnectEnabled,
        )
      }
    }
  }
}

@Composable
private fun StatusChip(label: String, value: String, active: Boolean, present: Boolean) {
  val dotColor = if (active) AppColor.Green else if (present) AppColor.Yellow else Color.Gray
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
    Text(
        text = "$label: $value",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    )
  }
}

// MARK: - Control panel (QolAIJol)

@Composable
private fun ControlPanel(
    ui: CameraUiState,
    isUpdateRequired: Boolean,
    hasActiveDevice: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onControl: (ControlAction) -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleMic: () -> Unit,
    onUpdateFirmware: () -> Unit,
    onStopNavigation: () -> Unit,
    onToggleCameraSource: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .background(BottomScrimBrush)
              .navigationBarsPadding()
              .padding(horizontal = 20.dp, vertical = 16.dp)
              // TalkBack starts here: status and controls before the details at the top.
              .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
              },
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    StatusLine(ui = ui, hasActiveDevice = hasActiveDevice)
    // A glasses firmware update doesn't stop the USB webcam from being used.
    if (isUpdateRequired && ui.cameraSource == CameraSource.GLASSES) {
      UpdateRequiredMessage()
      SwitchButton(
          label = stringResource(R.string.update_firmware_button_title),
          onClick = onUpdateFirmware,
      )
      CameraSourceButton(ui = ui, onClick = onToggleCameraSource)
    } else {
      if (ui.navigation.active) {
        SwitchButton(
            label = stringResource(R.string.nav_stop_button),
            onClick = onStopNavigation,
            modifier = Modifier.testTag("stop_navigation_button"),
            isDestructive = true,
        )
      }
      VoiceButton(phase = ui.voice.phase, onClick = { onControl(ControlAction.SPEAK) })
      // Ignored while a command is in progress, so they are disabled meanwhile.
      val idle = ui.voice.phase == VoiceAssistant.Phase.IDLE
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ControlButton(
            label = stringResource(R.string.control_whats_around),
            enabled = idle,
            onClick = { onControl(ControlAction.WHATS_AROUND) },
            modifier = Modifier.weight(1f).testTag("whats_around_button"),
        )
        ControlButton(
            label = stringResource(R.string.control_repeat),
            enabled = idle,
            onClick = { onControl(ControlAction.REPEAT) },
            modifier = Modifier.weight(1f).testTag("repeat_button"),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ControlButton(
            label = stringResource(R.string.control_describe),
            enabled = idle,
            onClick = { onControl(ControlAction.DESCRIBE) },
            modifier = Modifier.weight(1f).testTag("describe_button"),
        )
        ControlButton(
            label =
                stringResource(
                    if (ui.assistanceEnabled) R.string.control_alerts_on
                    else R.string.control_alerts_off,
                ),
            enabled = idle,
            onClick = { onControl(ControlAction.TOGGLE_ALERTS) },
            modifier = Modifier.weight(1f).testTag("alerts_button"),
        )
      }
      if (SAMPLE_CAPTURE_CONTROLS_ENABLED) {
        CaptureRow(
            ui = ui,
            onCapturePhoto = onCapturePhoto,
            onToggleRecording = onToggleRecording,
            onToggleMic = onToggleMic,
        )
      }
      CameraSourceButton(ui = ui, onClick = onToggleCameraSource)
      StartStopButton(ui = ui, onStart = onStart, onStop = onStop)
    }
  }
}

/** Glasses or USB webcam; can be changed only while the camera is off. */
@Composable
private fun CameraSourceButton(ui: CameraUiState, onClick: () -> Unit) {
  ControlButton(
      label =
          stringResource(
              when (ui.cameraSource) {
                CameraSource.GLASSES -> R.string.control_source_glasses
                CameraSource.USB -> R.string.control_source_usb
              },
          ),
      enabled = !ui.isActive,
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().testTag("camera_source_button"),
  )
}

/** Camera and gesture state in words; the first thing TalkBack reads on this screen. */
@Composable
private fun StatusLine(ui: CameraUiState, hasActiveDevice: Boolean) {
  val camera =
      when (ui.cameraStatus) {
        CameraStatus.OFF ->
            when {
              ui.cameraSource == CameraSource.USB ->
                  if (ui.usbCameraAttached) R.string.status_usb_off
                  else R.string.status_usb_not_found
              hasActiveDevice -> R.string.status_off
              else -> R.string.status_no_glasses
            }
        CameraStatus.STARTING -> R.string.status_starting
        CameraStatus.ON ->
            if (ui.assistanceEnabled) R.string.status_on else R.string.status_on_alerts_off
        CameraStatus.PAUSED -> R.string.status_paused
      }
  val gestures =
      when (ui.gestures) {
        GlassesGestures.Status.OFF -> null
        GlassesGestures.Status.CONNECTING -> R.string.gestures_connecting
        GlassesGestures.Status.ON -> R.string.gestures_on
        GlassesGestures.Status.UNAVAILABLE -> R.string.gestures_unavailable
      }
  Column(
      modifier =
          Modifier.fillMaxWidth().testTag("status_line").semantics(mergeDescendants = true) {
            heading()
          },
      verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
        text = stringResource(camera),
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    gestures?.let {
      Text(text = stringResource(it), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
    }
  }
}

/** One switch for everything: Start connects and turns the camera on, Stop turns it all off. */
@Composable
private fun StartStopButton(ui: CameraUiState, onStart: () -> Unit, onStop: () -> Unit) {
  // Stop whenever anything is connected or connecting, so it can always be turned off.
  val on = ui.isActive
  val stopping =
      ui.sessionState == DeviceSessionState.STOPPING || ui.streamState == StreamState.STOPPING
  Button(
      onClick = if (on) onStop else onStart,
      enabled = !stopping,
      modifier =
          Modifier.fillMaxWidth().height(64.dp).testTag(if (on) "stop_button" else "start_button"),
      shape = RoundedCornerShape(20.dp),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = if (on) AppColor.DestructiveBackground else AppColor.Green,
              contentColor = if (on) AppColor.DestructiveForeground else Color.Black,
              disabledContainerColor = Color.Gray,
              disabledContentColor = Color.DarkGray,
          ),
  ) {
    Text(
        text = stringResource(if (on) R.string.control_stop else R.string.control_start),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
  }
}

/** A large, high-contrast button with a short label, easy to find with TalkBack. */
@Composable
private fun ControlButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.height(64.dp),
      shape = RoundedCornerShape(20.dp),
      contentPadding = PaddingValues(horizontal = 8.dp),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = AppColor.ControlBackground,
              contentColor = Color.White,
              disabledContainerColor = AppColor.ControlBackground.copy(alpha = 0.5f),
              disabledContentColor = Color.White.copy(alpha = 0.5f),
          ),
  ) {
    Text(
        text = label,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
  }
}

@Composable
private fun CaptureRow(
    ui: CameraUiState,
    onCapturePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleMic: () -> Unit,
) {
  val captureEnabled = ui.isStreaming
  val micEnabled = ui.isStreaming && !ui.isRecording
  val recordEnabled = ui.isStreaming || ui.isRecording

  Row(
      modifier = Modifier.fillMaxWidth().alpha(if (ui.hasSession) 1f else 0f),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Photo capture.
    CircleIconButton(
        modifier = Modifier.testTag("capture_button"),
        icon = Icons.Filled.PhotoCamera,
        contentDescription = stringResource(R.string.capture_photo),
        enabled = captureEnabled,
        onClick = onCapturePhoto,
    )

    // Record / stop, morphing into a live timer.
    RecordPill(
        modifier = Modifier.weight(1f).testTag("record_button"),
        isRecording = ui.isRecording,
        elapsedSeconds = ui.recordingElapsedSeconds,
        enabled = recordEnabled,
        onClick = onToggleRecording,
    )

    // Sound-in-video toggle.
    CircleIconButton(
        modifier = Modifier.testTag("mic_toggle"),
        icon = if (ui.includeAudioInStream) Icons.Filled.Mic else Icons.Filled.MicOff,
        contentDescription =
            if (ui.includeAudioInStream) stringResource(R.string.mic_on)
            else stringResource(R.string.mic_off),
        enabled = micEnabled,
        tint = if (ui.includeAudioInStream) Color.White else Color.White.copy(alpha = 0.45f),
        onClick = onToggleMic,
    )
  }
}

// MARK: - Voice button (QolAIJol)

/** The big push-to-talk button: easy to find and press without looking. */
@Composable
private fun VoiceButton(phase: VoiceAssistant.Phase, onClick: () -> Unit) {
  val label =
      when (phase) {
        VoiceAssistant.Phase.IDLE -> stringResource(R.string.voice_button_idle)
        VoiceAssistant.Phase.LISTENING -> stringResource(R.string.voice_button_listening)
        VoiceAssistant.Phase.RECOGNIZING -> stringResource(R.string.voice_button_recognizing)
        VoiceAssistant.Phase.UNDERSTANDING -> stringResource(R.string.voice_button_understanding)
        VoiceAssistant.Phase.DESCRIBING -> stringResource(R.string.voice_button_describing)
        VoiceAssistant.Phase.SEARCHING -> stringResource(R.string.voice_button_searching)
      }
  val listening = phase == VoiceAssistant.Phase.LISTENING
  Button(
      onClick = onClick,
      enabled = phase == VoiceAssistant.Phase.IDLE || listening,
      modifier = Modifier.fillMaxWidth().height(104.dp).testTag("voice_button"),
      shape = RoundedCornerShape(28.dp),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = if (listening) AppColor.Red else AppColor.DeepBlue,
              contentColor = Color.White,
              disabledContainerColor = AppColor.DeepBlue.copy(alpha = 0.6f),
              disabledContentColor = Color.White,
          ),
  ) {
    Icon(
        imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(text = label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
  }
}

// MARK: - Detections (QolAIJol)

@Composable
private fun DetectionPanel(ui: CameraUiState) {
  val vision = ui.vision
  val result = vision.latestResult
  val statusText =
      when (vision.status) {
        VisionPipeline.Status.IDLE,
        VisionPipeline.Status.LOADING -> stringResource(R.string.ai_status_loading)
        VisionPipeline.Status.READY -> stringResource(R.string.ai_status_ready)
        VisionPipeline.Status.ERROR -> stringResource(R.string.ai_status_error)
      }
  Column(
      modifier =
          Modifier.padding(horizontal = 20.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(Color.Black.copy(alpha = 0.55f))
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("detection_panel")
              // One TalkBack stop for all the details, instead of a dozen small lines.
              .semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
        text =
            if (result != null && vision.status == VisionPipeline.Status.READY) {
              stringResource(R.string.ai_status_with_timing, statusText, result.inferenceMs)
            } else {
              stringResource(R.string.ai_status_label, statusText)
            },
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text =
            stringResource(
                R.string.voice_status_label,
                when (ui.voiceStatus) {
                  TtsManager.Status.INITIALIZING -> stringResource(R.string.voice_status_starting)
                  TtsManager.Status.READY -> stringResource(R.string.voice_status_ready)
                  TtsManager.Status.NO_OFFLINE_VOICE ->
                      stringResource(R.string.voice_status_no_offline_voice)
                  TtsManager.Status.UNAVAILABLE -> stringResource(R.string.voice_status_unavailable)
                },
            ),
        color = Color.White,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
    )
    vision.error?.let { error ->
      Text(text = error, color = AppColor.Yellow, fontSize = 12.sp)
    }
    if (!ui.assistanceEnabled) {
      Text(
          text = stringResource(R.string.assistance_off),
          color = AppColor.Yellow,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
    ui.voice.heard?.let { heard ->
      Text(
          text =
              stringResource(
                  if (ui.voice.heardOffline) R.string.voice_heard_offline else R.string.voice_heard,
                  heard,
              ),
          color = Color.White,
          fontSize = 13.sp,
      )
    }
    ui.voice.answer?.let { answer ->
      Text(
          text = stringResource(R.string.voice_answer, answer),
          color = Color.White,
          fontSize = 13.sp,
          modifier = Modifier.testTag("voice_answer"),
      )
    }
    if (ui.navigation.active) {
      Text(
          text =
              stringResource(
                  R.string.nav_status,
                  ui.navigation.destination.orEmpty(),
                  NavPhrases.distance((ui.navigation.remainingM ?: 0).toDouble()),
              ),
          color = AppColor.Green,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.testTag("navigation_status"),
      )
      ui.navigation.lastInstruction?.let { instruction ->
        Text(text = stringResource(R.string.nav_instruction, instruction), color = Color.White, fontSize = 13.sp)
      }
    }
    ui.latestAlert?.let { alert ->
      Text(
          text = stringResource(R.string.latest_alert, alert),
          color = AppColor.Yellow,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.testTag("latest_alert"),
      )
    }
    if (ui.hasPreview && result != null) {
      if (result.detections.isEmpty()) {
        Text(
            text = stringResource(R.string.no_objects_detected),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
      }
      result.detections.take(MAX_LISTED_DETECTIONS).forEach { detection ->
        Text(
            text =
                stringResource(
                    R.string.detection_line,
                    EnglishPhrases.objectName(detection.className),
                    (detection.confidence * 100).roundToInt(),
                    stringResource(
                        when (detection.horizontalPosition) {
                          HorizontalPosition.LEFT -> R.string.position_left
                          HorizontalPosition.CENTER -> R.string.position_center
                          HorizontalPosition.RIGHT -> R.string.position_right
                        },
                    ),
                ),
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun RecordPill(
    isRecording: Boolean,
    elapsedSeconds: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val background =
      if (isRecording) AppColor.RecordAccent.copy(alpha = 0.5f)
      else Color.White.copy(alpha = if (enabled) 0.18f else 0.08f)
  Row(
      modifier =
          modifier
              .height(50.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(background)
              .clickable(enabled = enabled, onClick = onClick),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isRecording) {
      Icon(
          imageVector = Icons.Filled.Stop,
          contentDescription = stringResource(R.string.stop_recording),
          tint = Color.White,
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      val minutes = elapsedSeconds / 60
      val seconds = elapsedSeconds % 60
      Text(
          text = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds),
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.testTag("recording_indicator"),
      )
    } else {
      Icon(
          imageVector = Icons.Filled.Videocam,
          contentDescription = stringResource(R.string.record_video),
          tint = if (enabled) AppColor.RecordAccent else Color.White.copy(alpha = 0.45f),
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
          text = stringResource(R.string.record_label),
          color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
  Box(
      modifier =
          modifier
              .size(50.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
              .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) tint else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(22.dp),
    )
  }
}

@Composable
private fun UpdateRequiredMessage(modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(AppColor.UpdateRequiredBackground)
              .padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = null,
        tint = AppColor.UpdateRequiredForeground,
        modifier = Modifier.size(24.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          text = stringResource(R.string.update_required_title),
          color = AppColor.UpdateRequiredForeground,
          fontWeight = FontWeight.SemiBold,
          fontSize = 16.sp,
      )
      Text(
          text = stringResource(R.string.update_required_firmware_message),
          color = AppColor.UpdateRequiredForeground,
          fontSize = 15.sp,
      )
    }
  }
}
