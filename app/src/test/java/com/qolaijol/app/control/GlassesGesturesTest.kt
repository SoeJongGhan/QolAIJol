package com.qolaijol.app.control

import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.DragAction
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsError
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.inputs.types.NavDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesGesturesTest {

  private fun action(event: InputEvent) = GlassesGestures.actionFor(event)

  @Test
  fun doubleTapSpeaks() {
    assertEquals(ControlAction.SPEAK, action(InputEvent.Select(InputSource.CAPTOUCH, 0L)))
    assertEquals(ControlAction.SPEAK, action(InputEvent.Select(InputSource.NEURAL_BAND, 0L)))
    assertEquals(
        ControlAction.SPEAK,
        action(InputEvent.Button(ButtonType.ACTION, InputSource.ACTION_BUTTON, 0L)),
    )
  }

  @Test
  fun swipeInAnyDirectionTellsWhatIsAround() {
    // The wearer's settings can invert swipe directions, so every direction does the same thing.
    for (direction in NavDirection.entries) {
      assertEquals(
          ControlAction.WHATS_AROUND,
          action(InputEvent.Nav(direction, InputSource.CAPTOUCH, 0L)),
      )
    }
  }

  @Test
  fun captureButton() {
    fun capture(type: CapturePressType) =
        action(InputEvent.Capture(type, InputSource.CAPTURE_BUTTON, 0L))
    assertEquals(ControlAction.DESCRIBE, capture(CapturePressType.SHORT_PRESS))
    assertEquals(ControlAction.REPEAT, capture(CapturePressType.DOUBLE_PRESS))
    assertEquals(ControlAction.TOGGLE_ALERTS, capture(CapturePressType.HOLD))
  }

  @Test
  fun backAndDragAreIgnored() {
    assertNull(action(InputEvent.Back(InputSource.CAPTOUCH, 0L)))
    assertNull(
        action(InputEvent.Drag(DragAction.MOVE, 0f, 0f, 1f, 1f, InputSource.NEURAL_BAND_DRAG, 0L)),
    )
  }

  @Test
  fun status() {
    assertEquals(GlassesGestures.Status.ON, GlassesGestures.statusOf(InputsState.ACTIVE, null))
    assertEquals(
        GlassesGestures.Status.CONNECTING,
        GlassesGestures.statusOf(InputsState.ACTIVATING, null),
    )
    assertEquals(GlassesGestures.Status.OFF, GlassesGestures.statusOf(InputsState.INACTIVE, null))
    // Not allowed or not supported: tell the user to use the phone.
    for (error in
        listOf(
            InputsError.PERMISSION_DENIED,
            InputsError.ACTIVATION_FAILED,
            InputsError.ACTIVATION_TIMEOUT,
        )) {
      assertEquals(
          GlassesGestures.Status.UNAVAILABLE,
          GlassesGestures.statusOf(InputsState.INACTIVE, error),
      )
    }
    // Lost with the connection: comes back by itself when the glasses reconnect.
    assertEquals(
        GlassesGestures.Status.OFF,
        GlassesGestures.statusOf(InputsState.INACTIVE, InputsError.DEVICE_DISCONNECTED),
    )
  }
}
