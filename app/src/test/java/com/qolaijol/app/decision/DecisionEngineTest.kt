package com.qolaijol.app.decision

import com.qolaijol.app.detection
import com.qolaijol.app.vision.Detection
import com.qolaijol.app.vision.ObjectTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DecisionEngineTest {

  private lateinit var tracker: ObjectTracker
  private lateinit var engine: DecisionEngine

  @Before
  fun setUp() {
    tracker = ObjectTracker()
    engine = DecisionEngine()
  }

  /** Runs one detection round at [nowMs] and returns the alert text, if any. */
  private fun round(nowMs: Long, vararg detections: Detection): String? =
      engine.process(tracker.update(detections.toList(), nowMs), nowMs)?.message

  @Test
  fun personAheadIsAnnouncedOnceAfterTwoSightings() {
    val person = detection("person", centerX = 0.5f, confidence = 0.94f)

    assertNull("one sighting is not enough", round(0, person))
    assertEquals("Person ahead.", round(500, person))
    // Still there, same place: silence instead of "Person, person, person".
    for (t in 1_000L..30_000L step 500) assertNull(round(t, person))
  }

  @Test
  fun carOnTheLeft() {
    val car = detection("car", centerX = 0.15f, confidence = 0.92f)
    round(0, car)
    assertEquals("Car on the left.", round(500, car))
  }

  @Test
  fun movingToAnotherZoneIsAnnouncedAgain() {
    // One detection round every 500 ms while the person walks from the center to the right.
    val alerts = mutableListOf<String>()
    for (step in 0..10) {
      val centerX = 0.50f + 0.025f * step // 0.50 -> 0.75
      round(step * 500L, detection("person", centerX))?.let { alerts += it }
    }
    assertEquals(listOf("Person ahead.", "Person on the right."), alerts)
  }

  @Test
  fun lowConfidenceAndUnimportantObjectsAreIgnored() {
    val weak = detection("person", 0.5f, confidence = 0.4f)
    val cup = detection("cup", 0.2f, confidence = 0.95f)
    for (t in 0L..5_000L step 500) assertNull(round(t, weak, cup))
  }

  @Test
  fun obstaclesOnlyMatterInTheWalkingPath() {
    val chairOnTheSide = detection("chair", 0.1f)
    for (t in 0L..2_000L step 500) assertNull(round(t, chairOnTheSide))

    engine.reset()
    tracker.reset()
    val chairAhead = detection("chair", 0.5f)
    round(0, chairAhead)
    assertEquals("Chair ahead.", round(500, chairAhead))
  }

  @Test
  fun fineTunedModelClasses() {
    val obstacleAhead = detection("obstacle", 0.5f)
    round(0, obstacleAhead)
    assertEquals("Obstacle ahead.", round(500, obstacleAhead))

    engine.reset()
    tracker.reset()
    val poleOnTheSide = detection("pole", 0.1f, width = 0.05f)
    val cyclistRight = detection("rider", 0.8f)
    round(10_000, poleOnTheSide, cyclistRight)
    assertEquals("Cyclist on the right.", round(10_500, poleOnTheSide, cyclistRight))
    for (t in 11_000L..20_000L step 500) assertNull(round(t, poleOnTheSide, cyclistRight))
  }

  @Test
  fun unreliableAndMisleadingClassesAreNeverSpoken() {
    val stairs = detection("stairs", 0.5f, confidence = 0.95f)
    val light = detection("traffic light", 0.5f, confidence = 0.95f, width = 0.05f, height = 0.1f)
    val crosswalk = detection("crosswalk", 0.5f, confidence = 0.95f, centerY = 0.8f, height = 0.1f)
    for (t in 0L..10_000L step 500) assertNull(round(t, stairs, light, crosswalk))
  }

  @Test
  fun mostImportantObjectGoesFirstAndAlertsAreSpacedOut() {
    val person = detection("person", 0.15f)
    val car = detection("car", 0.5f)
    round(0, person, car)

    val first = engine.process(tracker.update(listOf(person, car), 500), 500)
    assertEquals("Car ahead.", first?.message)
    assertEquals(Priority.CRITICAL, first?.priority)

    // Detection keeps running every 500 ms; the person waits for the 2.5 s gap.
    for (t in 1_000L..2_500L step 500) {
      assertNull("too soon after the previous alert (t=$t)", round(t, person, car))
    }
    assertEquals("Person on the left.", round(3_000, person, car))
  }

  @Test
  fun criticalAlertIsNotHeldBackByTheGap() {
    val person = detection("person", 0.15f)
    round(0, person)
    assertEquals("Person on the left.", round(500, person))

    val bus = detection("bus", 0.5f, width = 0.5f)
    round(1_000, person, bus)
    assertEquals("Bus ahead.", round(1_500, person, bus))
  }

  @Test
  fun reappearingObjectIsNotRepeatedWithinCooldown() {
    val person = detection("person", 0.5f)
    round(0, person)
    assertEquals("Person ahead.", round(500, person))

    // Lost for longer than the track timeout, then found again as a new track.
    round(1_000)
    round(3_000)
    round(3_500, person)
    assertNull(round(4_000, person))

    // After a stream restart everything is announced fresh.
    engine.reset()
    tracker.reset()
    round(20_000, person)
    assertEquals("Person ahead.", round(20_500, person))
  }

}
