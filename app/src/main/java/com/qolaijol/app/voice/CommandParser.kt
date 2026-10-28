// CommandParser - local (offline) parsing of English voice commands
//
// Recognized text -> VoiceCommand, with simple keyword rules and no LLM. Rules are checked in a fixed
// order so that scene questions ("Show me what's ahead", "What's nearby?") are never mistaken for
// place searches — the bug the Python prototype had — and the "stop" in "bus stop" never turns the
// warnings off. Anything not matched returns Unknown, and only then may the caller try the cloud
// LLM. Plain Kotlin, unit tested on the JVM.

package com.qolaijol.app.voice

enum class SceneZone {
  AHEAD,
  LEFT,
  RIGHT,
  ALL,
}

sealed interface VoiceCommand {
  /** "What's ahead / on the left / on the right / what do you see" — answered from YOLO. */
  data class Scene(val zone: SceneZone) : VoiceCommand

  /** "Describe what's around" — detailed cloud description (Phase 5). */
  data object Describe : VoiceCommand

  /** "Find the nearest pharmacy" (Phase 6). */
  data class FindNearest(val place: String) : VoiceCommand

  /** "How do I get to the bus stop" (Phase 6). */
  data class Navigate(val place: String) : VoiceCommand

  /** "Go" / "yes" after a search: route to the place just found. */
  data object NavigateToFound : VoiceCommand

  /** "Stop route", "cancel navigation". */
  data object StopNavigation : VoiceCommand

  /** "How far is it?" */
  data object NavigationStatus : VoiceCommand

  data object StopAssistance : VoiceCommand

  data object StartAssistance : VoiceCommand

  data object Repeat : VoiceCommand

  data object Help : VoiceCommand

  data class Unknown(val text: String) : VoiceCommand
}

object CommandParser {

  private const val ALERT_WORDS = "(?:alerts|hints|warnings|assistance|sound|voice)"

  private val contractions =
      mapOf(
          "what's" to "what is", "where's" to "where is", "who's" to "who is",
          "that's" to "that is", "there's" to "there is", "it's" to "it is", "how's" to "how is",
          "let's" to "let us", "i'm" to "i am", "don't" to "do not", "didn't" to "did not",
          "can't" to "cannot", "what're" to "what are", "you're" to "you are",
      )
  private val contraction = Regex("[a-z]+'[a-z]+")

  private val fillerPrefix =
      Regex(
          "^(?:(?:hey|hi|ok|okay|so|um|uh|well|and|now|please|assistant|all right|alright|" +
              "can you|could you|would you|will you|tell me|just)\\s+)+",
      )
  private val trailingPlease = Regex("(?:\\s+(?:please|thanks|thank you))+$")

  private val stopWords = setOf("stop", "quiet", "silence", "mute", "pause", "enough", "hush", "shush")
  private val stopPhrases =
      listOf(
          Regex("(?:turn off|switch off|disable|stop|mute|pause)(?: the)? $ALERT_WORDS"),
          Regex("(?:alerts|hints|warnings) off"),
          Regex("shut up|stop talking|do not talk|be quiet"),
      )
  // "bus stop" is a place, not the "stop" command.
  private val stopAsPlace = Regex("(?:bus|tram|trolleybus) stop")
  private val startWords = setOf("start", "resume", "continue", "unmute")
  private val startPhrases =
      listOf(
          Regex("(?:turn on|switch on|enable|start|resume)(?: the)? $ALERT_WORDS"),
          Regex("(?:alerts|hints|warnings) on"),
          Regex("keep talking|talk again|speak again"),
      )
  private val describePhrases =
      listOf(
          "describe", "description", "what is around", "what is near me",
          "what is nearby", "who is around", "who is near me", "where am i", "what is happening",
          "what is going on", "look around", "in detail", "details", "more detail",
      )
  private val repeatPhrases =
      listOf(
          "repeat", "say again", "say that again", "again", "what did you say", "come again",
          "pardon", "one more time", "did not catch", "what was that",
      )
  private val helpPhrases =
      listOf("help", "what can you do", "commands", "what commands", "how do i use you", "instructions")

