package com.qolaijol.app

import com.qolaijol.app.vision.BoundingBox
import com.qolaijol.app.vision.Detection

// COCO class ids used in tests (same as yolo26n.labels.json).
private val CLASS_IDS =
    mapOf(
        "person" to 0, "bicycle" to 1, "car" to 2, "bus" to 5, "traffic light" to 9, "chair" to 56,
        "cup" to 41,
        // Fine-tuned QolAIJol model ids (offset so they never collide with COCO ids in tests).
        "pedestrian" to 100, "rider" to 101, "vehicle" to 102, "animal" to 103, "obstacle" to 104,
        "pole" to 105, "stairs" to 106, "crosswalk" to 107,
    )

/** A detection centered at ([centerX], [centerY]) with the given normalized size. */
fun detection(
    className: String,
    centerX: Float,
    confidence: Float = 0.9f,
    centerY: Float = 0.5f,
    width: Float = 0.2f,
    height: Float = 0.4f,
) =
    Detection(
        classId = CLASS_IDS.getValue(className),
        className = className,
        confidence = confidence,
        boundingBox =
            BoundingBox(
                left = centerX - width / 2,
                top = centerY - height / 2,
                right = centerX + width / 2,
                bottom = centerY + height / 2,
            ),
    )
