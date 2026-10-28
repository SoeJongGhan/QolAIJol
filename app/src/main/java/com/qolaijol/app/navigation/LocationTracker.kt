// LocationTracker - the phone's GPS position for walking navigation
//
// Uses the platform LocationManager (fused provider when available, else GPS), no Play Services.
// There is deliberately no "default" location: if the position is unknown the user is told so
// (the Python prototype silently used fixed coordinates instead).

package com.qolaijol.app.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class LocationTracker(context: Context) {

  companion object {
    private const val TAG = "QolAIJol/Location"
    private const val UPDATE_INTERVAL_MS = 1_000L
    /** A cached fix younger than this is used right away. */
    private const val MAX_CACHED_AGE_MS = 30_000L
    /** If no fresh fix arrives in time, a cached fix up to this old is still used (then corrected by rerouting). */
    private const val FALLBACK_CACHED_AGE_MS = 120_000L
  }

  private val appContext = context.applicationContext
  private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  private var listener: LocationListener? = null

  fun hasPermission(): Boolean =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED

  fun isEnabled(): Boolean = locationManager.isLocationEnabled

  private fun provider(): String =
      if (locationManager.hasProvider(LocationManager.FUSED_PROVIDER) &&
          locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
        LocationManager.FUSED_PROVIDER
      } else {
        LocationManager.GPS_PROVIDER
      }

  /** Current position, or null if it can't be determined within [timeoutMs]. */
  @SuppressLint("MissingPermission")
  suspend fun currentLocation(timeoutMs: Long = 15_000): Location? {
    if (!hasPermission() || !isEnabled()) return null
    val provider = provider()
    val cached = locationManager.getLastKnownLocation(provider)
    val cachedAgeMs =
        cached?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
    if (cached != null && cachedAgeMs != null && cachedAgeMs in 0..MAX_CACHED_AGE_MS) return cached
    val fresh =
        withTimeoutOrNull(timeoutMs) {
          suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            locationManager.getCurrentLocation(
                provider,
                highAccuracyRequest(),
                cancel,
                appContext.mainExecutor,
            ) { location ->
              if (continuation.isActive) continuation.resume(location)
            }
          }
        }
    if (fresh != null) return fresh
    if (cached != null && cachedAgeMs != null && cachedAgeMs in 0..FALLBACK_CACHED_AGE_MS) {
      Log.w(TAG, "No fresh fix; using a ${cachedAgeMs / 1000} s old one")
      return cached
    }
    return null
  }

  /** Starts ~1 Hz updates delivered on the main thread. Safe to call again (restarts). */
  @SuppressLint("MissingPermission")
  fun start(onLocation: (Location) -> Unit) {
    stop()
    if (!hasPermission()) return
    val newListener = LocationListener { location -> onLocation(location) }
    listener = newListener
    val provider = provider()
    locationManager.requestLocationUpdates(provider, highAccuracyRequest(), appContext.mainExecutor, newListener)
    Log.i(TAG, "Location updates started ($provider, high accuracy)")
  }

  /**
   * GPS-grade accuracy. The default (balanced) quality lets the fused provider answer from Wi-Fi
   * and cell towers only — 20-100 m, too coarse for walking directions — and never turns GPS on.
   */
  private fun highAccuracyRequest(): LocationRequest =
      LocationRequest.Builder(UPDATE_INTERVAL_MS)
          .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
          .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
          .build()

  fun stop() {
    listener?.let {
      locationManager.removeUpdates(it)
      Log.i(TAG, "Location updates stopped")
    }
    listener = null
  }
}
