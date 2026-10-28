package com.qolaijol.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

  private fun parse(text: String) = CommandParser.parse(text)

  @Test
  fun sceneQuestions() {
    assertEquals(VoiceCommand.Scene(SceneZone.AHEAD), parse("What's ahead?"))
    assertEquals(VoiceCommand.Scene(SceneZone.AHEAD), parse("What is in front of me"))
    assertEquals(VoiceCommand.Scene(SceneZone.LEFT), parse("And what's on the left?"))
    assertEquals(VoiceCommand.Scene(SceneZone.RIGHT), parse("Tell me what's on my right"))
    assertEquals(VoiceCommand.Scene(SceneZone.ALL), parse("What's on the left and right?"))
    assertEquals(VoiceCommand.Scene(SceneZone.ALL), parse("What do you see?"))
    assertEquals(VoiceCommand.Scene(SceneZone.AHEAD), parse("Is there anything ahead?"))
  }

  @Test
  fun prototypeBugCasesAreNotNavigation() {
    // The Python prototype turned these into 2GIS place searches.
    assertEquals(VoiceCommand.Scene(SceneZone.AHEAD), parse("Show me what's ahead"))
    assertEquals(VoiceCommand.Describe, parse("What's nearby?"))
    assertEquals(VoiceCommand.Describe, parse("Where am I?"))
  }

  @Test
  fun describe() {
    assertEquals(VoiceCommand.Describe, parse("Describe what's around"))
    assertEquals(VoiceCommand.Describe, parse("Describe what is ahead"))
    assertEquals(VoiceCommand.Describe, parse("Can you tell me in detail?"))
    assertEquals(VoiceCommand.Describe, parse("Look around"))
  }

  @Test
  fun placesAndRoutes() {
    assertEquals(VoiceCommand.FindNearest("pharmacy"), parse("Find the nearest pharmacy"))
    assertEquals(VoiceCommand.FindNearest("pharmacy"), parse("Where's the nearest pharmacy?"))
    assertEquals(VoiceCommand.FindNearest("bus stop"), parse("Nearest bus stop"))
    assertEquals(VoiceCommand.FindNearest("atm"), parse("Find me an ATM, please"))
    assertEquals(VoiceCommand.FindNearest("cafe"), parse("Is there a cafe nearby?"))
    assertEquals(VoiceCommand.Navigate("bus stop"), parse("How do I get to the bus stop?"))
    assertEquals(VoiceCommand.Navigate("clinic"), parse("Take me to the clinic"))
    assertEquals(VoiceCommand.Navigate("university"), parse("Route to the university"))
    assertEquals(VoiceCommand.Navigate("clinic"), parse("I need to get to the clinic"))
    assertEquals(VoiceCommand.Navigate("train station"), parse("I want to go to the train station"))
    assertEquals(VoiceCommand.Navigate("pharmacy"), parse("Take me to the nearest pharmacy"))
  }

  @Test
  fun navigationControl() {
    assertEquals(VoiceCommand.StopNavigation, parse("Stop the route"))
    assertEquals(VoiceCommand.StopNavigation, parse("Cancel navigation"))
    assertEquals(VoiceCommand.StopNavigation, parse("End route"))
    assertEquals(VoiceCommand.NavigationStatus, parse("How far is it?"))
    assertEquals(VoiceCommand.NavigationStatus, parse("How much further?"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Go"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Yes"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Start route"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Take me there"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Okay, let's go."))
    assertEquals(VoiceCommand.NavigateToFound, parse("Yes, go."))
    assertEquals(VoiceCommand.NavigateToFound, parse("Build a route"))
    assertEquals(VoiceCommand.NavigateToFound, parse("Take me to it"))
    // Plain "stop" still means the warnings.
    assertEquals(VoiceCommand.StopAssistance, parse("Stop"))
  }

  @Test
  fun stopStartRepeatHelp() {
    assertEquals(VoiceCommand.StopAssistance, parse("Stop"))
    assertEquals(VoiceCommand.StopAssistance, parse("Be quiet for a bit"))
    assertEquals(VoiceCommand.StopAssistance, parse("Turn off the alerts"))
    assertEquals(VoiceCommand.StartAssistance, parse("Turn on alerts"))
    assertEquals(VoiceCommand.StartAssistance, parse("Start"))
    assertEquals(VoiceCommand.Repeat, parse("What did you say?"))
    assertEquals(VoiceCommand.Repeat, parse("Repeat"))
    assertEquals(VoiceCommand.Repeat, parse("Say that again"))
    assertEquals(VoiceCommand.Help, parse("What can you do?"))
    assertEquals(VoiceCommand.Help, parse("Help"))
  }

  @Test
  fun busStopIsAPlaceNotTheStopCommand() {
    assertEquals(VoiceCommand.FindNearest("bus stop"), parse("Find the nearest bus stop"))
    assertEquals(VoiceCommand.Navigate("bus stop"), parse("Take me to the bus stop"))
    assertEquals(VoiceCommand.FindNearest("bus stop"), parse("Where is the bus stop?"))
    // "Help me find a pharmacy" is a search, not a help request.
    assertEquals(VoiceCommand.FindNearest("pharmacy"), parse("Help me find a pharmacy"))
  }

  @Test
  fun unknownGoesToTheFallback() {
    assertTrue(parse("What's the weather today?") is VoiceCommand.Unknown)
    assertTrue(parse("Where can I buy bread?") is VoiceCommand.Unknown)
    assertTrue(parse("") is VoiceCommand.Unknown)
  }
}
