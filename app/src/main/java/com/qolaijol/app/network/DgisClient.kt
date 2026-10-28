// DgisClient - 2GIS place search and walking routes
//
// Requests verified in Phase 3/6 (see CLOUD_APIS.md): Places API 3.0 with sort=distance and
// Routing API 7.0.0 with transport=walking. The key is a query parameter, so request URLs are never
// logged or put into exception messages. Only coordinates and the search text are sent.

package com.qolaijol.app.network

import com.qolaijol.app.config.ApiKeys
import com.qolaijol.app.navigation.Geo
import com.qolaijol.app.navigation.LatLon
import com.qolaijol.app.navigation.RoutePart
import com.qolaijol.app.navigation.WalkingRoute
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DgisClient(private val apiKey: () -> String? = { ApiKeys.dgis }) {

  data class Place(val name: String, val address: String?, val point: LatLon, val distanceM: Int)

  enum class Kind {
    NO_KEY,
    NO_NETWORK,
    RATE_LIMITED,
    HTTP,
    BAD_RESPONSE,
  }

  class DgisException(val kind: Kind, message: String, cause: Throwable? = null) :
      Exception(message, cause)

  companion object {
    private const val CATALOG_URL = "https://catalog.api.2gis.com/3.0/items"
    private const val ROUTING_URL = "https://routing.api.2gis.com/routing/7.0.0/global"
    private const val TIMEOUT_MS = 15_000

    /** Parses a catalog response; [from] is used to compute straight-line distances. */
    fun parsePlaces(json: JSONObject, from: LatLon): List<Place> {
      val code = json.optJSONObject("meta")?.optInt("code") ?: 0
      if (code == 404) return emptyList() // 2GIS: nothing found
      if (code != 200) throw DgisException(Kind.BAD_RESPONSE, "Catalog meta code $code")
      val items = json.optJSONObject("result")?.optJSONArray("items") ?: return emptyList()
      return (0 until items.length()).mapNotNull { i ->
        val item = items.getJSONObject(i)
        val point = item.optJSONObject("point") ?: return@mapNotNull null
        val p = LatLon(point.getDouble("lat"), point.getDouble("lon"))
        // name_ex.primary drops the category: "Мир здоровья", not "Мир здоровья, аптека".
        val primary = item.optJSONObject("name_ex")?.optString("primary").orEmpty()
        Place(
            name = primary.ifBlank { item.optString("name") }.ifBlank { return@mapNotNull null },
            address = item.optString("address_name").ifBlank { null },
            point = p,
            distanceM = Geo.distanceM(from, p).toInt(),
        )
      }
    }

    /** Parses a routing response into a WalkingRoute (geometry of every maneuver's path). */
    fun parseRoute(json: JSONObject, destinationName: String): WalkingRoute {
      val result = json.optJSONArray("result")
      if (result == null || result.length() == 0) {
        throw DgisException(Kind.BAD_RESPONSE, "No route in response (status ${json.optString("status")})")
      }
      val route = result.getJSONObject(0)
      val parts = ArrayList<RoutePart>()
      val maneuvers = route.optJSONArray("maneuvers") ?: JSONArray()
      for (i in 0 until maneuvers.length()) {
        val geometry = maneuvers.getJSONObject(i).optJSONObject("outcoming_path")?.optJSONArray("geometry") ?: continue
        for (g in 0 until geometry.length()) {
          val part = geometry.getJSONObject(g)
          val points = parseWkt(part.optString("selection"))
          if (points.size >= 2) {
            parts += RoutePart(points, part.optString("style", "normal"), part.optString("zlevel", "zlevel-normal"))
          }
        }
      }
      if (parts.isEmpty()) throw DgisException(Kind.BAD_RESPONSE, "Route has no geometry")
      return WalkingRoute(
          parts = parts,
          destinationName = destinationName,
          totalDistanceM = route.optInt("total_distance"),
          totalDurationS = route.optInt("total_duration"),
      )
    }

    /** "LINESTRING(lon lat, lon lat, ...)" -> points. 2GIS uses lon-lat order. */
    fun parseWkt(wkt: String): List<LatLon> {
      val body = wkt.substringAfter('(', "").substringBeforeLast(')', "")
      if (body.isBlank()) return emptyList()
      return body.split(',').mapNotNull { pair ->
        val xy = pair.trim().split(Regex("\\s+"))
        if (xy.size < 2) null
        else LatLon(lat = xy[1].toDoubleOrNull() ?: return@mapNotNull null, lon = xy[0].toDoubleOrNull() ?: return@mapNotNull null)
      }
    }
  }

  /** Places matching [query], nearest first, within [radiusM] of [near]. */
  suspend fun findNearest(query: String, near: LatLon, radiusM: Int = 3000, limit: Int = 5): List<Place> =
      withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw DgisException(Kind.NO_KEY, "2GIS key is not configured")
        val params =
            listOf(
                    "q" to query,
                    "point" to String.format(Locale.ROOT, "%.6f,%.6f", near.lon, near.lat),
                    "radius" to radiusM.toString(),
                    "sort" to "distance",
                    "page_size" to limit.toString(),
                    "fields" to "items.point,items.name,items.name_ex,items.address_name",
                    "key" to key,
                )
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = (URL("$CATALOG_URL?$params").openConnection() as HttpURLConnection)
        parsePlaces(JSONObject(read(connection, null)), near)
      }

  /** Walking route from [from] to [to]. */
  suspend fun walkingRoute(from: LatLon, to: LatLon, destinationName: String): WalkingRoute =
      withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw DgisException(Kind.NO_KEY, "2GIS key is not configured")
        val body =
            JSONObject()
                .put(
                    "points",
                    JSONArray()
                        .put(JSONObject().put("type", "stop").put("lat", from.lat).put("lon", from.lon))
                        .put(JSONObject().put("type", "stop").put("lat", to.lat).put("lon", to.lon)),
                )
                .put("transport", "walking")
                .put("locale", "ru")
        val connection =
            (URL("$ROUTING_URL?key=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        parseRoute(JSONObject(read(connection, body.toString().toByteArray())), destinationName)
      }

  private fun read(connection: HttpURLConnection, body: ByteArray?): String {
    try {
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      if (body != null) {
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
      }
      val code = connection.responseCode
      if (code == 429) throw DgisException(Kind.RATE_LIMITED, "HTTP 429")
      if (code !in 200..299) throw DgisException(Kind.HTTP, "HTTP $code")
      return connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: DgisException) {
      throw e
    } catch (e: IOException) {
      // Never include e.message: it can contain the request URL with the key.
      throw DgisException(Kind.NO_NETWORK, "Network error: ${e.javaClass.simpleName}")
    } finally {
      connection.disconnect()
    }
  }
}
