// UsbCameraManager - a USB (UVC) webcam on the phone's USB-C port as the QolAIJol camera
//
// The alternative to the glasses camera: frames from a standard USB webcam go into the same
// pipeline (preview, YOLO, scene description) as CameraFrames. Uses UVCAndroid (libusb + libuvc),
// so it works on any phone with USB host (OTG) support, not only on those whose Camera2 lists
// external cameras. Android asks the user to allow access once each time the webcam is plugged in.
// Library callbacks arrive on the main thread; frames arrive on the library's capture thread.
//
// Frame size: the library sizes each frame buffer for the resolution it asked for but fills it with
// the one the webcam chose, which can differ; read at the wrong width the picture shows
// side-by-side copies. So the real size is worked out from the first frames' pixels (FrameLayout).
// The webcam is asked for 640 x 480 MJPEG (enough for the detector, light on USB bandwidth), and
// larger frames are reduced to at most MAX_WIDTH before anything else touches them.

package com.qolaijol.app.usb

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.qolaijol.app.utils.downscaledHeight
import com.qolaijol.app.utils.downscaledWidth
import com.qolaijol.app.utils.nv21ToI420
import com.qolaijol.app.vision.CameraFrame
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import com.serenegiant.utils.UVCUtils
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbCameraManager(
    application: Application,
    /** Called on the capture thread; the frame's buffer is reused for the next frame. */
    private val onFrame: (CameraFrame) -> Unit,
    /** Called on the main thread when the camera can't be used or stops unexpectedly. */
    private val onProblem: (Problem) -> Unit,
) {

  enum class State {
    OFF,
    /** Waiting for the user's permission, or opening the camera, until the first frame. */
    CONNECTING,
    ON,
  }

  enum class Problem {
    NOT_CONNECTED,
    PERMISSION_DENIED,
    OPEN_FAILED,
    DISCONNECTED,
  }

  companion object {
    private const val TAG = "QolAIJol/UsbCamera"
    private const val PREFERRED_WIDTH = 640
    private const val PREFERRED_HEIGHT = 480
    private const val PREFERRED_FPS = 30

    /** Wider frames are reduced: the detector works at 640 anyway. */
    const val MAX_WIDTH = 640
    private const val BAD_FRAME_LOG_EVERY = 100
    /**
     * The frame layout is worked out on each of the first frames, then rechecked now and then (a
     * dark or covered lens at the start gives no clue).
     */
    private const val LAYOUT_FRAMES = 15
    private const val LAYOUT_RECHECK_EVERY = 30

    // UVC power line frequency control values.
    private const val POWER_LINE_50_HZ = 1
    private const val POWER_LINE_60_HZ = 2

    // Countries on 60 Hz mains (most others, Kazakhstan included, use 50 Hz).
    private val SIXTY_HZ_COUNTRIES =
        setOf(
            "US", "CA", "MX", "BR", "CO", "VE", "EC", "PE", "CR", "GT", "HN", "NI", "SV", "PA",
            "DO", "PR", "CU", "BS", "KR", "TW", "PH", "SA", "LR",
        )

    /** Keep every n-th pixel so frames are at most [MAX_WIDTH] wide. */
    fun downscaleStep(width: Int): Int = maxOf(1, width / MAX_WIDTH)

    /** Mains frequency in [countryIso] (two letters, any case), for the anti-flicker setting. */
    fun mainsHz(countryIso: String): Int = if (countryIso.uppercase(Locale.ROOT) in SIXTY_HZ_COUNTRIES) 60 else 50
  }

  private val _state = MutableStateFlow(State.OFF)
  val state: StateFlow<State> = _state.asStateFlow()

  /** A UVC webcam is plugged in. */
  private val _attached = MutableStateFlow(false)
  val attached: StateFlow<Boolean> = _attached.asStateFlow()

  private val appContext = application.applicationContext
  private val helper: CameraHelper

  // Set on the main thread when the camera opens, read on the capture thread.
  @Volatile private var reportedSize: FrameLayout.Size? = null
  @Volatile private var supportedSizes: List<FrameLayout.Size> = emptyList()
  // False after stop(): frames that are still in flight are dropped.
  @Volatile private var active = false

  // Capture thread only (reset in start(), while no frames arrive).
  private var nv21 = ByteArray(0)
  private var i420 = ByteArray(0)
  @Volatile private var frameSize: FrameLayout.Size? = null
  private var frameCount = 0
  private var badFrames = 0

  init {
    // Hand the library our Application instead of letting it look one up by reflection.
    UVCUtils.init(application)
    helper = CameraHelper()
    helper.setStateCallback(
        object : ICameraHelper.StateCallback {
          override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "Webcam plugged in: ${device.productName ?: device.deviceName}")
            _attached.value = true
          }

          override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (!active) return
            // 640 x 480 MJPEG; if the webcam doesn't offer it, the library picks its own size.
            helper.openCamera(
                UVCParam(
                    Size(
                        UVCCamera.UVC_VS_FRAME_MJPEG,
                        PREFERRED_WIDTH,
                        PREFERRED_HEIGHT,
                        PREFERRED_FPS,
                        arrayListOf(PREFERRED_FPS),
                    ),
                    0,
                ),
            )
          }

          override fun onCameraOpen(device: UsbDevice) {
            if (!active) return
            supportedSizes =
                helper.supportedSizeList.orEmpty().map { FrameLayout.Size(it.width, it.height) }.distinct()
            reportedSize = helper.previewSize?.let { FrameLayout.Size(it.width, it.height) }
            Log.i(TAG, "Webcam open at $reportedSize; it offers $supportedSizes")
            setPowerLineFrequency()
            helper.startPreview()
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
          }

          override fun onCameraClose(device: UsbDevice) {
            _state.value = State.OFF
          }

          override fun onDeviceClose(device: UsbDevice) {
            _state.value = State.OFF
          }

          override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "Webcam unplugged")
            _attached.value = !helper.deviceList.isNullOrEmpty()
            if (active) fail(Problem.DISCONNECTED)
          }

          override fun onCancel(device: UsbDevice) {
            Log.w(TAG, "USB access to the webcam was not allowed")
            if (active) fail(Problem.PERMISSION_DENIED)
          }

          override fun onError(device: UsbDevice, e: CameraException) {
            Log.e(TAG, "Webcam error", e)
            if (active) fail(Problem.OPEN_FAILED)
          }
        },
    )
    _attached.value = !helper.deviceList.isNullOrEmpty()
  }

  private val frameCallback = IFrameCallback { buffer -> handleFrame(buffer) }

  /** Opens the first plugged-in webcam; Android asks for access first if needed. */
  fun start() {
    if (_state.value != State.OFF) return
    val device = helper.deviceList?.firstOrNull()
    if (device == null) {
      _attached.value = false
      onProblem(Problem.NOT_CONNECTED)
      return
    }
    active = true
    reportedSize = null
    supportedSizes = emptyList()
    frameSize = null
    frameCount = 0
    badFrames = 0
    _state.value = State.CONNECTING
    helper.selectDevice(device)
  }

  fun stop() {
    if (!active && _state.value == State.OFF) return
    active = false
    _state.value = State.OFF
    helper.stopPreview()
    helper.closeCamera()
  }

  /** Frees the library; the manager can't be used afterwards. */
  fun release() {
    active = false
    _state.value = State.OFF
    helper.release()
  }

  private fun fail(problem: Problem) {
    active = false
    _state.value = State.OFF
    helper.closeCamera()
    onProblem(problem)
  }

  /**
   * Lights flicker at twice the mains frequency; a webcam set for the wrong one shows rolling
   * bands. Most webcams default to 60 Hz; Kazakhstan, like most countries, uses 50 Hz.
   */
  private fun setPowerLineFrequency() {
    try {
      val control = helper.uvcControl ?: return
      if (!control.isPowerlineFrequencyEnable) return
      val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
      val country =
          telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
              ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
              ?: Locale.getDefault().country
      val hz = mainsHz(country)
      control.setPowerlineFrequency(if (hz == 60) POWER_LINE_60_HZ else POWER_LINE_50_HZ)
      Log.i(TAG, "Anti-flicker set to $hz Hz (country \"$country\")")
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't set the webcam's anti-flicker", e)
    }
  }

  private fun handleFrame(buffer: ByteBuffer) {
    if (!active) return
    val bytes = buffer.remaining()
    if (nv21.size != bytes) nv21 = ByteArray(bytes)
    buffer.get(nv21, 0, bytes)
    val size = frameLayout(bytes) ?: return

    val step = downscaleStep(size.width)
    val width = downscaledWidth(size.width, step)
    val height = downscaledHeight(size.height, step)
    val outBytes = CameraFrame.i420Size(width, height)
    if (i420.size != outBytes) i420 = ByteArray(outBytes)
    nv21ToI420(nv21, size.width, size.height, i420, step)

    // compareAndSet: a stop() racing with this frame must not be turned back into ON.
    _state.compareAndSet(State.CONNECTING, State.ON)
    onFrame(CameraFrame(i420, width, height, SystemClock.elapsedRealtimeNanos() / 1000))
  }

  /**
   * The real size of the frame now in [nv21] ([bytes] long), worked out from its pixels on the
   * first [LAYOUT_FRAMES] frames and every [LAYOUT_RECHECK_EVERY]-th after. Null drops the frame.
   */
  private fun frameLayout(bytes: Int): FrameLayout.Size? {
    val current = frameSize
    frameCount++
    val check = frameCount <= LAYOUT_FRAMES || frameCount % LAYOUT_RECHECK_EVERY == 0
    if (current != null && !check) return current
    val reported = reportedSize
    val size = FrameLayout.detect(nv21, bytes, current ?: reported, supportedSizes)
    if (size == null) {
      if (badFrames++ % BAD_FRAME_LOG_EVERY == 0) {
        Log.w(TAG, "Dropping webcam frame of $bytes bytes: no known size fits (reported $reported)")
      }
      return current
    }
    if (size != current) {
      val step = downscaleStep(size.width)
      Log.i(
          TAG,
          "Webcam frames: ${size.width}x${size.height}" +
              (if (size != reported) " (the library reported $reported)" else "") +
              if (step > 1) {
                ", reduced to ${downscaledWidth(size.width, step)}x" +
                    "${downscaledHeight(size.height, step)}"
              } else {
                ""
              },
      )
      frameSize = size
    }
    return size
  }
}
