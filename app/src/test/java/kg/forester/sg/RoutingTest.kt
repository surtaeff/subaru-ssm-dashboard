package kg.forester.sg

import kg.forester.sg.service.Place
import kg.forester.sg.service.RouteStep
import kg.forester.sg.service.Routing
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

/** The cluster's own route: OSRM's answer, the polyline, the words of the manoeuvres. */
class RoutingTest {
    /** The polyline format with 6 decimals, the other way round, to make test geometry. */
    private fun encode(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1F).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        points.forEach { (la, lo) ->
            val a = (la * 1e6).roundToLong(); val b = (lo * 1e6).roundToLong()
            put(a - pLat); put(b - pLon); pLat = a; pLon = b
        }
        return sb.toString()
    }

    private val pts = listOf(42.8746 to 74.5874, 42.8760 to 74.5874, 42.8760 to 74.5900, 42.8790 to 74.5900)

    @Test fun polylineRoundTrip() {
        val back = Routing.decodePolyline6(encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> assertEquals(a.first, b.first, 1e-6); assertEquals(a.second, b.second, 1e-6) }
    }

    @Test fun parsesOsrm() {
        val json = """
            {"code":"Ok","routes":[{"duration":180.5,"geometry":"${encode(pts)}","legs":[{"steps":[
              {"name":"Киевская","maneuver":{"type":"depart","modifier":"","location":[74.5874,42.8746]}},
              {"name":"Абдрахманова","maneuver":{"type":"turn","modifier":"right","location":[74.5874,42.8760]}},
              {"name":"Токтогула","maneuver":{"type":"turn","modifier":"left","location":[74.5900,42.8760]}},
              {"name":"","maneuver":{"type":"arrive","modifier":"","location":[74.5900,42.8790]}}
            ]}]}]}
        """.trimIndent()
        val r = Routing.parse(json, Place("Дом", "", 42.8790, 74.5900))!!
        assertEquals(4, r.lat.size)
        assertEquals(listOf(0, 1, 2, 3), r.steps.map { it.at })
        assertEquals("right", r.steps[1].modifier)
        assertTrue(r.lengthM in 600.0..720.0) // 156 m + 213 m + 334 m
        assertNull(Routing.parse("""{"code":"NoRoute"}""", r.dest))
    }

    @Test fun manoeuvreWords() {
        assertEquals("Поверните направо", Routing.say(RouteStep(0, "turn", "right", 0, "")))
        assertEquals("Держитесь левее", Routing.say(RouteStep(0, "fork", "slight left", 0, "")))
        assertEquals("Круговое движение, 2-й съезд", Routing.say(RouteStep(0, "roundabout", "right", 2, "")))
        assertEquals("Разворот", Routing.say(RouteStep(0, "continue", "uturn", 0, "")))
        assertEquals("right", Routing.kind(RouteStep(0, "turn", "right", 0, "")))
        assertEquals(kg.forester.sg.service.Turn.FINISH, Routing.kind(RouteStep(0, "arrive", "", 0, "")))
        assertEquals(kg.forester.sg.service.Turn.ROUND, Routing.kind(RouteStep(0, "roundabout", "right", 2, "")))
        assertEquals("350 м", Routing.distanceText(354.0))
        assertEquals("1,2 км", Routing.distanceText(1240.0))
        assertEquals("12 км", Routing.distanceText(12_400.0))
    }

    @Test fun placeKeepsItsFields() {
        val p = Place("Ала-Арча", "Бишкек, Кыргызстан", 42.85, 74.6)
        assertEquals(p, Place.parse(p.format()))
        assertNull(Place.parse("мусор"))
    }
}
