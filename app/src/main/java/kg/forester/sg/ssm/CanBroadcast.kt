package kg.forester.sg.ssm

import java.util.concurrent.ConcurrentHashMap

/**
 * What a Subaru's units send on the CAN bus by themselves, without being asked (OpenPort 2.0 listening to all
 * traffic): engine speed, pedal, speeds, temperatures, gear, cruise. Read with no request at all, so it works while
 * a laptop or a scan tool holds the diagnostics, and gives values the diagnostics do not.
 *
 * Ids and bits from comma.ai's open CAN database (opendbc, generator/subaru/_subaru_preglobal_2015.dbc: Legacy,
 * Outback, Forester, Impreza of about 2010–2018); little-endian signals. Other years may differ: whatever does not
 * come stays empty. 0x362 is named Fuel there with no fields decoded yet; its bytes go to the trace for that.
 */
object CanBroadcast {
    data class Values(
        val rpm: Int? = null,
        val pedalPct: Float? = null,
        val speedKmh: Float? = null,
        /** FL, FR, RL, RR. */
        val wheels: List<Float>? = null,
        val coolantC: Int? = null,
        val oilC: Int? = null,
        val gear: Int? = null,
        val cruiseOn: Boolean? = null,
        val cruiseActive: Boolean? = null,
    )

    private class Frame(val data: ByteArray, val ms: Long)

    private val frames = ConcurrentHashMap<Int, Frame>()
    @Volatile var lastMs = 0L
        private set
    private var fuelLoggedMs = 0L

    /** A frame off the bus (any id): kept by id, the latest wins. */
    fun feed(id: Int, data: ByteArray, now: Long = System.currentTimeMillis()) {
        if (id in 0x7E0..0x7EF) return // diagnostics, not broadcast
        // the monitor and the driver's own values off the same bus (a CAN sensor board next to the car's units)
        CanBus.dispatch(CanFrame(id, false, data), now)
        frames[id] = Frame(data, now)
        lastMs = now
        if (id == 0x362 && now - fuelLoggedMs > 5000) { fuelLoggedMs = now; SsmTrace.log("CAN 362 (Fuel, not decoded yet)", data) }
    }

    /** Broadcast frames came in the last [ms]. */
    fun fresh(now: Long = System.currentTimeMillis(), ms: Long = 2000) = now - lastMs < ms

    /** How many different ids are on the bus: to show the driver that listening works at all. */
    val idsSeen: Int get() = frames.size

    fun clear() { frames.clear(); lastMs = 0L }

    /** [len] bits from bit [start] of [d], little-endian (Intel) as the DBC's "@1+". */
    fun bits(d: ByteArray, start: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) {
            val b = start + i
            if (b / 8 >= d.size) return -1
            if ((d[b / 8].toInt() shr (b % 8)) and 1 == 1) v = v or (1L shl i)
        }
        return v
    }

    /** What the frames newer than [maxAgeMs] say. */
    fun values(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 2000): Values {
        fun f(id: Int) = frames[id]?.takeIf { now - it.ms <= maxAgeMs }?.data
        fun ok(v: Long) = v >= 0
        val throttle = f(0x140)
        val temp = f(0x360)
        val brake = f(0xD1)
        val wheels = f(0xD4)
        val tr = f(0x148)
        val cruise = f(0x144)
        return Values(
            rpm = throttle?.let { bits(it, 16, 14) }?.takeIf(::ok)?.toInt()?.takeIf { it in 0..9000 },
            pedalPct = throttle?.let { bits(it, 0, 8) }?.takeIf(::ok)?.let { it * 0.392157f }?.takeIf { it in 0f..100.5f },
            speedKmh = brake?.let { bits(it, 0, 16) }?.takeIf(::ok)?.let { it * 0.05625f }?.takeIf { it in 0f..300f },
            wheels = wheels?.let { d -> (0 until 4).map { bits(d, it * 16, 16) * 0.0592f } }?.takeIf { l -> l.all { it in 0f..300f } },
            coolantC = temp?.let { bits(it, 24, 8) }?.takeIf(::ok)?.let { it.toInt() - 40 }?.takeIf { it in -40..150 },
            oilC = temp?.let { bits(it, 16, 8) }?.takeIf(::ok)?.let { it.toInt() - 40 }?.takeIf { it in -40..160 },
            gear = tr?.let { bits(it, 48, 4) }?.takeIf(::ok)?.toInt(),
            cruiseOn = cruise?.let { bits(it, 48, 1) }?.takeIf(::ok)?.let { it == 1L },
            cruiseActive = cruise?.let { bits(it, 49, 1) }?.takeIf(::ok)?.let { it == 1L },
        )
    }

    /** [e] with what the bus says filling the gaps the diagnostics left (the bus never overrides them). */
    fun fill(e: EcuSnapshot, now: Long = System.currentTimeMillis()): EcuSnapshot {
        if (!fresh(now)) return e
        val v = values(now)
        return e.copy(
            rpm = e.rpm ?: v.rpm,
            speedKmh = e.speedKmh ?: v.speedKmh?.toInt(),
            coolantC = e.coolantC ?: v.coolantC,
            pedalPct = e.pedalPct ?: v.pedalPct,
        )
    }

    /** A sample made from the bus alone: no diagnostics at all (another tool holds them, or the car answers none). */
    fun snapshot(now: Long = System.currentTimeMillis()): EcuSnapshot = fill(EcuSnapshot(timeMs = now), now)
}
