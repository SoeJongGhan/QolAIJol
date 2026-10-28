// NotificationActionReceiver - Speak / Repeat / Stop buttons on the "QolAIJol is on" notification
//
// The notification can be used from the lock screen (also with TalkBack), so the phone can stay
// locked in a pocket. Actions go to the camera view model, which handles them exactly like the
// on-screen buttons and the glasses gestures.

package com.qolaijol.app.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NotificationActionReceiver : BroadcastReceiver() {

  companion object {
    private const val ACTION_PREFIX = "com.qolaijol.app.control."

    private val _actions = MutableSharedFlow<ControlAction>(extraBufferCapacity = 4)

    /** Collected by the camera view model while it exists. */
    val actions: SharedFlow<ControlAction> = _actions.asSharedFlow()

    fun pendingIntent(context: Context, action: ControlAction): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.ordinal,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_PREFIX + action.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
  }

  override fun onReceive(context: Context, intent: Intent) {
    val name = intent.action?.removePrefix(ACTION_PREFIX) ?: return
    val action = ControlAction.entries.firstOrNull { it.name == name } ?: return
    _actions.tryEmit(action)
  }
}
