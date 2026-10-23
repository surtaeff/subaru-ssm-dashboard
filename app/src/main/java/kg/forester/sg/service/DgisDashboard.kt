package kg.forester.sg.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 2GIS's own service for car dashboards and head-up displays (ru.dublgis.api.DashboardInformationService, API 2,
 * open to any app): while it guides, the next manoeuvre, the speed bumps and other barriers, the cameras, the speed
 * limit, the traffic light ahead and the time left, and on request its map with the route drawn, as JPEG frames of
 * the size asked for ("cluster"). Spoken to over its AIDL by hand (the transaction numbers of its stub).
 */
object DgisDashboard {
    const val PKG = "ru.dublgis.dgismobile"
    private const val ACTION = "ru.dublgis.api.ACTION_BIND_DASHBOARD_INFORMATION_SERVICE"
    private const val SERVICE = "ru.dublgis.api.IDashboardInformationService"
    private const val UPDATE = "ru.dublgis.api.IUpdateCallback"
    private const val JPEG = "ru.dublgis.api.IJpegMapFrameCallback"
    private const val GET_JSON = 1
    private const val REGISTER = 2
    private const val UNREGISTER = 3
    private const val VERSION = 4
    private const val JPEG_FRAMES = 8
    private const val STOP_FRAMES = 9
    private const val MAP = "cluster"

    /** What 2GIS says now; the strings are its own, ready to show ("350 м", "17:42"). */
    data class Info(
        val mode: String,
        val maneuverDistance: String,
        val maneuverDescription: String,
        val maneuverIcon: String,
        val barrierIcon: String,
        val barrierDescription: String,
        val arrival: String,
        val remaining: String,
        val total: String,
        /** km/h (it sends m/s); 0: none. */
        val speedLimit: Double,
        val overLimit: Boolean,
        val cameraType: String,
        val cameraSubtype: String,
        /** How far along to the camera, 0–100 %; below 0: none. */
        val cameraPercent: Float,
        val lightColor: String,
        val lightArrow: String,
        val lightCountdown: Int,
    ) {
        /** Guiding along a route (not just driving with the map open). */
        val guiding get() = mode != "freeroam" && mode != "unknown" && mode.isNotEmpty() && (maneuverDistance.isNotEmpty() || total.isNotEmpty())
    }

