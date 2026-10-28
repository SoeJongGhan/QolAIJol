// DecisionEngine - decides what (if anything) the user should hear
//
// For every tracked object:
//   confident enough? -> seen in at least two rounds? -> important enough? ->
//   not already announced at this position? -> not announced recently (cooldown)?
// Then the most important candidate becomes one Alert, with a minimum gap between alerts so the
// user is never flooded. Plain Kotlin (time is passed in) so it can be unit tested on the JVM.

package com.qolaijol.app.decision

import com.qolaijol.app.vision.HorizontalPosition
import com.qolaijol.app.vision.ObjectTracker.TrackedObject

data class Alert(
    val message: String,
    val priority: Priority,
    val trackId: Int,
    val className: String,
    val position: HorizontalPosition,
    val timestampMs: Long,
)

class DecisionEngine(
    private val minConfidence: Float = 0.5f,
    /** Objects must be seen in this many detection rounds, filtering one-frame false positives. */
    private val minHits: Int = 2,
    private val minPriority: Priority = Priority.MEDIUM,
    /** Minimum time between two alerts; CRITICAL alerts may interrupt it. */
    private val minAlertGapMs: Long = 2_500L,
    /**
     * The same class at the same position is not repeated within this time, even if the tracker
     * lost the object and found it again under a new id.
     */
    private val eventCooldownMs: Long = 10_000L,
) {

  private data class Candidate(val track: TrackedObject, val priority: Priority)

  // Position each visible track was last announced at.
  private val announcedPositions = HashMap<Int, HorizontalPosition>()
  // "class|position" -> time of the last alert for it.
  private val recentEvents = HashMap<String, Long>()
  private var lastAlertMs: Long? = null

  /** Returns at most one alert for the currently visible [tracks]. */
  @Synchronized
  fun process(tracks: List<TrackedObject>, nowMs: Long): Alert? {
    announcedPositions.keys.retainAll(tracks.map { it.id }.toSet())

    val candidates =
        tracks.mapNotNull { track ->
          val detection = track.detection
          if (track.hits < minHits || detection.confidence < minConfidence) return@mapNotNull null
          val priority = DangerAnalyzer.priorityOf(detection) ?: return@mapNotNull null
          if (priority < minPriority) return@mapNotNull null
          if (announcedPositions[track.id] == track.position) return@mapNotNull null
          val lastEvent = recentEvents[eventKey(track)]
          if (lastEvent != null && nowMs - lastEvent < eventCooldownMs) {
            // Same kind of object at the same place was just announced: treat it as known.
            announcedPositions[track.id] = track.position
            return@mapNotNull null
          }
          Candidate(track, priority)
        }

    val best =
        candidates.maxWithOrNull(
            compareBy<Candidate>(
                { it.priority },
                { it.track.position == HorizontalPosition.CENTER },
                { it.track.detection.confidence },
            ),
        ) ?: return null

    val last = lastAlertMs
    if (last != null && nowMs - last < minAlertGapMs && best.priority < Priority.CRITICAL) {
      return null // still speaking time for the previous alert; try again next round
    }

    val track = best.track
    announcedPositions[track.id] = track.position
    recentEvents[eventKey(track)] = nowMs
    lastAlertMs = nowMs
    return Alert(
        message = EnglishPhrases.alert(track.detection.className, track.position),
        priority = best.priority,
        trackId = track.id,
        className = track.detection.className,
        position = track.position,
        timestampMs = nowMs,
    )
  }

  @Synchronized
  fun reset() {
    announcedPositions.clear()
    recentEvents.clear()
    lastAlertMs = null
  }

  private fun eventKey(track: TrackedObject) = "${track.detection.className}|${track.position}"
}
