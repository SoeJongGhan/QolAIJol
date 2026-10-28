// YoloDetector - YOLO26n object detection with ONNX Runtime
//
// Loads the model once, then per frame: letterbox the I420 frame straight into a reused float
// tensor, run inference, and decode the end-to-end output. The model is NMS-free, so no NMS here.
// Only returns what was seen — deciding what the user hears belongs to the decision layer.
//
// Model contract (see ml/README.md, validated against PyTorch):
//   input  "images"  float32[1, 3, 640, 640]  RGB 0..1, letterboxed, pad 114/255
//   output "output0" float32[1, 300, 6]       x1, y1, x2, y2, score, classId in 640-input pixels

package com.qolaijol.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qolaijol.app.utils.i420PixelArgb
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.json.JSONObject

class YoloDetector
private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val labels: List<String>,
) : Closeable {

  companion object {
    const val TAG = "QolAIJol/YOLO"
    const val INPUT_SIZE = 640
    const val DEFAULT_MIN_CONFIDENCE = 0.25f

    /**
     * The exported model shipped in assets/models/ as <MODEL_NAME>.onnx + <MODEL_NAME>.labels.json
     * (produced by ml/export_model.py; see ml/README.md for how the model was chosen).
     */
    const val MODEL_NAME = "yolo26n_qolaijol_ft"
    private const val MODEL_ASSET = "models/$MODEL_NAME.onnx"
    private const val LABELS_ASSET = "models/$MODEL_NAME.labels.json"
    private const val OUTPUT_ROW_SIZE = 6
    private const val PAD_VALUE = 114f / 255f
    private const val INV_255 = 1f / 255f
    // The NMS-free head occasionally emits two near-identical boxes for one object.
    private const val DUPLICATE_IOU = 0.7f
    private const val INTRA_OP_THREADS = 4

    /**
     * Loads the model and labels from assets. Slow (reads ~10 MB and builds the ORT session) — call
     * off the main thread. Throws if the model is missing or doesn't match the expected contract.
     */
    fun create(context: Context): YoloDetector {
      val started = SystemClock.elapsedRealtime()
      val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
      val labels = loadLabels(context)
      val environment = OrtEnvironment.getEnvironment()
      val options =
          OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(INTRA_OP_THREADS)
          }
      val session = options.use { environment.createSession(modelBytes, it) }
      try {
        validateContract(session, labels.size)
      } catch (e: Exception) {
        session.close()
        throw e
      }
      Log.i(
          TAG,
          "Model $MODEL_NAME loaded in ${SystemClock.elapsedRealtime() - started} ms " +
              "(${modelBytes.size / 1024} KB, ${labels.size} classes)",
      )
      return YoloDetector(environment, session, labels)
    }

    private fun loadLabels(context: Context): List<String> {
      val json = JSONObject(context.assets.open(LABELS_ASSET).bufferedReader().use { it.readText() })
      return List(json.length()) { index -> json.getString(index.toString()) }
    }

    private fun validateContract(session: OrtSession, labelCount: Int) {
      val input = session.inputInfo.values.single().info as TensorInfo
      val output = session.outputInfo.values.first().info as TensorInfo
      val expectedInput = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
      check(input.shape.contentEquals(expectedInput)) {
        "Unsupported model input shape ${input.shape.contentToString()}, expected " +
            expectedInput.contentToString()
      }
      check(output.shape.size == 3 && output.shape[2] == OUTPUT_ROW_SIZE.toLong()) {
        "Unsupported model output shape ${output.shape.contentToString()}, expected [1, N, 6] " +
            "(end-to-end YOLO export)"
      }
      check(labelCount > 0) { "No class labels found in $LABELS_ASSET" }
    }
  }

  private val inputName = session.inputNames.first()
  private val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
  private val planeSize = INPUT_SIZE * INPUT_SIZE
  // Reused across frames: filled on the CPU, then bulk-copied into the direct buffer ORT reads.
  private val inputArray = FloatArray(3 * planeSize)
  private val inputBuffer: FloatBuffer =
      ByteBuffer.allocateDirect(inputArray.size * Float.SIZE_BYTES)
          .order(ByteOrder.nativeOrder())
          .asFloatBuffer()

  // Letterbox geometry, recomputed only when the frame size changes.
  private var geometryWidth = -1
  private var geometryHeight = -1
  private var scale = 1f
  private var padX = 0
  private var padY = 0
  private var contentWidth = 0
  private var contentHeight = 0
  private var sourceX = IntArray(0)
  private var sourceY = IntArray(0)

  /**
   * Detects objects in an I420 [frame]. Serialized: one inference at a time per detector. Throws on
   * an invalid frame or an ONNX Runtime failure — the caller decides how to surface it.
   */
  @Synchronized
  fun detect(frame: CameraFrame, minConfidence: Float = DEFAULT_MIN_CONFIDENCE): DetectionResult {
    val width = frame.width
    val height = frame.height
    require(width > 0 && height > 0 && frame.i420.size >= CameraFrame.i420Size(width, height)) {
      "Invalid frame ${width}x$height with ${frame.i420.size} bytes"
    }

    val started = SystemClock.elapsedRealtime()
    prepareGeometry(width, height)
    fillInput(frame.i420, width, height)
    inputBuffer.rewind()
    inputBuffer.put(inputArray)
    inputBuffer.rewind()
    val preprocessed = SystemClock.elapsedRealtime()

    val detections =
        OnnxTensor.createTensor(environment, inputBuffer, inputShape).use { tensor ->
          session.run(mapOf(inputName to tensor)).use { outputs ->
            decode((outputs[0] as OnnxTensor).floatBuffer, width, height, minConfidence)
          }
        }
    val finished = SystemClock.elapsedRealtime()

    return DetectionResult(
        detections = detections,
        frameWidth = width,
        frameHeight = height,
        preprocessMs = preprocessed - started,
        inferenceMs = finished - preprocessed,
        frameTimestampUs = frame.timestampUs,
    )
  }

  private fun prepareGeometry(width: Int, height: Int) {
    if (width == geometryWidth && height == geometryHeight) return
    geometryWidth = width
    geometryHeight = height
    scale = minOf(INPUT_SIZE.toFloat() / width, INPUT_SIZE.toFloat() / height)
    contentWidth = Math.round(width * scale).coerceAtMost(INPUT_SIZE)
    contentHeight = Math.round(height * scale).coerceAtMost(INPUT_SIZE)
    padX = (INPUT_SIZE - contentWidth) / 2
    padY = (INPUT_SIZE - contentHeight) / 2
    // Nearest-neighbour source index for every destination column/row.
    sourceX = IntArray(contentWidth) { dx -> ((dx + 0.5f) / scale).toInt().coerceAtMost(width - 1) }
    sourceY = IntArray(contentHeight) { dy -> ((dy + 0.5f) / scale).toInt().coerceAtMost(height - 1) }
    // Padding never changes for a given geometry, so fill it once; content is overwritten per frame.
    inputArray.fill(PAD_VALUE)
    Log.d(TAG, "Letterbox ${width}x$height -> ${contentWidth}x$contentHeight pad=($padX,$padY)")
  }

  private fun fillInput(i420: ByteArray, width: Int, height: Int) {
    val greenOffset = planeSize
    val blueOffset = 2 * planeSize
    for (dy in 0 until contentHeight) {
      val sy = sourceY[dy]
      val rowStart = (dy + padY) * INPUT_SIZE + padX
      for (dx in 0 until contentWidth) {
        val argb = i420PixelArgb(i420, width, height, sourceX[dx], sy)
        val index = rowStart + dx
        inputArray[index] = ((argb shr 16) and 0xFF) * INV_255
        inputArray[greenOffset + index] = ((argb shr 8) and 0xFF) * INV_255
        inputArray[blueOffset + index] = (argb and 0xFF) * INV_255
      }
    }
  }

  private fun decode(
      output: FloatBuffer,
      width: Int,
      height: Int,
      minConfidence: Float,
  ): List<Detection> {
    val candidates = ArrayList<Detection>()
    val rows = output.remaining() / OUTPUT_ROW_SIZE
    for (row in 0 until rows) {
      val base = row * OUTPUT_ROW_SIZE
      val score = output.get(base + 4)
      if (score < minConfidence) continue
      // Undo the letterbox, then normalize to 0..1 of the frame and clamp (boxes can overshoot).
      val left = ((output.get(base) - padX) / scale / width).coerceIn(0f, 1f)
      val top = ((output.get(base + 1) - padY) / scale / height).coerceIn(0f, 1f)
      val right = ((output.get(base + 2) - padX) / scale / width).coerceIn(0f, 1f)
      val bottom = ((output.get(base + 3) - padY) / scale / height).coerceIn(0f, 1f)
      if (right <= left || bottom <= top) continue
      val classId = output.get(base + 5).toInt()
      candidates +=
          Detection(
              classId = classId,
              className = labels.getOrElse(classId) { "class $classId" },
              confidence = score,
              boundingBox = BoundingBox(left, top, right, bottom),
          )
    }
    return removeDuplicates(candidates)
  }

  /** Keeps the highest-confidence box among same-class boxes that overlap almost completely. */
  private fun removeDuplicates(candidates: List<Detection>): List<Detection> {
    val kept = ArrayList<Detection>(candidates.size)
    for (candidate in candidates.sortedByDescending { it.confidence }) {
      val duplicate =
          kept.any {
            it.classId == candidate.classId &&
                it.boundingBox.iou(candidate.boundingBox) > DUPLICATE_IOU
          }
      if (!duplicate) kept += candidate
    }
    return kept
  }

  // Synchronized with detect() so the session is never closed mid-inference.
  @Synchronized
  override fun close() {
    session.close()
  }
}
