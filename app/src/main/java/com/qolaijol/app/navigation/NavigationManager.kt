// NavigationManager - "find a pharmacy", "take me to…", "go", "stop route", "how far is it"
//
// Finds places and walking routes with 2GIS, follows the user's GPS position with RouteGuide and
// speaks its instructions. 2GIS here only understands Russian, so the spoken English place word is
// translated first (PlaceQuery, else the LLM), and the Cyrillic names it returns are read out in
// Latin letters (Translit). Instructions that come up while the user is giving a voice command are
// held and spoken right after. Leaving the route triggers a new route (at most every 30 s, 2GIS
// limits requests). Exact coordinates are never logged.

package com.qolaijol.app.navigation

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.qolaijol.app.audio.TtsManager
import com.qolaijol.app.network.DgisClient
import com.qolaijol.app.network.GroqClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NavigationManager(
    context: Context,
    private val scope: CoroutineScope,
    private val tts: TtsManager,
    /** True while speech must wait (the user is giving a voice command). */
    private val isQuiet: () -> Boolean,
    private val dgis: DgisClient = DgisClient(),
    private val groq: GroqClient = GroqClient(),
) {

  companion object {
    private const val TAG = "QolAIJol/Navigation"
    private const val REROUTE_INTERVAL_MS = 30_000L
  }

  data class State(
      val active: Boolean = false,
      val destination: String? = null,
      val remainingM: Int? = null,
      val lastInstruction: String? = null,
  )

  private val location = LocationTracker(context)
  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  private var guide: RouteGuide? = null
  private var destination: DgisClient.Place? = null
  private var lastFound: DgisClient.Place? = null
  private var lastRerouteMs = 0L
  private var rerouting = false
  private val pending = ArrayList<RouteGuide.Event.Say>()

  fun hasPermission(): Boolean = location.hasPermission()

  /** "Find a pharmacy": nearest match, remembered for a following "go". */
  suspend fun find(query: String): String {
    val here = here() ?: return locationProblem()
    val place =
        try {
          dgis.findNearest(searchText(query), here).firstOrNull()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return errorPhrase(e)
        } ?: return "No $query found nearby."
    lastFound = place
    Log.i(TAG, "Found \"${place.name}\" ${place.distanceM} m away")
    val address = place.address?.let { ", ${Translit.address(it)}" }.orEmpty()
    return "Nearest $query: ${spokenName(place)}$address, about " +
        "${NavPhrases.distance(place.distanceM.toDouble())} away in a straight line. " +
        "To go there, say: start route."
  }

  /** Builds a walking route to the nearest [query], or to the last found place if [query] is null. */
  suspend fun navigate(query: String?): String {
    val here = here() ?: return locationProblem()
    val place =
        if (query != null) {
          try {
            dgis.findNearest(searchText(query), here).firstOrNull()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            return errorPhrase(e)
          } ?: return "No $query found nearby."
        } else {
          lastFound ?: return "First tell me where to go. For example: take me to a pharmacy."
        }
    val route =
        try {
          dgis.walkingRoute(here, place.point, spokenName(place))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          return errorPhrase(e)
        }
    Log.i(
        TAG,
        "Route to \"${place.name}\": ${route.lengthM.toInt()} m, ${route.features.size} turns/passages",
    )
    lastFound = place
    destination = place
    val newGuide = RouteGuide(route)
    guide = newGuide
    pending.clear()
    location.start(::onLocation)
    val intro = newGuide.start().text
    _state.value = State(true, spokenName(place), route.lengthM.toInt(), intro)
    return intro
  }

  fun stop(): String {
    if (guide == null) return "There is no route."
    finish()
    return "Route ended."
  }

  fun status(): String =
      guide?.status() ?: "There is no route. Say, for example: take me to a pharmacy."

  fun release() {
    location.stop()
  }

  private fun onLocation(fix: Location) {
    val current = guide ?: return
    val position = LatLon(fix.latitude, fix.longitude)
    for (event in current.onLocation(position, fix.accuracy)) {
      when (event) {
        is RouteGuide.Event.Say -> say(event)
        RouteGuide.Event.Reroute -> reroute(position)
        RouteGuide.Event.Arrived -> {
          flush()
          finish()
          return
        }
      }
    }
    flush()
    _state.value = _state.value.copy(remainingM = current.remainingM.toInt())
  }

  private fun reroute(from: LatLon) {
    val place = destination ?: return
    val now = SystemClock.elapsedRealtime()
    if (rerouting || now - lastRerouteMs < REROUTE_INTERVAL_MS) return
    rerouting = true
    lastRerouteMs = now
    scope.launch {
      try {
        val route = dgis.walkingRoute(from, place.point, spokenName(place))
        if (destination == place) {
          guide = RouteGuide(route).also { say(it.start()) }
          Log.i(TAG, "Rerouted: ${route.lengthM.toInt()} m")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        say(RouteGuide.Event.Say(errorPhrase(e)))
      } finally {
        rerouting = false
      }
    }
  }

  private fun say(event: RouteGuide.Event.Say) {
    if (isQuiet()) pending += event else speak(event)
  }

  private fun flush() {
    if (isQuiet() || pending.isEmpty()) return
    pending.forEach(::speak)
    pending.clear()
  }

  private fun speak(event: RouteGuide.Event.Say) {
    Log.i(TAG, "Instruction: ${event.text}")
    tts.speak(event.text, interrupt = event.urgent)
    _state.value = _state.value.copy(lastInstruction = event.text)
  }

  private fun finish() {
    location.stop()
    guide = null
    destination = null
    _state.value = State()
  }

  /** What to search 2GIS for: the dictionary word, else the LLM's translation, else as said. */
  private suspend fun searchText(query: String): String {
    PlaceQuery.translate(query)?.let { return it }
    return try {
      groq
          .chatJson(
              "Translate the place a user is looking for into a short Russian search query for " +
                  "the 2GIS map catalog in Kazakhstan (1-3 words, nominative case). Keep brand " +
                  "and proper names as they are. Return only JSON {\"query\": \"<Russian text>\"}. " +
                  "Place: \"${query.replace("\"", "").take(100)}\"",
          )
          .optString("query")
          .ifBlank { query }
          .also { Log.i(TAG, "Search text for \"$query\": \"$it\"") }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not translate the place query: ${e.message}")
      query
    }
  }

  private fun spokenName(place: DgisClient.Place): String = Translit.toLatin(place.name)

  private suspend fun here(): LatLon? =
      location.currentLocation()?.let { LatLon(it.latitude, it.longitude) }

  private fun locationProblem(): String =
      when {
        !location.hasPermission() ->
            "I need precise location access. Please allow it in the app settings."
        !location.isEnabled() -> "Location is turned off. Turn it on in the phone settings."
        else -> "Couldn't determine your location. Try again in an open area."
      }

  private fun errorPhrase(e: Exception): String =
      when ((e as? DgisClient.DgisException)?.kind) {
        DgisClient.Kind.NO_KEY -> "Navigation is unavailable: the 2GIS key isn't configured."
        DgisClient.Kind.NO_NETWORK ->
            "No internet. Navigation is unavailable, but obstacle alerts still work."
        DgisClient.Kind.RATE_LIMITED -> "The map service is busy. Wait a minute and try again."
        else -> {
          Log.w(TAG, "2GIS request failed: ${e.javaClass.simpleName}")
          "The map service didn't respond. Please try again."
        }
      }
}