  private val navigateRegex =
      Regex(
          "(?:how (?:do|can|should) i (?:get|go|walk) to|how to (?:get|go|walk) to|" +
              "(?:take|lead|guide|walk|bring|get) me to|navigate(?: me)? to|directions to|" +
              "(?:build |make |plan )?(?:a |the )?route to|show me the way to|" +
              "(?:i )?(?:need|want|would like|have) to (?:go|get|walk) to|go to) (.+)",
      )
  private val findRegex =
      Regex(
          "(?:where can i find|find|search for|search|look for|locate|is there|are there|" +
              "where is|where are)(?: me)? (.+)",
      )
  private val nearestRegex = Regex("^(?:the )?(?:nearest|closest) (.+)")
  private val stopNavigationRegex =
      Regex(
          "(?:stop|cancel|end|finish|quit|exit|abort|clear|turn off)(?: the| my)? " +
              "(?:route|navigation|navigating|directions|guidance)",
      )
  private val statusPhrases =
      listOf(
          "how far", "how much further", "how much farther", "how long", "how much is left",
          "how much left", "distance left", "remaining distance", "are we there yet",
          "where do i turn", "when do i turn", "how many meters",
      )
  // Short "go there" commands, matched as the whole phrase.
  private val goThereRegex =
      Regex(
          "^(?:yes |yeah |sure )?(?:go|let us go|lets go|go there|take me there|lead me there|" +
              "lead the way|lead me|guide me|guide me there|navigate|navigate there|get me there|" +
              "start(?: the)? (?:route|navigation|navigating)|show me the way|" +
              "(?:build|make|plan|create)(?: a| the)? route)(?: there)?$",
      )
  private val goThereWords = setOf("yes", "yeah", "yep", "sure", "route", "directions")
  private val thereWords = setOf("there", "it", "that", "that place", "the place")

  private val placePrefix = Regex("^(?:(?:the|a|an|some|any|nearest|closest|me)\\s+)+")
  private val placeSuffix =
      Regex("(?:\\s+(?:near me|near here|around here|close by|nearby|around|here|please|now))+$")
  private val pronouns = setOf("it", "that", "this", "he", "she", "they", "i", "you", "we")

  fun parse(text: String): VoiceCommand {
    val n = normalize(text)
    if (n.isEmpty()) return VoiceCommand.Unknown(text)

    // Navigation control first: "stop route" must not turn the warnings off.
    if (stopNavigationRegex.containsMatchIn(n) || n == "stop navigating") {
      return VoiceCommand.StopNavigation
    }
    if (statusPhrases.any { contains(n, it) }) return VoiceCommand.NavigationStatus
    if (n in goThereWords || goThereRegex.matches(n)) return VoiceCommand.NavigateToFound

    val commandWords = n.replace(stopAsPlace, " ").split(' ').toSet()
    if (commandWords.any { it in stopWords } || stopPhrases.any { it.containsMatchIn(n) }) {
      return VoiceCommand.StopAssistance
    }
    if (commandWords.any { it in startWords } || startPhrases.any { it.containsMatchIn(n) }) {
      return VoiceCommand.StartAssistance
    }
    if (describePhrases.any { contains(n, it) }) return VoiceCommand.Describe

    // "all right" and "right now" are not directions.
    val scene = n.replace(Regex("all right|right now|right away"), " ")
    val left = contains(scene, "left")
    val right = contains(scene, "right")
    val ahead = listOf("ahead", "in front", "forward", "before me").any { contains(scene, it) }
    when {
      left && right -> return VoiceCommand.Scene(SceneZone.ALL)
      left -> return VoiceCommand.Scene(SceneZone.LEFT)
      right -> return VoiceCommand.Scene(SceneZone.RIGHT)
      ahead -> return VoiceCommand.Scene(SceneZone.AHEAD)
    }
    val all = listOf("what do you see", "what can you see", "what is there", "what is visible")
    if (all.any { contains(n, it) }) return VoiceCommand.Scene(SceneZone.ALL)

    navigateRegex.find(n)?.let { m ->
      val raw = m.groupValues[1]
      if (raw in thereWords) return VoiceCommand.NavigateToFound
      place(raw)?.let { return VoiceCommand.Navigate(it) }
    }
    (findRegex.find(n) ?: nearestRegex.find(n))?.let { m ->
      place(m.groupValues[1])?.let { return VoiceCommand.FindNearest(it) }
    }

    if (repeatPhrases.any { contains(n, it) }) return VoiceCommand.Repeat
    if (helpPhrases.any { contains(n, it) }) return VoiceCommand.Help
    return VoiceCommand.Unknown(text)
  }

  /** Lowercase, contractions expanded, punctuation removed, single spaces, fillers dropped. */
  fun normalize(text: String): String =
      contraction
          .replace(text.lowercase().replace('’', '\'')) {
            contractions[it.value] ?: it.value.replace("'", "")
          }
          .replace(Regex("[^\\p{L}\\p{Nd}\\s-]"), " ")
          .replace(Regex("\\s+"), " ")
          .trim()
          .replace(fillerPrefix, "")
          .replace(trailingPlease, "")
          .trim()

  private fun contains(text: String, phrase: String): Boolean =
      Regex("(^| )" + Regex.escape(phrase) + "($| )").containsMatchIn(text)

  /** "the nearest pharmacy near me" -> "pharmacy"; null if nothing (or only "it") is left. */
  private fun place(raw: String): String? =
      raw.replace(placeSuffix, "")
          .replace(placePrefix, "")
          .trim()
          .takeIf { it.isNotEmpty() && it !in pronouns }
}