    val info = MutableStateFlow<Info?>(null)
    /** The last map frame and when it came. */
    val frame = MutableStateFlow<Pair<Bitmap, Long>?>(null)

    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    @Volatile private var binder: IBinder? = null
    private var users = 0
    /** The map frames asked: width, height, dpi; null for none. */
    private var wantFrames: Triple<Int, Int, Int>? = null
    private var framesOn = false
    private val loggedIcons = HashSet<String>()

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    private fun call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit = {}) {
        val b = binder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /** "The data changed": read it again, on our thread (not 2GIS's binder call). */
    private val updates by lazy { object : Binder() {
        init { attachInterface(null, UPDATE) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(UPDATE)
            reply?.writeNoException()
            io.execute { refresh() }
            return true
        }
    } }

    private var frameLogged = false

    private val frames by lazy { object : Binder() {
        init { attachInterface(null, JPEG) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(JPEG)
            val bytes = data.createByteArray()
            val f = data.readFloat()
            reply?.writeNoException()
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                if (!frameLogged) { frameLogged = true; SsmTrace.log("2GIS map frame ${it.width}x${it.height}, ${bytes.size} bytes, value $f") }
                frame.value = it to System.currentTimeMillis()
            }
            return true
        }
    } }

    private val conn by lazy { object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            io.execute {
                runCatching {
                    var v = 0
                    call(VERSION, read = { v = it.readInt() })
                    SsmTrace.log("2GIS dashboard service connected, API $v")
                    call(REGISTER, write = { it.writeStrongBinder(updates) })
                    refresh()
                    syncFrames()
                }.onFailure { SsmTrace.log("2GIS dashboard: $it") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null; framesOn = false
            info.value = null; frame.value = null
        }
    } }

    /** A screen that shows it comes on; the service is bound while one is there. */
    fun start(ctx: Context) {
        io.execute {
            users++
            if (users > 1 || binder != null) return@execute
            val c = ctx.applicationContext
            app = c
            if (!installed(c)) return@execute
            val ok = runCatching { c.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!ok) SsmTrace.log("2GIS dashboard service not bound")
        }
    }

    fun stop() {
        io.execute {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return@execute
            runCatching {
                if (framesOn) call(STOP_FRAMES, write = { it.writeString(MAP) })
                call(UNREGISTER, write = { it.writeStrongBinder(updates) })
            }
            framesOn = false
            wantFrames = null
            if (binder != null) runCatching { app?.unbindService(conn) }
            binder = null
            info.value = null; frame.value = null
        }
    }

    /** The map frames of [w]×[h] px at [dpi] (null: none wanted any more). Only one client gets them at a time. */
    fun frames(size: Triple<Int, Int, Int>?) {
        io.execute {
            if (size == wantFrames) return@execute
            wantFrames = size
            syncFrames()
        }
    }

    private fun syncFrames() {
        if (binder == null) return
        runCatching {
            if (framesOn) { call(STOP_FRAMES, write = { it.writeString(MAP) }); framesOn = false; frame.value = null }
            val (w, h, dpi) = wantFrames ?: return
            val opts = JSONObject().put("width", w.coerceIn(100, 4000)).put("height", h.coerceIn(100, 4000))
                .put("dpi", dpi.coerceIn(100, 2000)).put("desiredFps", 15).put("jpegQuality", 80).toString()
            var r = ""
            call(JPEG_FRAMES, write = { it.writeString(MAP); it.writeStrongBinder(frames); it.writeString(opts) }, read = { r = it.readString().orEmpty() })
            framesOn = r == "success"
            // it sends a frame when its map changes: a car standing still gets the first one once it moves
            SsmTrace.log("2GIS map frames ${w}x$h: $r")
        }.onFailure { SsmTrace.log("2GIS map frames: $it") }
    }

    private fun refresh() {
        var json = ""
        runCatching { call(GET_JSON, read = { json = it.readString().orEmpty() }) }.onFailure { return }
        info.value = parse(json)?.also { i ->
            if (i.maneuverIcon.isNotEmpty() && loggedIcons.add(i.maneuverIcon)) SsmTrace.log("2GIS manoeuvre icon «${i.maneuverIcon}»: ${i.maneuverDescription}")
            if (i.barrierIcon.isNotEmpty() && loggedIcons.add("b:" + i.barrierIcon)) SsmTrace.log("2GIS barrier «${i.barrierIcon}»: ${i.barrierDescription}")
        }
    }

    fun parse(json: String): Info? = runCatching {
        val o = JSONObject(json)
        Info(
            mode = o.optString("activeNavigationMode"),
            maneuverDistance = o.optString("maneuverDistance"), maneuverDescription = o.optString("maneuverDescription"),
            maneuverIcon = o.optString("maneuverIcon"),
            barrierIcon = o.optString("barrierIcon"), barrierDescription = o.optString("barrierDescription"),
            arrival = o.optString("arrivalTime"), remaining = o.optString("remainingTime"), total = o.optString("totalDistance"),
            speedLimit = o.optDouble("speedLimit", 0.0) * 3.6, overLimit = o.optBoolean("exceedingMaxSpeedLimit"),
            cameraType = o.optString("trafficCameraType"), cameraSubtype = o.optString("trafficCameraSubtype"),
            cameraPercent = o.optDouble("trafficCameraDistancePercent", -1.0).toFloat(),
            lightColor = o.optString("trafficLightColor"), lightArrow = o.optString("trafficLightArrow"),
            lightCountdown = o.optInt("trafficLightCountdown", -1),
        )
    }.getOrNull()

    /**
     * The kind of a 2GIS manoeuvre by its icon's name (crossroad_left, crossroad_sharply_right_one, crossroad_keep_left,
     * crossroad_uturn, turn_over, ringroad_exit, finish…), as [Turn] names them; null when the name tells nothing.
     */
    fun kind(icon: String): String? {
        val s = icon.lowercase()
        val side = if ("left" in s) "left" else if ("right" in s) "right" else ""
        return when {
            "finish" in s -> Turn.FINISH
            "ringroad" in s || "roundabout" in s -> Turn.ROUND
            "uturn" in s || "turn_over" in s || "turnover" in s -> if (side == "right") Turn.UTURN_RIGHT else Turn.UTURN_LEFT
            side.isNotEmpty() && "sharp" in s -> "sharp_$side"
            side.isNotEmpty() && ("slight" in s || "keep" in s) -> "slight_$side"
            side.isNotEmpty() -> side
            "straight" in s || "start" in s -> Turn.STRAIGHT
            else -> null
        }
    }

    /** «Камера скорости» and the like, from the camera type. */
    fun cameraText(type: String): String = when (type) {
        "speed" -> "Камера скорости"
        "averageSpeed" -> "Средняя скорость"
        "lane" -> "Камера на полосу"
        "crossroad" -> "Камера на перекрёстке"
        "marking" -> "Камера разметки"
        "parking" -> "Камера парковки"
        else -> "Камера"
    }
}
