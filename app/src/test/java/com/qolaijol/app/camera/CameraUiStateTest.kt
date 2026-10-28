package com.qolaijol.app.camera

import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.qolaijol.app.usb.UsbCameraManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateTest {

  private fun status(session: DeviceSessionState, stream: StreamState = StreamState.STOPPED) =
      CameraUiState(sessionState = session, streamState = stream).cameraStatus

  @Test
  fun cameraStatus() {
    assertEquals(CameraStatus.OFF, CameraUiState().cameraStatus)
    assertEquals(CameraStatus.STARTING, status(DeviceSessionState.STARTING))
    assertEquals(
        CameraStatus.STARTING,
        status(DeviceSessionState.STARTED, StreamState.STARTING),
    )
    assertEquals(
        CameraStatus.STARTING,
        CameraUiState(sessionState = DeviceSessionState.STARTED, isStartingStream = true)
            .cameraStatus,
    )
    assertEquals(CameraStatus.ON, status(DeviceSessionState.STARTED, StreamState.STREAMING))
  }

  @Test
  fun pausedByStreamOrSession() {
    assertEquals(CameraStatus.PAUSED, status(DeviceSessionState.STARTED, StreamState.PAUSED))
    assertEquals(CameraStatus.PAUSED, status(DeviceSessionState.PAUSED, StreamState.STREAMING))
    // A paused session without a stream has nothing to resume.
    assertEquals(CameraStatus.OFF, status(DeviceSessionState.PAUSED))
  }

  @Test
  fun usbWebcam() {
    fun usb(state: UsbCameraManager.State) =
        CameraUiState(cameraSource = CameraSource.USB, usbCamera = state)
    assertEquals(CameraStatus.OFF, usb(UsbCameraManager.State.OFF).cameraStatus)
    assertEquals(CameraStatus.STARTING, usb(UsbCameraManager.State.CONNECTING).cameraStatus)
    assertEquals(CameraStatus.ON, usb(UsbCameraManager.State.ON).cameraStatus)

    // Alerts, answers and the preview work the same as with the glasses stream.
    assertTrue(usb(UsbCameraManager.State.ON).isCameraOn)
    assertFalse(usb(UsbCameraManager.State.CONNECTING).isCameraOn)
    assertTrue(usb(UsbCameraManager.State.CONNECTING).hasPreview)
    // Stop is offered while connecting too (e.g. while Android asks for USB access).
    assertTrue(usb(UsbCameraManager.State.CONNECTING).isActive)
    assertFalse(usb(UsbCameraManager.State.OFF).isActive)
  }

  @Test
  fun stoppingAlreadyCountsAsOff() {
    assertEquals(CameraStatus.OFF, status(DeviceSessionState.STARTED, StreamState.STOPPING))
    assertEquals(CameraStatus.OFF, status(DeviceSessionState.STOPPING, StreamState.STOPPING))
  }
}
