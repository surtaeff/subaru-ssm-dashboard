package kg.forester.sg.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The kinds of manoeuvre the cluster draws an arrow for (and "left", "right", "slight_left"… as they read). */
object Turn {
    const val STRAIGHT = "straight"
    const val ROUND = "round"
    const val FINISH = "finish"
    const val UTURN_LEFT = "uturn_left"
    const val UTURN_RIGHT = "uturn_right"
}

/** A place to drive to: a search result or one of the recent ones. */
data class Place(val name: String, val address: String, val lat: Double, val lon: Double) {
    fun format() = listOf(name, address, lat, lon).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }

    companion object {
        fun parse(s: String): Place? {
            val f = s.split('\t')
            if (f.size != 4) return null
            return Place(f[0], f[1], f[2].toDoubleOrNull() ?: return null, f[3].toDoubleOrNull() ?: return null)
        }
    }
}

/** One manoeuvre of the route: where it is ([at], an index into the route's points) and what to do there. */
data class RouteStep(val at: Int, val type: String, val modifier: String, val exit: Int, val road: String)

/** A built route: its points, the distance along it to each point, the manoeuvres, the totals. */
class Route(
    val dest: Place,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val steps: List<RouteStep>,
    val durationS: Double,
) {
    /** Metres from the start to each point. */
    val along: DoubleArray = DoubleArray(lat.size).also { a ->
        for (i in 1 until lat.size) a[i] = a[i - 1] + Routing.metres(lat[i - 1], lon[i - 1], lat[i], lon[i])
    }
    val lengthM get() = along.lastOrNull() ?: 0.0
}

/** Where the car is on the route now. */
data class RouteProgress(
    /** The first route point still ahead of the car. */
    val ahead: Int,
    val leftM: Double,
    val leftS: Double,
    /** The next manoeuvre and the metres to it; null near the end. */
    val next: RouteStep?,
    val toNextM: Double,
)

/**
 * The cluster's own route: the destination is picked in the app, the route is asked from the OSRM server of
 * OpenStreetMap (router.project-osrm.org) and followed by GPS; off the line it is built again. The navigators
 * on the unit keep their routes to themselves, so this one is what the «Приборка с картой» card draws, and its
 * manoeuvres are the turn hints when the navigator does not print them in a notification (Yandex). Places are
 * found by Nominatim, the same OpenStreetMap data.
 */
object Routing {
    val route = MutableStateFlow<Route?>(null)
    val progress = MutableStateFlow<RouteProgress?>(null)
    /** "" while all is well, else why there is no route ("нет интернета"…). */
    val status = MutableStateFlow("")

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastBuildMs = 0L
    private var offCount = 0
    private var agent = "CarPulse"

    fun init(ctx: android.content.Context) {
        agent = ctx.packageName // the OpenStreetMap servers want to know who asks
    }

    /** Builds a route from ([fromLat], [fromLon]) to [dest]; replaces the current one when it comes. */
    fun build(fromLat: Double, fromLon: Double, dest: Place) {
        lastBuildMs = System.currentTimeMillis()
        offCount = 0
        status.value = "строю маршрут…"
        io.launch {
            runCatching { fetch(fromLat, fromLon, dest) }
                .onSuccess { r ->
                    route.value = r
                    progress.value = null
                    status.value = if (r == null) "маршрут не найден" else ""
                }
                .onFailure { status.value = "нет связи с сервером маршрутов" }
        }
    }

    fun clear() {
        route.value = null
        progress.value = null
        status.value = ""
        kg.forester.sg.data.Repo.settings.navDest = ""
    }

