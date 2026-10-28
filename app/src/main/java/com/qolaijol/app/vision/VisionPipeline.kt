// VisionPipeline - frame throttling + background YOLO inference
//
// Camera frames arrive at the stream rate (24 fps); YOLO runs at most once per
// DETECTION_INTERVAL_MS and never on more than one frame at a time. Frames that arrive while a
// detection is running, or before the interval has elapsed, are skipped — the camera thread is
// never blocked by inference.

package com.qolaijol.app.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VisionPipeline(
    context: Context,
    private val detectionIntervalMs: Long = DETECTION_INTERVAL_MS,
    private val minConfidence: Float = YoloDetector.DEFAULT_MIN_CONFIDENCE,
) {

  companion object {
    private const val TAG = YoloDetector.TAG

    /** Minimum time between two YOLO runs. Tune after measuring on the target phone. */
    const val DETECTION_INTERVAL_MS = 500L

    /**
     * If no camera frame arrives for this long, the last detections are dropped. The SDK can keep
     * reporting STREAMING after the glasses disappear (e.g. powered off), and stale detections must
     * never be presented as the current scene.
     */
    const val FRAME_TIMEOUT_MS = 2_000L
    private const val WATCHDOG_PERIOD_MS = 500L
  }

  enum class Status {
    IDLE,
    LOADING,
    READY,
    ERROR,
  }

  data class State(
      val status: Status = Status.IDLE,
      /** Model-load or last inference error; cleared by the next successful detection. */
      val error: String? = null,
      val latestResult: DetectionResult? = null,
  )

  private val appContext = context.applicationContext
  // Single thread: model loading, every inference and close() run strictly in order.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  // Every detection round as an event (StateFlow would conflate/deduplicate identical results).
  private val _results =
      MutableSharedFlow<DetectionResult>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val results: SharedFlow<DetectionResult> = _results.asSharedFlow()

  @Volatile private var detector: YoloDetector? = null
  private val started = AtomicBoolean(false)
  private val busy = AtomicBoolean(false)

  // Written on the camera frame thread, read by the frame watchdog.
  @Volatile private var lastFrameMs = 0L

  // Only touched on the camera frame thread (lastScheduledMs) or while `busy` is held (frameCopy).
  private var lastScheduledMs = 0L
  private var frameCopy = ByteArray(0)

  /** Latest detections, readable from any thread (used by the preview overlay). */
  @Volatile
  var latestDetections: List<Detection> = emptyList()
    private set

  /** Loads the model in the background. Safe to call more than once. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    _state.update { it.copy(status = Status.LOADING, error = null) }
    scope.launch {
      try {
        detector = YoloDetector.create(appContext)
        _state.update { it.copy(status = Status.READY, error = null) }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load YOLO model", e)
        _state.update {
          it.copy(status = Status.ERROR, error = "Couldn't load the model: ${e.message ?: e.javaClass.simpleName}")
        }
      } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory while loading YOLO model", e)
        _state.update { it.copy(status = Status.ERROR, error = "Not enough memory to load the model") }
      }
    }
    scope.launch { watchFrames() }
  }

  /** Drops the last result once frames stop arriving (see [FRAME_TIMEOUT_MS]). */
  private suspend fun watchFrames() {
    while (true) {
      delay(WATCHDOG_PERIOD_MS)
      if (_state.value.latestResult == null) continue
      val silentMs = SystemClock.elapsedRealtime() - lastFrameMs
      if (silentMs >= FRAME_TIMEOUT_MS) {
        Log.w(TAG, "No camera frames for $silentMs ms; clearing stale detections")
        latestDetections = emptyList()
        _state.update {
          it.copy(latestResult = null, error = "No camera frames for ${silentMs / 1000} s")
        }
      }
    }
  }

  /**
   * Offers a camera frame. Called on the camera frame thread; returns immediately. The frame is
   * copied only when it is actually scheduled for detection.
   */
  fun onFrame(frame: CameraFrame) {
    val now = SystemClock.elapsedRealtime()
    lastFrameMs = now
    if (detector == null) return
    if (now - lastScheduledMs < detectionIntervalMs) return
    if (!busy.compareAndSet(false, true)) return
    lastScheduledMs = now

    val size = CameraFrame.i420Size(frame.width, frame.height)
    if (frameCopy.size != size) frameCopy = ByteArray(size)
    System.arraycopy(frame.i420, 0, frameCopy, 0, size)
    val copy = CameraFrame(frameCopy, frame.width, frame.height, frame.timestampUs)

    scope.launch {
      try {
        val current = detector ?: return@launch
        val result = current.detect(copy, minConfidence)
        latestDetections = result.detections
        _state.update { it.copy(latestResult = result, error = null) }
        _results.tryEmit(result)
        logResult(result)
      } catch (e: Exception) {
        Log.e(TAG, "Inference failed", e)
        _state.update { it.copy(error = "Detection error: ${e.message ?: e.javaClass.simpleName}") }
      } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory during inference", e)
        _state.update { it.copy(error = "Not enough memory for detection") }
      } finally {
        busy.set(false)
      }
    }
  }

  /** Forgets the last result, e.g. when the stream stops, so stale boxes aren't shown. */
  fun clearResults() {
    latestDetections = emptyList()
    // Transient (frame/inference) errors belong to the stopped stream; model errors stay visible.
    _state.update {
      it.copy(latestResult = null, error = if (it.status == Status.READY) null else it.error)
    }
  }

  /** Releases the model after any in-flight inference finishes. The pipeline can't be reused. */
  fun close() {
    scope.launch {
      detector?.close()
      detector = null
      Log.d(TAG, "Vision pipeline closed")
      scope.cancel()
    }
  }

  private fun logResult(result: DetectionResult) {
    Log.d(
        TAG,
        "Frame ${result.frameWidth}x${result.frameHeight}: ${result.detections.size} objects, " +
            "preprocess=${result.preprocessMs} ms inference=${result.inferenceMs} ms",
    )
    for (detection in result.detections) {
      Log.d(
          TAG,
          String.format(
              Locale.ROOT,
              "Detected %s confidence=%.2f position=%s center=(%.2f, %.2f)",
              detection.className,
              detection.confidence,
              detection.horizontalPosition,
              detection.centerX,
              detection.centerY,
          ),
      )
    }
  }
}
