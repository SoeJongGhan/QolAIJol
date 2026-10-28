// ObjectTracker - keeps the identity of objects across frames
//
// A simple IoU tracker: a detection continues an existing track when it has the same class and its
// box overlaps that track's last box enough. This lets the decision layer tell "the same person as
// half a second ago" from "a new person", so it doesn't announce the same object on every frame.
// Plain Kotlin (no Android types) so it can be unit tested on the JVM.

package com.qolaijol.app.vision

class ObjectTracker(
    /** Minimum box overlap for a detection to continue a track. Detections run ~2x per second. */
    private val minMatchIou: Float = 0.2f,
    /** A track not seen for this long is dropped (about three missed detection rounds). */
    private val trackTimeoutMs: Long = 1_500L,
) {

  data class TrackedObject(
      val id: Int,
      val detection: Detection,
      val firstSeenMs: Long,
      val lastSeenMs: Long,
      /** How many detection rounds this object was seen in. */
      val hits: Int,
  ) {
    val position: HorizontalPosition
      get() = detection.horizontalPosition
  }

  private val tracks = mutableListOf<TrackedObject>()
  private var nextId = 1

  /** Updates the tracks with one frame's detections and returns the objects visible now. */
  @Synchronized
  fun update(detections: List<Detection>, nowMs: Long): List<TrackedObject> {
    tracks.removeAll { nowMs - it.lastSeenMs > trackTimeoutMs }

    // Greedy matching: best-overlapping same-class (track, detection) pairs first.
    val pairs =
        buildList {
              tracks.forEachIndexed { trackIndex, track ->
                detections.forEachIndexed { detectionIndex, detection ->
                  if (detection.classId == track.detection.classId) {
                    val iou = detection.boundingBox.iou(track.detection.boundingBox)
                    if (iou >= minMatchIou) add(Triple(iou, trackIndex, detectionIndex))
                  }
                }
              }
            }
            .sortedByDescending { it.first }

    val matchedTracks = HashSet<Int>()
    val matchedDetections = HashSet<Int>()
    for ((_, trackIndex, detectionIndex) in pairs) {
      if (trackIndex in matchedTracks || detectionIndex in matchedDetections) continue
      matchedTracks += trackIndex
      matchedDetections += detectionIndex
      val track = tracks[trackIndex]
      tracks[trackIndex] =
          track.copy(detection = detections[detectionIndex], lastSeenMs = nowMs, hits = track.hits + 1)
    }

    detections.forEachIndexed { index, detection ->
      if (index !in matchedDetections) {
        tracks += TrackedObject(nextId++, detection, firstSeenMs = nowMs, lastSeenMs = nowMs, hits = 1)
      }
    }

    return tracks.filter { it.lastSeenMs == nowMs }
  }

  @Synchronized
  fun reset() {
    tracks.clear()
  }
}
