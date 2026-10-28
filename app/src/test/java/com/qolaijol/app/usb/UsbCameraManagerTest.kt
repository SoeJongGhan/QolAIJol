package com.qolaijol.app.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbCameraManagerTest {

  @Test
  fun wideFramesAreReducedToAtMost640() {
    assertEquals(1, UsbCameraManager.downscaleStep(640))
    assertEquals(1, UsbCameraManager.downscaleStep(1024))
    assertEquals(2, UsbCameraManager.downscaleStep(1280))
    assertEquals(3, UsbCameraManager.downscaleStep(1920))
    assertEquals(6, UsbCameraManager.downscaleStep(3840))
  }

  @Test
  fun antiFlickerFollowsTheMainsFrequency() {
    assertEquals(50, UsbCameraManager.mainsHz("kz"))
    assertEquals(50, UsbCameraManager.mainsHz("RU"))
    assertEquals(60, UsbCameraManager.mainsHz("us"))
    assertEquals(60, UsbCameraManager.mainsHz("KR"))
  }
}
