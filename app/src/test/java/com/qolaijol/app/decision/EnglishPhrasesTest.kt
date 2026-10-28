package com.qolaijol.app.decision

import com.qolaijol.app.vision.HorizontalPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnglishPhrasesTest {

  @Test
  fun alertSentences() {
    assertEquals("Person ahead.", EnglishPhrases.alert("person", HorizontalPosition.CENTER))
    assertEquals("Person ahead.", EnglishPhrases.alert("pedestrian", HorizontalPosition.CENTER))
    assertEquals("Car on the left.", EnglishPhrases.alert("car", HorizontalPosition.LEFT))
    assertEquals("Pole on the right.", EnglishPhrases.alert("pole", HorizontalPosition.RIGHT))
    assertEquals("Stop sign ahead.", EnglishPhrases.alert("stop sign", HorizontalPosition.CENTER))
    assertEquals("Cyclist on the left.", EnglishPhrases.alert("rider", HorizontalPosition.LEFT))
  }

  @Test
  fun everyClassOfBothModelsHasASpeakablePlural() {
    // Labels of yolo26n.pt (COCO) and the fine-tuned QolAIJol model, as exported to the app.
    val coco =
        listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )
    val fineTuned =
        listOf(
            "pedestrian", "rider", "vehicle", "animal", "obstacle", "pole", "stairs", "crosswalk",
            "traffic light",
        )
    assertEquals(80, coco.size)
    for (name in coco + fineTuned) {
      val singular = EnglishPhrases.objectName(name)
      val plural = EnglishPhrases.plural(singular)
      assertFalse("blank name for $name", singular.isBlank())
      // Names that are already plural ("skis", "stairs") must not become "skises".
      if (singular.endsWith("s") && !singular.endsWith("ss") && !singular.endsWith("us")) {
        assertEquals("plural of $singular", singular, plural)
      }
    }
  }

  @Test
  fun plurals() {
    assertEquals("person", EnglishPhrases.countPhrase("pedestrian", 1))
    assertEquals("2 people", EnglishPhrases.countPhrase("pedestrian", 2))
    assertEquals("5 people", EnglishPhrases.countPhrase("person", 5))
    assertEquals("21 vehicles", EnglishPhrases.countPhrase("vehicle", 21))
    assertEquals("3 buses", EnglishPhrases.countPhrase("bus", 3))
    assertEquals("2 benches", EnglishPhrases.countPhrase("bench", 2))
    assertEquals("2 knives", EnglishPhrases.countPhrase("knife", 2))
    assertEquals("4 sheep", EnglishPhrases.countPhrase("sheep", 4))
    assertEquals("2 TVs", EnglishPhrases.countPhrase("tv", 2))
    assertEquals("2 cyclists", EnglishPhrases.countPhrase("rider", 2))
    assertEquals("3 cups", EnglishPhrases.countPhrase("cup", 3))
  }

  @Test
  fun unknownClassFallsBackToModelName() {
    assertEquals("mystery", EnglishPhrases.objectName("mystery"))
  }
}
