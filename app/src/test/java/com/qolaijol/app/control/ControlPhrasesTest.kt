package com.qolaijol.app.control

import com.qolaijol.app.camera.CameraStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlPhrasesTest {

  private fun say(from: CameraStatus, to: CameraStatus, alertsOn: Boolean = true) =
      ControlPhrases.cameraStatusChange(from, to, alertsOn)

  @Test
  fun cameraOn() {
    assertEquals("Camera on. Alerts are on.", say(CameraStatus.STARTING, CameraStatus.ON))
    assertEquals(
        "Camera on. Alerts are off.",
        say(CameraStatus.STARTING, CameraStatus.ON, alertsOn = false),
    )
  }

  @Test
  fun pauseAndResume() {
    assertEquals(
        "Paused. Alerts are off. Tap the glasses once to resume.",
        say(CameraStatus.ON, CameraStatus.PAUSED),
    )
    assertEquals("Resumed.", say(CameraStatus.PAUSED, CameraStatus.ON))
  }

  @Test
  fun cameraOff() {
    assertEquals("Camera off.", say(CameraStatus.ON, CameraStatus.OFF))
    assertEquals("Camera off.", say(CameraStatus.PAUSED, CameraStatus.OFF))
    // A failed start is explained by its own error message.
    assertNull(say(CameraStatus.STARTING, CameraStatus.OFF))
  }

  @Test
  fun startingIsSaidByTheStartButtonItself() {
    assertNull(say(CameraStatus.OFF, CameraStatus.STARTING))
  }
}
