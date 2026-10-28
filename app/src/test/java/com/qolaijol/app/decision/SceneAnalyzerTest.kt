package com.qolaijol.app.decision

import com.qolaijol.app.detection
import com.qolaijol.app.voice.SceneZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneAnalyzerTest {

  @Test
  fun countsPerZoneMostImportantFirst() {
    val scene =
        listOf(
            detection("person", 0.45f),
            detection("person", 0.55f),
            detection("bus", 0.5f),
            detection("person", 0.1f),
            detection("chair", 0.9f),
        )
    assertEquals("Ahead: bus, 2 people.", SceneAnalyzer.answer(SceneZone.AHEAD, scene))
    assertEquals("On the left: person.", SceneAnalyzer.answer(SceneZone.LEFT, scene))
    assertEquals("On the right: chair.", SceneAnalyzer.answer(SceneZone.RIGHT, scene))
    assertEquals(
        "Ahead: bus, 2 people. On the left: person. On the right: chair.",
        SceneAnalyzer.answer(SceneZone.ALL, scene),
    )
  }

  @Test
  fun emptyZoneIsNotCalledSafe() {
    val scene = listOf(detection("car", 0.1f))
    assertEquals("Nothing detected ahead.", SceneAnalyzer.answer(SceneZone.AHEAD, scene))
    assertEquals("Nothing detected on the right.", SceneAnalyzer.answer(SceneZone.RIGHT, scene))
    assertEquals("Nothing detected.", SceneAnalyzer.answer(SceneZone.ALL, emptyList()))
  }

  @Test
  fun lowConfidenceAndUnreliableClassesAreLeftOut() {
    val scene =
        listOf(
            detection("pedestrian", 0.5f, confidence = 0.3f),
            detection("stairs", 0.5f, confidence = 0.9f),
            detection("crosswalk", 0.5f, confidence = 0.9f),
        )
    assertEquals("Nothing detected ahead.", SceneAnalyzer.answer(SceneZone.AHEAD, scene))
  }
}
