// GlassesGestures - double tap, swipes and camera-button presses on the glasses as ControlActions
//
// Uses the DAT Inputs capability (beta: development and beta release channels only; no runtime
// permission). While a session runs, the glasses keep two touchpad gestures for themselves and the
// app never sees them: a single tap pauses or resumes the session, tap-and-hold stops it. What does
// reach the app:
//   double tap                    -> SPEAK
//   swipe, either direction       -> WHATS_AROUND (the wearer's settings can invert directions, so
//                                    both do the same thing)
//   capture button, press         -> DESCRIBE
//   capture button, press twice   -> REPEAT
//   capture button, hold          -> TOGGLE_ALERTS
//   action button (where present) -> SPEAK
// Attached once per session after it starts; on Android the SDK restores it after a reconnect.

package com.qolaijol.app.control

import android.util.Log
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsError
import com.meta.wearable.dat.inputs.types.InputsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GlassesGestures(
    private val scope: CoroutineScope,
    private val onAction: (ControlAction) -> Unit,
) {

  enum class Status {
    /** No session, the session is paused, or the glasses are off the face. */
    OFF,
    CONNECTING,
    ON,
    /** Not allowed for this app or release channel, or not supported by these glasses. */
    UNAVAILABLE,
  }

  companion object {
    private const val TAG = "QolAIJol/Gestures"

    // Only the surfaces the app uses: a requested source's gestures no longer reach the glasses'
    // own features. Real glasses don't deliver Back yet, so the system keeps it.
    val CONFIGURATION =
        InputsConfiguration(
            sources =
                setOf(
                    InputSource.CAPTOUCH,
                    InputSource.CAPTURE_BUTTON,
                    InputSource.ACTION_BUTTON,
                    InputSource.NEURAL_BAND,
                ),
            consumeBack = false,
        )

    fun actionFor(event: InputEvent): ControlAction? =
        when (event) {
          is InputEvent.Select -> ControlAction.SPEAK
          is InputEvent.Nav -> ControlAction.WHATS_AROUND
          is InputEvent.Capture ->
              when (event.pressType) {
                CapturePressType.SHORT_PRESS -> ControlAction.DESCRIBE
                CapturePressType.DOUBLE_PRESS -> ControlAction.REPEAT
                CapturePressType.HOLD -> ControlAction.TOGGLE_ALERTS
              }
          is InputEvent.Button -> ControlAction.SPEAK
          is InputEvent.Back,
          is InputEvent.Drag -> null
        }

    fun statusOf(state: InputsState, error: InputsError?): Status =
        when {
          error == InputsError.PERMISSION_DENIED ||
              error == InputsError.ACTIVATION_FAILED ||
              error == InputsError.ACTIVATION_TIMEOUT -> Status.UNAVAILABLE
          // Lost with the connection; the SDK restores it when the glasses reconnect.
          error != null -> Status.OFF
          state == InputsState.ACTIVE -> Status.ON
          state == InputsState.ACTIVATING -> Status.CONNECTING
          else -> Status.OFF
        }
  }

  private val _status = MutableStateFlow(Status.OFF)
  val status: StateFlow<Status> = _status.asStateFlow()

  private var inputs: Inputs? = null
  private var jobs: List<Job> = emptyList()

  /** Call when [session] is STARTED; does nothing if gestures are already attached. */
  fun attach(session: DeviceSession) {
    if (inputs != null) return
    session
        .addInputs(CONFIGURATION)
        .onSuccess { added ->
          inputs = added
          observe(added)
        }
        .onFailure { error, _ ->
          Log.w(TAG, "Glasses gestures unavailable: ${error.description}")
          _status.value = Status.UNAVAILABLE
        }
  }

  /** The session ended; stopping it already removed the capability. */
  fun detach() {
    jobs.forEach { it.cancel() }
    jobs = emptyList()
    inputs = null
    _status.value = Status.OFF
  }

  private fun observe(added: Inputs) {
    jobs =
        listOf(
            scope.launch {
              combine(added.state, added.errors) { state, error ->
                    if (error != null) Log.w(TAG, "Glasses gestures error: ${error.description}")
                    statusOf(state, error)
                  }
                  .collect { _status.value = it }
            },
            scope.launch {
              added.events.collect { event ->
                Log.i(TAG, "Gesture: $event")
                actionFor(event)?.let(onAction)
              }
            },
        )
  }
}
