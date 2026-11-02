package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.BumpRule
import kg.forester.sg.data.KnockEvent
import kg.forester.sg.data.KnockSeen
import kg.forester.sg.data.KnockStats
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the ECU knock correction (SSM 0x000022, or the FBKC where that pulls deeper) into timing-pull events for the
 * current trip. An event starts at [KnockStats.START_DEG] or deeper and ends once the correction is back at 0; one
 * starting again within [KnockStats.MERGE_MS] carries on, so a value flickering between -0.35° and 0 counts once.
 * Finished events are appended to logs/knock.csv. A pull of [kg.forester.sg.data.Settings.knockBeepDeg] or deeper,
 * from [kg.forester.sg.data.Settings.knockBeepRpm] up, beeps once (the BTSSM beeper, without −1° low down beeping all the time).
 * A pull that passes [BumpRule] is marked once [BumpRule.QUIET_MS] have gone by with no other, and only then written.
 */
class KnockWatch internal constructor(
    private val file: File,
    /** The strongest vertical shock between two moments, null with no accelerometer. */
    private val shock: (Long, Long) -> Float?,
    /** FLKC and IAM now. */
    private val learned: () -> Pair<Float?, Float?>,
    private val beeping: Boolean,
) {
    constructor(ctx: Context) : this(
        File(File(ctx.getExternalFilesDir(null), "logs"), "knock.csv"), Motion::shockG,
        { Repo.guard.value.let { it.flkc to it.iam } }, true,
    )

    private var stats = KnockStats(sinceMs = System.currentTimeMillis())
    /** The last pull, ended but not yet in knock.csv: it may still carry on (see [KnockStats.MERGE_MS]). */
    private var pending: KnockEvent? = null
    /** The pull that has beeped already (its start), and the last beep. */
    private var beepedStart = -1L
    private var beepMs = 0L
    /** How the open or the pending pull went, what [BumpRule] judges. */
    private var seen = KnockSeen()
    /** The pull had another this close before it. */
    private var neighbour = false
    /** The throttle at the sample before a pull: the moving foot starts before the pull does. */
    private var lastThrottle: Float? = null
    private var headerChecked = false

    init {
        Repo.knock.value = stats
    }

    /** [record] false keeps demo data out of knock.csv. */
    @Synchronized
    fun process(s: EcuSnapshot, record: Boolean = true) {
        val k = s.knockDeg ?: return
        pending?.let { if (s.timeMs - (it.startMs + it.durationMs) > BumpRule.QUIET_MS) flush(judge = true) }
        val open = stats.events.lastOrNull()?.takeIf { it.active }
        when {
            // engine stopped: the byte means nothing then
            (s.rpm ?: 0) < 300 -> if (open != null) close(open, s.timeMs, record)
            open == null -> if (k <= KnockStats.START_DEG) {
                val last = stats.events.lastOrNull()
                val sinceLast = last?.let { s.timeMs - (it.startMs + it.durationMs) }
                // flicker: the pull that just ended goes on (its deepest point kept)
                if (last != null && sinceLast!! in 0..KnockStats.MERGE_MS) {
                    pending = null
                    publish(stats.events.dropLast(1) + (if (k < last.maxPullDeg) event(s, k, last.startMs).copy(shockG = last.shockG) else last.copy(active = true)))
                } else {
                    flush(judge = false) // another one close by: neither was a lone bump
                    neighbour = sinceLast != null && sinceLast in 0..BumpRule.QUIET_MS
                    val (flkc, iam) = learned()
                    seen = KnockSeen(flkcStart = flkc, iamStart = iam, minThrottle = lastThrottle, maxThrottle = lastThrottle)
                    publish(stats.events + event(s, k, s.timeMs))
                }
            }
            k >= 0f -> close(open, s.timeMs, record)
            k < open.maxPullDeg -> publish(stats.events.dropLast(1) + event(s, k, open.startMs).copy(shockG = open.shockG))
        }
        watch(s)
        if (beeping && record) stats.events.lastOrNull()?.takeIf { it.active }?.let { beep(it, k, s.rpm ?: 0, s.timeMs) }
    }

    /** What the rule needs of this sample, while a pull is open; the shock also for a moment after it. */
    private fun watch(s: EcuSnapshot) {
        lastThrottle = s.throttlePct ?: lastThrottle
        val e = stats.events.lastOrNull() ?: return
        if (e.active) {
            val (flkc, iam) = learned()
            seen = seen.with(s.gaugeKpa, s.loadGRev, s.throttlePct, flkc, iam)
        } else if (e !== pending || s.timeMs - (e.startMs + e.durationMs) > BumpRule.QUIET_MS) return
        else learned().let { (flkc, iam) -> seen = seen.copy(flkcEnd = flkc, iamEnd = iam) }
        val end = if (e.active) s.timeMs else e.startMs + e.durationMs
        if (s.timeMs > end + BumpRule.SHOCK_NEAR_MS) return
        val g = shock(e.startMs - BumpRule.SHOCK_NEAR_MS, end + BumpRule.SHOCK_NEAR_MS) ?: return
        if (g != e.shockG && g > (e.shockG ?: -1f)) {
            val next = e.copy(shockG = g)
            if (e === pending) pending = next
            publish(stats.events.dropLast(1) + next)
        }
    }

    private fun beep(e: KnockEvent, k: Float, rpm: Int, now: Long) {
        val deg = Repo.settings.knockBeepDeg
        if (deg >= 0f || k > deg || rpm < Repo.settings.knockBeepRpm || e.startMs == beepedStart || now - beepMs < 2000L) return
        beepedStart = e.startMs; beepMs = now
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250)
        }
    }

    /** A new trip: keep the old trip's pulls in knock.csv only. */
    @Synchronized
    fun reset(now: Long, record: Boolean = true) {
        stats.events.lastOrNull()?.takeIf { it.active }?.let { close(it, now, record) }
        flush()
        publish(emptyList(), now)
    }

    private fun event(s: EcuSnapshot, k: Float, startMs: Long) = KnockEvent(
        startMs = startMs, maxPullDeg = k, rpm = s.rpm, loadGRev = s.loadGRev, throttlePct = s.throttlePct,
        speedKmh = s.speedKmh, coolantC = s.coolantC, timingDeg = s.timingDeg,
    )

    private fun close(e: KnockEvent, now: Long, record: Boolean) {
        val done = e.copy(durationMs = now - e.startMs, active = false)
        publish(stats.events.dropLast(1) + done)
        if (record) pending = done
    }

    /** The pending pull goes to the file; [judge]: the quiet time has passed, so it may be marked. */
    private fun flush(judge: Boolean = false) {
        pending?.let { e ->
            val out = if (judge && BumpRule.isBump(e, seen, neighbour)) e.copy(bump = true) else e
            if (out !== e) stats.events.indexOfLast { it === e }.takeIf { it >= 0 }?.let { i ->
                publish(stats.events.toMutableList().also { it[i] = out })
            }
            runCatching { append(out) }
        }
        pending = null
    }

    private fun publish(events: List<KnockEvent>, sinceMs: Long = stats.sinceMs) {
        stats = KnockStats(sinceMs, events)
        Repo.knock.value = stats
    }

    /** A file from before these columns: its header is corrected once, the old rows simply have them empty. */
    private fun oldHeader() {
        if (headerChecked) return
        headerChecked = true
        if (!file.exists()) return
        val first = file.bufferedReader().use { it.readLine() } ?: return
        if (first.endsWith(COLUMNS_ADDED)) return
        file.writeText(file.readLines().let { l -> (listOf(first + COLUMNS_ADDED) + l.drop(1)).joinToString("\n") + "\n" })
    }

    private fun append(e: KnockEvent) {
        file.parentFile?.mkdirs()
        oldHeader()
        val fresh = !file.exists()
        FileWriter(file, true).use { w ->
            if (fresh) w.write("time,duration_s,max_pull_deg,rpm,load_g_rev,throttle_pct,speed_kmh,coolant_c,timing_deg$COLUMNS_ADDED\n")
            fun f(x: Float?) = x?.let { "%.2f".format(Locale.US, it) } ?: ""
            w.write(
                listOf(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.startMs)),
                    "%.1f".format(Locale.US, e.durationMs / 1000.0), f(e.maxPullDeg), e.rpm?.toString() ?: "",
                    f(e.loadGRev), f(e.throttlePct), e.speedKmh?.toString() ?: "", e.coolantC?.toString() ?: "", f(e.timingDeg),
                    if (e.bump) "1" else "0", f(e.shockG),
                ).joinToString(",") + "\n"
            )
        }
    }

    private companion object {
        const val COLUMNS_ADDED = ",bump,shock_g"
    }
}
