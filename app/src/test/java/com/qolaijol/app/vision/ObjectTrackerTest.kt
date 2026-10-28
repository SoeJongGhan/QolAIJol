package com.qolaijol.app.vision

import com.qolaijol.app.detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerTest {

  @Test
  fun horizontalPositionUsesThirds() {
    assertEquals(HorizontalPosition.LEFT, HorizontalPosition.of(0.10f))
    assertEquals(HorizontalPosition.LEFT, HorizontalPosition.of(0.32f))
    assertEquals(HorizontalPosition.CENTER, HorizontalPosition.of(0.33f))
    assertEquals(HorizontalPosition.CENTER, HorizontalPosition.of(0.50f))
    assertEquals(HorizontalPosition.CENTER, HorizontalPosition.of(0.66f))
    assertEquals(HorizontalPosition.RIGHT, HorizontalPosition.of(0.67f))
    assertEquals(HorizontalPosition.RIGHT, detection("car", centerX = 0.9f).horizontalPosition)
  }

  @Test
  fun sameObjectKeepsItsIdAcrossFrames() {
    val tracker = ObjectTracker()
    val first = tracker.update(listOf(detection("person", 0.50f)), nowMs = 0)
    val second = tracker.update(listOf(detection("person", 0.52f)), nowMs = 500)

    assertEquals(first.single().id, second.single().id)
    assertEquals(2, second.single().hits)
  }

  @Test
  fun differentClassesAndSeparateObjectsGetDifferentIds() {
    val tracker = ObjectTracker()
    val visible =
        tracker.update(
            listOf(detection("person", 0.2f), detection("person", 0.8f), detection("car", 0.8f)),
            nowMs = 0,
        )
    assertEquals(3, visible.map { it.id }.toSet().size)

    // The car overlaps the right person's old box but must not steal its track.
    val next = tracker.update(listOf(detection("car", 0.8f)), nowMs = 500)
    assertEquals(visible.first { it.detection.className == "car" }.id, next.single().id)
  }

  @Test
  fun lostObjectExpiresAndComesBackAsNewTrack() {
    val tracker = ObjectTracker(trackTimeoutMs = 1_500)
    val original = tracker.update(listOf(detection("person", 0.5f)), nowMs = 0).single()

    assertTrue(tracker.update(emptyList(), nowMs = 1_000).isEmpty())
    val found = tracker.update(listOf(detection("person", 0.5f)), nowMs = 2_000).single()

    assertNotEquals(original.id, found.id)
    assertEquals(1, found.hits)
  }
}
