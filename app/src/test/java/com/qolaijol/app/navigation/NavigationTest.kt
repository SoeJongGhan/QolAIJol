package com.qolaijol.app.navigation

import com.qolaijol.app.network.DgisClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

  private val start = LatLon(50.2800, 57.2000)

  /** Straight north [firstM], then [secondM] towards [secondBearing]. */
  private fun lRoute(firstM: Double = 100.0, secondM: Double = 80.0, secondBearing: Double = 90.0): WalkingRoute {
    val corner = Geo.offset(start, 0.0, firstM)
    val end = Geo.offset(corner, secondBearing, secondM)
    val points = (0..10).map { Geo.offset(start, 0.0, firstM * it / 10) } +
        (1..8).map { Geo.offset(corner, secondBearing, secondM * it / 8) }
    return WalkingRoute(listOf(RoutePart(points, "normal", "zlevel-normal")), "Pharmacy", 180, 150)
        .also { assertEquals(end.lat, it.points.last().lat, 1e-6) }
  }

  /** Walks [route] in [stepM] steps and collects everything the guide says. */
  private fun walk(route: WalkingRoute, stepM: Double = 5.0): Pair<List<String>, Boolean> {
    val guide = RouteGuide(route)
    val said = mutableListOf(guide.start().text)
    var arrived = false
    var s = 0.0
    while (s <= route.lengthM && !arrived) {
      for (e in guide.onLocation(route.pointAt(s))) {
        when (e) {
          is RouteGuide.Event.Say -> said += e.text
          RouteGuide.Event.Arrived -> arrived = true
          RouteGuide.Event.Reroute -> error("unexpected reroute at $s")
        }
      }
      s += stepM
    }
    return said to arrived
  }

  @Test
  fun geometryBasics() {
    assertEquals(111_195.0, Geo.distanceM(LatLon(50.0, 57.0), LatLon(51.0, 57.0)), 50.0)
    assertEquals(90.0, Geo.bearingDeg(start, Geo.offset(start, 90.0, 100.0)), 0.5)
    assertEquals(20.0, Geo.turnDeg(350.0, 10.0), 1e-9)
    assertEquals(-20.0, Geo.turnDeg(10.0, 350.0), 1e-9)
    assertEquals(100.0, Geo.distanceM(start, Geo.offset(start, 33.0, 100.0)), 0.1)
  }

  @Test
  fun turnsAreComputedFromGeometry() {
    val right = lRoute().features.single() as RouteFeature.Turn
    assertEquals(100.0, right.atM, 3.0)
    assertEquals(90.0, right.angleDeg, 5.0)

    val left = lRoute(secondBearing = 270.0).features.single() as RouteFeature.Turn
    assertEquals(-90.0, left.angleDeg, 5.0)

    // A gentle 20° bend is "keep going straight".
    assertTrue(lRoute(secondBearing = 20.0).features.isEmpty())
  }

  @Test
  fun walkingTheRouteGivesInstructionsInOrder() {
    val (said, arrived) = walk(lRoute())
    assertTrue(arrived)
    assertEquals(
        listOf(
            "Route to Pharmacy: 200 meters, about 3 minutes. Go straight for 100 meters, then turn right.",
            "In 40 meters, turn right.",
            "Turn right.",
            "You have arrived at Pharmacy.",
        ),
        said,
    )
  }

  @Test
  fun leavingTheRouteAsksForANewOneAfterTwoFixes() {
    val route = lRoute()
    val guide = RouteGuide(route)
    guide.onLocation(route.pointAt(30.0))
    val off = Geo.offset(route.pointAt(40.0), 270.0, 50.0) // 50 m to the west of the first leg
    assertTrue(guide.onLocation(off).isEmpty()) // one bad fix may be GPS noise
    val events = guide.onLocation(off)
    assertTrue(events.contains(RouteGuide.Event.Reroute))
    assertTrue((events.first() as RouteGuide.Event.Say).text.startsWith("You are off the route"))
  }

  @Test
  fun walkingBackwardsIsNoticed() {
    val route = lRoute()
    val guide = RouteGuide(route)
    listOf(10.0, 30.0, 50.0).forEach { guide.onLocation(route.pointAt(it)) }
    val events = guide.onLocation(route.pointAt(25.0))
    assertEquals(
        RouteGuide.Event.Say("You seem to be going the wrong way. Turn around.", urgent = true),
        events.single(),
    )
  }

  @Test
  fun realTwoGisWalkingRouteWithStairsAndOverpass() {
    val json = javaClass.classLoader!!.getResource("dgis_walking_route.json")!!.readText()
    val route = DgisClient.parseRoute(JSONObject(json), "Clinic")
    assertEquals(2525.0, route.lengthM, 2525 * 0.03)

    val passages = route.features.filterIsInstance<RouteFeature.Passage>()
    assertEquals(
        listOf(
            RouteFeature.Passage.Kind.STAIRS to true,
            RouteFeature.Passage.Kind.OVERPASS to null,
            RouteFeature.Passage.Kind.STAIRS to false,
        ),
        passages.map { it.kind to it.up },
    )
    assertEquals(1000.0, passages.first().atM, 30.0)
    assertTrue("route has turns", route.features.any { it is RouteFeature.Turn })

    // The whole route can be walked from start to finish, stairs announced on the way.
    val (said, arrived) = walk(route, stepM = 8.0)
    println("Real route, ${route.features.size} features, spoken:\n" + said.joinToString("\n"))
    assertTrue(arrived)
    assertTrue(said.any { it == "Stairs going up. Careful." })
    assertTrue(said.any { it == "Pedestrian overpass. Careful." })
    assertTrue(said.any { it == "Stairs going down. Careful." })
  }

  @Test
  fun wktAndPlacesParsing() {
    assertEquals(
        listOf(LatLon(50.1, 57.2), LatLon(50.3, 57.4)),
        DgisClient.parseWkt("LINESTRING(57.2 50.1, 57.4 50.3)"),
    )
    val found =
        JSONObject(
            """{"meta":{"code":200},"result":{"items":[
              {"name":"Мир здоровья, аптека","name_ex":{"primary":"Мир здоровья","extension":"аптека"},
               "address_name":"улица Абая, 1","point":{"lat":50.2805,"lon":57.2}},
              {"name":"Аптека","address_name":"ул. Абая, 3","point":{"lat":50.2806,"lon":57.2}}]}}""",
        )
    val (place, plain) = DgisClient.parsePlaces(found, start)
    assertEquals("Мир здоровья", place.name) // name_ex.primary, without the category
    assertEquals("улица Абая, 1", place.address)
    assertEquals("Аптека", plain.name)
    assertEquals(56.0, place.distanceM.toDouble(), 2.0)
    assertTrue(DgisClient.parsePlaces(JSONObject("""{"meta":{"code":404}}"""), start).isEmpty())
  }

  @Test
  fun englishDistancesAndTimes() {
    assertEquals("10 meters", NavPhrases.distance(7.0))
    assertEquals("40 meters", NavPhrases.distance(44.0))
    assertEquals("200 meters", NavPhrases.distance(180.0))
    assertEquals("1 kilometer", NavPhrases.distance(990.0))
    assertEquals("1.2 kilometers", NavPhrases.distance(1234.0))
    assertEquals("2 kilometers", NavPhrases.distance(2000.0))
    assertEquals("5 kilometers", NavPhrases.distance(5000.0))
    assertEquals("1 minute", NavPhrases.minutes(30))
    assertEquals("2 minutes", NavPhrases.minutes(61))
    assertEquals("7 minutes", NavPhrases.minutes(420))
  }

  @Test
  fun englishPlaceWordsBecomeRussianSearchText() {
    assertEquals("аптека", PlaceQuery.translate("pharmacy"))
    assertEquals("аптека", PlaceQuery.translate("the nearest pharmacies"))
    assertEquals("остановка", PlaceQuery.translate("bus stop"))
    assertEquals("банкомат", PlaceQuery.translate("ATM"))
    assertEquals("торговый центр", PlaceQuery.translate("shopping malls"))
    assertEquals(null, PlaceQuery.translate("Keruen City"))
  }

  @Test
  fun cyrillicNamesAreSpokenInLatinLetters() {
    assertEquals("Mir zdorovya", Translit.toLatin("Мир здоровья"))
    assertEquals("ARS", Translit.toLatin("АРС"))
    assertEquals("Shchuka", Translit.toLatin("Щука"))
    assertEquals("Qazaq Energy", Translit.toLatin("Qazaq Energy"))
    assertEquals("Aqtobe", Translit.toLatin("Ақтөбе"))
    assertEquals("Az Nauryz Street, 32", Translit.address("улица Аз Наурыз, 32"))
    assertEquals("Abaya Avenue, 1", Translit.address("проспект Абая, 1"))
    assertEquals("12 microdistrict, 21l", Translit.address("12-й микрорайон, 21л"))
  }
}
