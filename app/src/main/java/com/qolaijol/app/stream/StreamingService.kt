/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.qolaijol.app.stream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qolaijol.app.MainActivity
import com.qolaijol.app.R
import com.qolaijol.app.control.ControlAction
import com.qolaijol.app.control.NotificationActionReceiver

object ForegroundServiceNotificationIds {
  const val ACTIVE_RECORDING_NOTIFICATION_ID = 1001
}

/**
 * Foreground service that keeps the camera streaming alive when the screen is locked or the app is
 * in the background.
 *
 * This service:
 * - Displays a persistent notification while streaming, with Speak / Repeat / Stop buttons that
 *   work from the lock screen
 * - Acquires a partial wake lock to prevent CPU sleep
 * - Allows the streaming and recording to continue when the app is backgrounded
 * - Keeps the microphone (voice commands from the glasses) and location (walking directions)
 *   available while the phone is locked, when those permissions are granted
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "StreamingService"
    // A channel the lock screen shows; the earlier low-importance one may be hidden there.
    private const val CHANNEL_ID = "qolaijol_status"
    private const val OLD_CHANNEL_ID = "streaming_channel"
    private const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private const val WAKELOCK_TAG = "CameraAccess::StreamingWakeLock"
    // Safety backstop for a leaked lock; releaseWakeLock() is the primary release.
    private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    private const val ACTION_STOP =
        "com.qolaijol.app.stream.STOP"

    fun start(context: Context) {
      val intent =
          Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      // Route the stop through onStartCommand (a STOP-action start) rather than stopService(). Once
      // startForegroundService() is called the system requires startForeground() to follow; calling
      // stopService() while that start is still pending tears the service down with the foreground
      // promise unfulfilled, which crashes the app with
      // ForegroundServiceDidNotStartInTimeException.
      // Going through onStartCommand guarantees startForeground() runs before the service stops.
      val intent =
          Intent(context, StreamingService::class.java).apply {
            `package` = context.packageName
            action = ACTION_STOP
          }
      context.startForegroundService(intent)
    }

    /**
     * Re-enters the foreground so a permission granted since the start (location) applies while the
     * phone is locked. Call from the foreground.
     */
    fun refresh(context: Context) {
      try {
        start(context)
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Could not refresh the foreground service", e)
      }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  inner class LocalBinder : Binder() {
    fun getService(): StreamingService = this@StreamingService
  }

  private val binder = LocalBinder()

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Always enter the foreground first — even for a STOP request that may have raced ahead of a
    // pending start — so the startForegroundService() contract is always satisfied. Uses
    // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE for wearable streaming, which doesn't require CAMERA
    // permission since the phone's camera isn't used.
    val stopping = intent?.action == ACTION_STOP
    if (!enterForeground(if (stopping) CONNECTED_DEVICE else foregroundTypes())) {
      Log.e(TAG, "Failed to enter foreground; stopping service")
      stopSelf()
      return START_NOT_STICKY
    }

    if (stopping) {
      Log.d(TAG, "Service stopping")
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    Log.d(TAG, "Service started")
    acquireWakeLock()
    return START_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "Service destroyed")
    releaseWakeLock()
    super.onDestroy()
  }

  /**
   * Microphone and location let voice commands from the glasses and walking directions keep working
   * while the phone is locked. Android allows each type only with its permission granted.
   */
  private fun foregroundTypes(): Int {
    var types = CONNECTED_DEVICE
    if (isGranted(Manifest.permission.RECORD_AUDIO)) {
      types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
      types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    return types
  }

  private fun isGranted(permission: String): Boolean =
      ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  /**
   * Starts or updates the foreground notification. Android refuses the microphone and location
   * types when the start came from the background, so connected-device alone is the fallback.
   */
  private fun enterForeground(types: Int): Boolean {
    val notification = createNotification()
    for (attempt in listOf(types, CONNECTED_DEVICE).distinct()) {
      try {
        startForeground(
            ForegroundServiceNotificationIds.ACTIVE_RECORDING_NOTIFICATION_ID,
            notification,
            attempt,
        )
        return true
      } catch (e: Exception) {
        Log.e(TAG, "Failed to enter foreground with service types $attempt", e)
      }
    }
    return false
  }

  private fun createNotificationChannel() {
    // Default importance so the lock screen shows it; silent, it only carries status and buttons.
    val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT,
    )
        .apply {
          description = getString(R.string.notification_channel_description)
          setShowBadge(false)
          setSound(null, null)
          enableVibration(false)
          lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID)
    notificationManager.createNotificationChannel(channel)
  }

  private fun createNotification(): Notification {
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_body))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pendingIntent)
        .addAction(controlAction(R.string.notification_action_speak, ControlAction.SPEAK))
        .addAction(controlAction(R.string.notification_action_repeat, ControlAction.REPEAT))
        .addAction(controlAction(R.string.notification_action_stop, ControlAction.STOP))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
  }

  private fun controlAction(labelRes: Int, action: ControlAction): NotificationCompat.Action =
      NotificationCompat.Action.Builder(
              0,
              getString(labelRes),
              NotificationActionReceiver.pendingIntent(this, action),
          )
          .build()

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(WAKELOCK_TIMEOUT_MS)
          }
      Log.d(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }
}
