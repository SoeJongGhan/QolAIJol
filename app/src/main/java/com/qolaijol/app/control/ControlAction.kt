// ControlAction - what the user can ask for without saying a command
//
// The same actions come from three places, so a blind user can use whichever is at hand: gestures
// on the glasses (GlassesGestures), the big buttons on the phone screen, and the buttons on the
// "QolAIJol is on" notification (reachable from the lock screen, also with TalkBack).

package com.qolaijol.app.control

enum class ControlAction {
  /** Push-to-talk: start listening, or stop and handle the command. */
  SPEAK,

  /** Quick offline answer from the detector: what is ahead, on the left and on the right. */
  WHATS_AROUND,

  /** Detailed description of one camera frame by the cloud vision model. */
  DESCRIBE,

  /** Says the last spoken message again. */
  REPEAT,

  /** Turns the obstacle alerts off, or back on. */
  TOGGLE_ALERTS,

  /** Turns the glasses camera off and ends the session. */
  STOP,
}
