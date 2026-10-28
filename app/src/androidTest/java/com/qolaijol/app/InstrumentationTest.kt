/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// InstrumentationTest - DAT Integration Testing Suite
//
// End-to-end tests for the QolAIJol camera flow, driven by MockDeviceKit (which simulates Ray-Ban
// Meta glasses and auto-grants the wearable camera permission) and Compose UI automation: the
// one-step Start / Stop, pausing from the glasses, the gestures the app receives through the Inputs
// capability, the phone buttons that do the same, and choosing a USB webcam instead.

package com.qolaijol.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.qolaijol.app.camera.CameraViewModel
import com.qolaijol.app.control.ControlPhrases
import com.qolaijol.app.stream.VideoCaptureHandler
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class InstrumentationTest {

  companion object {
    private const val TAG = "InstrumentationTest"
    private const val DEFAULT_TIMEOUT = 5000L
    private const val STREAM_TIMEOUT = 20000L
  }

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()
  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

  @Before
  fun setup() {
    grantPermissions()
  }

  @After
  fun tearDown() {
    MockDeviceKit.getInstance(targetContext).disable()
    // The camera choice is remembered; the next test starts with the glasses again.
    targetContext.getSharedPreferences(CameraViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
  }

  // MARK: - Device pairing & navigation

  @Test
  fun showsHomeScreenOnLaunch() {
    composeTestRule.waitUntilExactlyOneExists(
        hasText(targetContext.getString(R.string.home_tip_session)),
        timeoutMillis = DEFAULT_TIMEOUT,
    )
  }

  @Test
  fun registerAndPairShowsCameraScreen() {
    pairActiveDevice()
    waitForActiveDevice()
  }

  // MARK: - Device activity

  @Test
  fun powerCycleAffectsDeviceActivity() {
    val device = pairActiveDevice()
    waitForActiveDevice()

    device.powerOff()
    waitForInactiveDevice()

    device.powerOn()
    device.don()
    waitForActiveDevice()
  }

  // MARK: - Start / Stop

  @Test
  fun startTurnsCameraOn() {
    pairActiveDevice()
    startCamera()
    composeTestRule.onNodeWithTag("stop_button").assertExists()
  }

  @Test
  fun stopTurnsCameraOff() {
    pairActiveDevice()
    startCamera()

    composeTestRule.onNodeWithTag("stop_button").performClick()

    // Stop ends the session too, so the next Start begins from scratch.
    waitForTag("start_button")
    waitForText(R.string.status_off)
  }

  @Test
  fun disconnectWhileStreamingThenReconnectShowsStart() {
    pairActiveDevice()
    startCamera()

    disconnect()

    val reconnectLabel = targetContext.getString(R.string.register_button_title)
    composeTestRule.waitUntilExactlyOneExists(
        hasText(reconnectLabel),
        timeoutMillis = STREAM_TIMEOUT,
    )
    composeTestRule.onNodeWithText(reconnectLabel).performClick()

    // Unregistering does not stop the SDK session, and the camera view model is scoped to the
    // activity rather than the screen, so re-registering must land on the "off" screen off the
    // app's own teardown — not on a camera screen still reporting the old stream.
    waitForTag("start_button")
    composeTestRule.onNodeWithTag("stop_button").assertDoesNotExist()
  }

  @Test
  fun cameraPermissionDeniedTurnsBackOff() {
    pairActiveDevice()
    // Deny the permission REQUEST too (not just the initial check), so confirming the redirect
    // resolves to Denied instead of the mock's default grant.
    MockDeviceKit.getInstance(targetContext)
        .permissions
        .setRequestResult(Permission.CAMERA, PermissionStatus.Denied)

    waitForActiveDevice()
    composeTestRule.onNodeWithTag("start_button").performClick()

    // Camera is denied, so starting surfaces the redirect confirm; tap Continue.
    val continueLabel = targetContext.getString(R.string.camera_permission_continue)
    composeTestRule.waitUntilExactlyOneExists(
        hasText(continueLabel),
        timeoutMillis = STREAM_TIMEOUT,
    )
    composeTestRule.onNodeWithText(continueLabel).performClick()

    // The request resolves Denied → the error shows, and Start ended the session it opened, so
    // the screen is back to plain "off".
    waitForText(R.string.error_camera_permission_denied)
    waitForTag("start_button")
  }

  // MARK: - Glasses

  @Test
  fun foldingGlassesTurnsCameraOff() {
    val device = pairActiveDevice()
    startCamera()

    device.fold()

    // Folding takes the glasses off the face and closes the hinge, which ends the session. The
    // device stays powered and connected, so Start is offered again.
    waitForTag("start_button")
  }

  @Test
  fun singleTapPausesThenResumes() {
    val device = pairActiveDevice()
    startCamera()

    // A single cap-touch tap pauses the stream. The preview stays mounted on its last frame with a
    // "Paused" badge, and the status line says how to resume.
    device.services.captouch.tap()
    waitForTag("paused_overlay")
    waitForText(R.string.status_paused)

    // Tapping again resumes streaming.
    device.services.captouch.tap()
    composeTestRule.waitUntil(timeoutMillis = STREAM_TIMEOUT) {
      composeTestRule.onAllNodesWithTag("paused_overlay").fetchSemanticsNodes().isEmpty()
    }
    waitForText(R.string.status_on)
  }

  @Test
  fun glassesSwipeSaysWhatIsAround() {
    val device = pairActiveDevice()
    startCamera()
    waitForText(R.string.gestures_on)

    device.services.input.navRight()

    waitForVoiceAnswer()
  }

  @Test
  fun glassesCameraButtonHoldTurnsAlertsOffAndOn() {
    val device = pairActiveDevice()
    startCamera()
    waitForText(R.string.gestures_on)

    device.services.input.capture(CapturePressType.HOLD)
    waitForText(R.string.status_on_alerts_off)
    composeTestRule.onNodeWithText(targetContext.getString(R.string.control_alerts_off)).assertExists()

    device.services.input.capture(CapturePressType.HOLD)
    waitForText(R.string.status_on)
  }

  @Test
  fun glassesDoubleTapStartsAndEndsListening() {
    val device = pairActiveDevice()
    startCamera()
    waitForText(R.string.gestures_on)

    device.services.input.select()
    waitForText(R.string.voice_button_listening)

    // A second double tap ends the recording; the command is handled and the button is free again.
    device.services.input.select()
    waitForText(R.string.voice_button_idle)
  }

  // MARK: - Phone buttons

  @Test
  fun phoneButtonsDoWhatTheGesturesDo() {
    pairActiveDevice()
    startCamera()

    composeTestRule.onNodeWithTag("whats_around_button").performClick()
    waitForVoiceAnswer()

    composeTestRule.onNodeWithTag("alerts_button").performClick()
    waitForText(R.string.status_on_alerts_off)
  }

  // MARK: - USB webcam (the emulator has none: checks the choice and the "not found" path)

  @Test
  fun cameraChoiceSwitchesToUsbWebcamAndBack() {
    pairActiveDevice()
    waitForActiveDevice()

    composeTestRule.onNodeWithTag("camera_source_button").performClick()
    waitForText(R.string.control_source_usb)
    // Shown in the status line and on the placeholder.
    composeTestRule.waitUntilAtLeastOneExists(
        hasText(targetContext.getString(R.string.status_usb_not_found)),
        timeoutMillis = DEFAULT_TIMEOUT,
    )

    // No webcam plugged in: Start says so and the camera stays off.
    composeTestRule.onNodeWithTag("start_button").performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasText(ControlPhrases.USB_NOT_CONNECTED),
        timeoutMillis = DEFAULT_TIMEOUT,
    )
    composeTestRule.onNodeWithTag("start_button").assertExists()

    composeTestRule.onNodeWithTag("camera_source_button").performClick()
    waitForText(R.string.control_source_glasses)
    waitForText(R.string.status_off)
  }

  // MARK: - Recording (driven directly: the recording controls are hidden in QolAIJol)

  // Drives VideoCaptureHandler directly with a valid HEVC CSD but only non-keyframe NALs, so no
  // keyframe is ever detected — the device-camera path that previously stuck at 00:00 (timer never
  // ran) and crashed on stop with "Failed to add the track to the muxer" (0x0 dimensions). The
  // grace fallback must open the track on dimensions alone and finalize a playable file. Driven
  // directly because remote test emulators have no usable camera to reproduce it end-to-end.
  @Test
  fun recordingFinalizesWithoutDetectableKeyframe() {
    val (csd, width, height) = hevcCsdAndSizeFromAsset("plant.mp4")
    val handler = VideoCaptureHandler()
    val dir = File(targetContext.cacheDir, "recordings").apply { mkdirs() }
    val out = File(dir, "synthetic_${System.currentTimeMillis()}.mp4")

    handler.prepare(out.canonicalPath, includeAudio = false)
    handler.setInitialCodecConfig(csd)

    // Minimal HEVC TRAIL_R (non-keyframe) NAL: start code + header (type 1) + dummy payload.
    val nonKeyframe = byteArrayOf(0, 0, 0, 1, 0x02, 0x01) + ByteArray(64) { 0x42 }
    var started = false
    for (i in 0 until 70) {
      if (handler.writeVideoFrame(nonKeyframe, i * 33_333L, width, height)) started = true
    }
    assertTrue("Recording should start via the grace fallback even without a keyframe", started)
    assertTrue("stopRecording should report a video track", handler.stopRecording())
    assertFileHasVideoTrackAndSamples(out)
  }

  // Audio enabled (sound-in-video on, the default) but the mic never delivers audio (no
  // RECORD_AUDIO permission, or a silent emulator mic). The keyframe opens the video track while
  // the audio track is still pending, then markAudioUnavailable() switches to video-only. This must
  // not re-add the video track — the duplicate, sampleless track previously left a 0-byte file
  // ("No video data was recorded"). Recording must finalize a playable, video-only file.
  @Test
  fun recordingFinalizesVideoOnlyWhenMicUnavailable() {
    val (csd, width, height) = hevcCsdAndSizeFromAsset("plant.mp4")
    val handler = VideoCaptureHandler()
    val dir = File(targetContext.cacheDir, "recordings").apply { mkdirs() }
    val out = File(dir, "synthetic_${System.currentTimeMillis()}.mp4")

    handler.prepare(out.canonicalPath, includeAudio = true)
    handler.setInitialCodecConfig(csd)

    val keyframe = byteArrayOf(0, 0, 0, 1, 0x2A, 0x01) + ByteArray(64) { 0x37 } // CRA (type 21)
    val nonKeyframe = byteArrayOf(0, 0, 0, 1, 0x02, 0x01) + ByteArray(64) { 0x42 } // TRAIL_R

    // First frame opens the video track while the audio track is still pending.
    assertTrue(handler.writeVideoFrame(keyframe, 0L, width, height))
    // Mic turns out unavailable → switch to video-only (previously double-added the video track).
    handler.markAudioUnavailable()
    for (i in 1..5) handler.writeVideoFrame(nonKeyframe, i * 33_333L, width, height)

    assertTrue("stopRecording should report a video track", handler.stopRecording())
    assertFileHasVideoTrackAndSamples(out)
  }

  // MARK: - Helpers

  // Pairs a powered, donned, unfolded mock device and returns it. Camera permission is explicitly
  // denied so starting exercises the real redirect-confirm (the mock grants it on request). Set
  // explicitly — MockDeviceKit is a process singleton and a prior test's grant can leak in.
  private fun pairActiveDevice(withCameraFeed: Boolean = true): MockGlasses {
    val mockDeviceKit = MockDeviceKit.getInstance(targetContext)
    mockDeviceKit.enable(MockDeviceKitConfig(initialPermissionsGranted = false))
    mockDeviceKit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
    val device = mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
    device.powerOn()
    device.don()
    device.unfold()
    if (withCameraFeed) device.services.camera.setCameraFeed(getFileUri("plant.mp4"))
    return device
  }

  // Opens the top-bar settings popover and taps Disconnect. The icon and the popover button share
  // the Disconnect string — the icon carries it as a content description, the button as text — so
  // the two matchers address them separately.
  private fun disconnect() {
    val disconnectLabel = targetContext.getString(R.string.unregister_button_title)
    composeTestRule.onNodeWithContentDescription(disconnectLabel).performClick()
    composeTestRule.waitUntilExactlyOneExists(
        hasText(disconnectLabel),
        timeoutMillis = DEFAULT_TIMEOUT,
    )
    composeTestRule.onNodeWithText(disconnectLabel).performClick()
  }

  // Active device → the status line says the camera is off and Start is offered.
  private fun waitForActiveDevice() {
    waitForText(R.string.status_off)
    waitForTag("start_button")
  }

  // Inactive device → the status line says the glasses aren't found.
  private fun waitForInactiveDevice() {
    waitForText(R.string.status_no_glasses)
  }

  // Presses Start, confirms the camera-permission redirect, and waits until the camera is on.
  private fun startCamera() {
    waitForActiveDevice()
    composeTestRule.onNodeWithTag("start_button").performClick()

    // Camera is denied by default, so the first start surfaces the permission-redirect confirm.
    // Tap Continue → the mock grants the request and persists it.
    val continueLabel = targetContext.getString(R.string.camera_permission_continue)
    composeTestRule.waitUntilExactlyOneExists(
        hasText(continueLabel),
        timeoutMillis = STREAM_TIMEOUT,
    )
    composeTestRule.onNodeWithText(continueLabel).performClick()

    waitForText(R.string.status_on)
  }

  private fun waitForTag(tag: String) {
    composeTestRule.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = STREAM_TIMEOUT)
  }

  // Texts in the status line are merged into one node; hasText matches any of them.
  private fun waitForText(resId: Int) {
    composeTestRule.waitUntilExactlyOneExists(
        hasText(targetContext.getString(resId)),
        timeoutMillis = STREAM_TIMEOUT,
    )
  }

  // The answer line sits in the merged details panel, so its tag is only in the unmerged tree.
  private fun waitForVoiceAnswer() {
    composeTestRule.waitUntil(timeoutMillis = STREAM_TIMEOUT) {
      composeTestRule
          .onAllNodesWithTag("voice_answer", useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
  }

  private fun grantPermissions() {
    listOf(
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
    )
        .forEach { grantPermission(it) }
  }

  private fun grantPermission(permission: String) {
    try {
      InstrumentationRegistry.getInstrumentation()
          .uiAutomation
          .executeShellCommand("pm grant ${targetContext.packageName} $permission")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to grant permission $permission", e)
    }
  }

  // Asserts the file is a finalized MP4 with a video track and at least one sample, then deletes
  // it.
  private fun assertFileHasVideoTrackAndSamples(file: File) {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.canonicalPath)
      var videoTrack = -1
      for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("video/")) videoTrack = i
      }
      assertTrue("Recording has no video track", videoTrack >= 0)
      extractor.selectTrack(videoTrack)
      assertTrue(
          "Recording has no video samples",
          extractor.readSampleData(ByteBuffer.allocate(1 shl 20), 0) > 0,
      )
    } finally {
      extractor.release()
      file.delete()
    }
  }

  // Returns the HEVC codec-specific data (Annex-B VPS+SPS+PPS) and dimensions of a bundled video
  // asset, for priming VideoCaptureHandler in the keyframe-less recording test.
  private fun hevcCsdAndSizeFromAsset(assetName: String): Triple<ByteArray, Int, Int> {
    val file = File(targetContext.cacheDir, assetName)
    InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
      FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.canonicalPath)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
          val buffer =
              checkNotNull(format.getByteBuffer("csd-0")) { "Asset $assetName has no csd-0" }
          val csd = ByteArray(buffer.remaining())
          buffer.get(csd)
          return Triple(
              csd,
              format.getInteger(MediaFormat.KEY_WIDTH),
              format.getInteger(MediaFormat.KEY_HEIGHT),
          )
        }
      }
    } finally {
      extractor.release()
    }
    throw IllegalStateException("No video track in $assetName")
  }

  private fun getFileUri(assetName: String): Uri {
    val outFile = File(targetContext.cacheDir, assetName)
    InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
      FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return Uri.fromFile(outFile)
  }
}