    /** A new GPS fix: where the car is on the route, a new route when it has left it, the end when it is there. */
    fun onFix(lat: Double, lon: Double) {
        val r = route.value ?: return
        if (r.lat.size < 2) return
        val from = progress.value?.ahead?.minus(1)?.coerceAtLeast(0) ?: 0
        // the nearest segment, looked for a little behind and well ahead of where the car was (a route may pass a place twice)
        var best = Double.MAX_VALUE
        var bestI = from
        var bestT = 0.0
        val end = if (progress.value == null) r.lat.size - 1 else minOf(r.lat.size - 1, from + 400)
        for (i in maxOf(0, from - 5) until end) {
            val (d, t) = toSegment(lat, lon, r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1])
            if (d < best) { best = d; bestI = i; bestT = t }
        }
        val at = r.along[bestI] + (r.along[bestI + 1] - r.along[bestI]) * bestT
        val left = (r.lengthM - at).coerceAtLeast(0.0)
        if (metres(lat, lon, r.dest.lat, r.dest.lon) < 40.0 || left < 25.0) {
            status.value = "вы приехали"
            route.value = null
            progress.value = null
            kg.forester.sg.data.Repo.settings.navDest = ""
            return
        }
        // off the line for a few fixes in a row: build it again from here, at most every 15 s
        if (best > 60.0) offCount++ else offCount = 0
        if (offCount >= 3 && System.currentTimeMillis() - lastBuildMs > 15_000) {
            build(lat, lon, r.dest)
            return
        }
        val next = r.steps.firstOrNull { it.at > bestI && it.type !in QuietTypes && !(it.type == "continue" && it.modifier == "straight") }
        progress.value = RouteProgress(
            bestI + 1, left, r.durationS * left / r.lengthM.coerceAtLeast(1.0), next,
            next?.let { (r.along[it.at] - at).coerceAtLeast(0.0) } ?: left,
        )
    }

    private val QuietTypes = setOf("depart", "new name", "notification")

    /** Places matching [query], the nearest first when the car's position is known. */
    fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val base = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&limit=10&accept-language=ru&addressdetails=0"
        // around the car first (some 50 km), the whole map only when nothing is there
        val near = if (nearLat != null && nearLon != null) "&viewbox=${nearLon - 0.6},${nearLat + 0.4},${nearLon + 0.6},${nearLat - 0.4}" else ""
        var arr = org.json.JSONArray(get(base + near + if (near.isEmpty()) "" else "&bounded=1"))
        if (arr.length() == 0 && near.isNotEmpty()) arr = org.json.JSONArray(get(base + near))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifEmpty { full.substringBefore(',') }
            out += Place(name, full.removePrefix(name).trimStart(',', ' '), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
        return if (nearLat == null || nearLon == null) out else out.sortedBy { metres(nearLat, nearLon, it.lat, it.lon) }
    }

    private fun fetch(fromLat: Double, fromLon: Double, dest: Place): Route? {
        val url = "https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;${dest.lon},${dest.lat}" +
            "?overview=full&geometries=polyline6&steps=true"
        return parse(get(url), dest)
    }

    /** The OSRM answer as a [Route] (the first one it offers). */
    fun parse(json: String, dest: Place): Route? {
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") return null
        val r = o.getJSONArray("routes").optJSONObject(0) ?: return null
        val pts = decodePolyline6(r.getString("geometry"))
        if (pts.size < 2) return null
        val lat = DoubleArray(pts.size) { pts[it].first }
        val lon = DoubleArray(pts.size) { pts[it].second }
        val steps = ArrayList<RouteStep>()
        val legs = r.getJSONArray("legs")
        var from = 0
        for (l in 0 until legs.length()) {
            val ss = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until ss.length()) {
                val st = ss.getJSONObject(s)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val mLon = loc.getDouble(0); val mLat = loc.getDouble(1)
                // the route point at the manoeuvre: the nearest one from where the previous one was
                var bi = from
                var bd = Double.MAX_VALUE
                for (i in from until pts.size) {
                    val d = metres(mLat, mLon, lat[i], lon[i])
                    if (d < bd) { bd = d; bi = i }
                    if (bd < 1.0) break
                }
                from = bi
                steps += RouteStep(bi, m.optString("type"), m.optString("modifier"), m.optInt("exit", 0), st.optString("name"))
            }
        }
        return Route(dest, lat, lon, steps, r.optDouble("duration", 0.0))
    }

    /** The Google polyline format with 6 decimals, as OSRM sends it. */
    fun decodePolyline6(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0; var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += lat / 1e6 to lon / 1e6
        }
        return out
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", agent)
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Metres from the point to the segment and where along it the nearest place is (0…1); flat over a few hundred metres. */
    private fun toSegment(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val k = cos(Math.toRadians(lat)) * 111_320.0
        val ax = (aLon - lon) * k; val ay = (aLat - lat) * 110_540.0
        val bx = (bLon - lon) * k; val by = (bLat - lat) * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-6) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + dx * t; val py = ay + dy * t
        return sqrt(px * px + py * py) to t
    }

    /** What to do at [s], in the navigators' words. */
    fun say(s: RouteStep): String {
        val side = when (s.modifier) {
            "right" -> "направо"; "left" -> "налево"; "slight right" -> "правее"; "slight left" -> "левее"
            "sharp right" -> "резко направо"; "sharp left" -> "резко налево"; "uturn" -> "разворот"; else -> "прямо"
        }
        return when (s.type) {
            "arrive" -> "Прибытие"
            "roundabout", "rotary", "roundabout turn" -> if (s.exit > 0) "Круговое движение, ${s.exit}-й съезд" else "Круговое движение"
            "exit roundabout", "exit rotary" -> "Съезд с кольца"
            "fork" -> if (s.modifier.contains("left")) "Держитесь левее" else if (s.modifier.contains("right")) "Держитесь правее" else "Прямо"
            "merge" -> "Перестройтесь " + if (s.modifier.contains("left")) "левее" else "правее"
            "on ramp" -> "Съезд на дорогу " + side
            "off ramp" -> "Съезд " + side
            else -> when (s.modifier) {
                "uturn" -> "Разворот"
                "slight right", "slight left" -> "Держитесь $side"
                "straight", "" -> "Прямо"
                else -> "Поверните $side"
            }
        }
    }

    /** The kind of [s] as [Turn] names them, for the cluster's arrow. */
    fun kind(s: RouteStep): String? = when {
        s.type == "arrive" -> Turn.FINISH
        s.type.startsWith("roundabout") || s.type == "rotary" -> Turn.ROUND
        else -> when (s.modifier) {
            "right" -> "right"; "left" -> "left"; "slight right" -> "slight_right"; "slight left" -> "slight_left"
            "sharp right" -> "sharp_right"; "sharp left" -> "sharp_left"; "uturn" -> Turn.UTURN_LEFT; else -> Turn.STRAIGHT
        }
    }

    /** "350 м", "1,2 км", "12 км". */
    fun distanceText(m: Double): String = when {
        m < 1000 -> "${((m / 10).toInt() * 10).coerceAtLeast(10)} м"
        m < 10_000 -> "%.1f км".format(m / 1000).replace('.', ',')
        else -> "${(m / 1000).toInt()} км"
    }
}
