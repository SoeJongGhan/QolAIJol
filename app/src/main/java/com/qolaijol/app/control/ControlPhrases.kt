// ControlPhrases - what the app says when the camera turns on, pauses, resumes or turns off
//
// Spoken because a blind user can't see the screen, and the state also changes without the phone:
// a tap on the glasses pauses or resumes, tap-and-hold or folding them stops. Plain Kotlin, unit
// tested on the JVM.

package com.qolaijol.app.control

import com.qolaijol.app.camera.CameraStatus

object ControlPhrases {

  const val STARTING = "Starting."
  const val NO_GLASSES = "Glasses not found. Put them on, turn them on and open the hinges."
  const val GESTURES_ON = "Double tap the glasses to speak."
  const val GESTURES_UNAVAILABLE =
      "Glasses gestures aren't available. Use the buttons on the phone."

  const val SOURCE_GLASSES = "Camera: glasses."
  const val SOURCE_USB = "Camera: USB webcam."
  const val USB_NOT_CONNECTED = "USB camera not found. Connect it to the phone's USB-C port."
  const val USB_PERMISSION_DENIED = "No access to the USB camera. Press Start and allow access."
  const val USB_OPEN_FAILED = "Couldn't open the USB camera. Reconnect it and try again."
  const val USB_DISCONNECTED = "USB camera disconnected."

  /** What to say when the camera goes [from] -> [to]; null when nothing needs saying. */
  fun cameraStatusChange(from: CameraStatus, to: CameraStatus, alertsOn: Boolean): String? =
      when (to) {
        CameraStatus.ON ->
            when {
              from == CameraStatus.PAUSED -> "Resumed."
              alertsOn -> "Camera on. Alerts are on."
              else -> "Camera on. Alerts are off."
            }
        CameraStatus.PAUSED -> "Paused. Alerts are off. Tap the glasses once to resume."
        // A start that failed is explained by its error message.
        CameraStatus.OFF ->
            if (from == CameraStatus.ON || from == CameraStatus.PAUSED) "Camera off." else null
        CameraStatus.STARTING -> null
      }
}
