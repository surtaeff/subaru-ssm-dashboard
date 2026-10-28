package kg.forester.sg.service

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The driver's own FM stations and tuning to them on an FYT unit, for the player and the voice alike.
 *
 * The stations are ours (frequency, band, name in our prefs) and are tuned by frequency. The stock radio's
 * preset slots are not used for them: after its autoscan a slot holds another station, so a station picked by
 * slot "stopped working" until the driver deleted and scanned again in the stock app.
 */
object FmRadio {
    data class Station(val freq: Int, val name: String, val band: Int = 65536)

    /** In the order they were saved; FM frequency in 10 kHz units. */
    var mine by mutableStateOf<List<Station>>(emptyList())
        private set
    /** A tune is under way (the player says "ищу станцию…"). */
    var tuning by mutableStateOf(false)
        private set

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        // "freq|name" (before 0.9-beta30) or "freq|name|band"
        mine = prefs(ctx).getString("mine", "").orEmpty().split(';').mapNotNull { e ->
            val f = e.split('|')
            val freq = f[0].toIntOrNull() ?: return@mapNotNull null
            Station(freq, f.getOrElse(1) { "" }, f.getOrNull(2)?.toIntOrNull() ?: 65536)
        }
    }

    private fun save() {
        app?.let { ctx ->
            prefs(ctx).edit().putString("mine", mine.joinToString(";") { "${it.freq}|${it.name.replace(";", " ").replace("|", " ")}|${it.band}" }).apply()
        }
    }

    fun add(freq: Int, name: String, band: Int = 65536) {
        if (mine.none { abs(it.freq - freq) < 5 }) { mine = mine + Station(freq, name, if (band >= 65536) band else 65536); save() }
    }

    fun remove(freq: Int) { mine = mine.filter { it.freq != freq }; save() }

    /** The stock radio's presets we have seen ([known]: "101.70|name" by slot) copied into ours; how many were new. */
    fun import(known: Collection<String?>): Int {
        val before = mine.size
        known.filterNotNull().forEach { v ->
            val f = v.substringBefore('|').toDoubleOrNull()?.let { Math.round(it * 100).toInt() } ?: return@forEach
            if (f in 8750..10800) add(f, v.substringAfter('|', ""))
        }
        return mine.size - before
    }

    /** Ours by number (1-based), by name, or the next / previous one from [cur]. */
    fun byNumber(n: Int) = mine.getOrNull(n - 1)

    fun byName(name: String): Station? {
        val q = name.lowercase().replace('ё', 'е').trim()
        if (q.isEmpty()) return null
        return mine.firstOrNull { it.name.lowercase().replace('ё', 'е').let { n -> n.isNotBlank() && (n.contains(q) || q.contains(n)) } }
    }

    fun neighbour(cur: Int, next: Boolean): Station? {
        if (mine.isEmpty()) return null
        val i = mine.indexOfFirst { abs(it.freq - cur) <= 15 }
        val j = if (i < 0) (if (next) 0 else mine.size - 1) else (i + (if (next) 1 else -1) + mine.size) % mine.size
        return mine[j]
    }

    /** ⏮ ⏭ (the player, the wheel, the voice): through ours when there are any, else the tuner's presets. */
    fun step(next: Boolean) {
        val s = neighbour(Fyt.radio.value.freq, next)
        if (s != null) tune(s.freq, s.band) else Fyt.radioPreset(next)
    }

    /**
     * To [target] (FM in 10 kHz units, AM in kHz): the radio made the source first, out of AM for an FM station,
     * then the frequency command; no reaction at all → the stock radio app is started (it relays our commands on
     * radio type 3 / 4) and the command sent again; a tuner that still doesn't take a frequency gets there seek
     * by seek.
     */
    fun tune(target: Int, band: Int = 65536) {
        if (tuning) return
        tuning = true
        scope.launch {
            radioOn()
            val fmTarget = target >= 8750
            if (fmTarget && !(Fyt.radio.value.isFm || Fyt.radio.value.band == -1)) toFm(band)
            val tol = if (fmTarget) 15 else 9
            if (near(target, tol)) return@launch
            val f0 = Fyt.radio.value.freq
            if (direct(target, tol)) return@launch
            // not a single update: the command went nowhere
            if (Fyt.radio.value.freq == f0 && Fyt.wakeRadioApp()) {
                withTimeoutOrNull(5000) { Fyt.appId.first { it == 1 } }
                // it comes up on its own last station: let it, then ours
                settled(Fyt.radio.value.freq, 1500)
                if (near(target, tol) || direct(target, tol)) return@launch
            }
            seekTo(target, tol)
        }.invokeOnCompletion { tuning = false }
    }

    private fun near(target: Int, tol: Int) = abs(Fyt.radio.value.freq - target) <= tol

    /**
     * The frequency command in each of [Fyt.TUNE_MODES], the one that worked on this unit last first (it is
     * remembered); true when the tuner got there.
     */
    private suspend fun direct(target: Int, tol: Int): Boolean {
        val sp = app?.let { prefs(it) }
        val learned = sp?.getInt("tuneMode", -1) ?: -1
        val modes = (listOf(learned).filter { it in Fyt.TUNE_MODES } + Fyt.TUNE_MODES.toList()).distinct()
        for (mode in modes) {
            Fyt.radioTune(target, mode)
            if (withTimeoutOrNull(2500) { Fyt.radio.first { abs(it.freq - target) <= tol } } == null) continue
            settled(Fyt.radio.value.freq, 400)
            if (mode != learned) sp?.edit()?.putInt("tuneMode", mode)?.apply()
            return near(target, tol)
        }
        return false
    }

    /** Out of AM for an FM station: the band by its number, else the other builds' "FM" (-3), else the band key. */
    private suspend fun toFm(band: Int) {
        for (b in listOf(if (band >= 65536) band else 65536, -3, -1)) {
            Fyt.radioBand(b)
            if (withTimeoutOrNull(1500) { Fyt.radio.first { it.isFm } } != null) break
        }
        settled(Fyt.radio.value.freq, 600)
    }

    /** Station by station towards [target], for a tuner without the frequency command. */
    private suspend fun seekTo(target: Int, tol: Int) {
        for (i in 0 until 12) {
            val cur = Fyt.radio.value.freq
            if (abs(cur - target) <= tol) break
            val up = target > cur
            Fyt.radioSeek(up, ensure = false)
            val first = withTimeoutOrNull(8000) { Fyt.radio.first { it.freq != cur } }?.freq ?: break
            // the tuner reports the frequencies it passes while seeking; the next seek sent before it stops
            // cancelled this one, and the tuner went back to where it started
            val now = settled(first, 900)
            // past the target, or round the end of the band: stop at this station
            if ((up && (now >= target - tol || now < cur)) || (!up && (now <= target + tol || now > cur))) break
        }
    }

    /** The stock radio's preset [p] (1..6) by its slot: only for a slot we have never seen the frequency of. */
    fun toPreset(p: Int) {
        if (tuning) return
        tuning = true
        scope.launch {
            radioOn()
            if (Fyt.radio.value.preset == p) return@launch
            // the preset itself; a unit that doesn't take that gets it step by step
            val f0 = Fyt.radio.value.freq
            Fyt.radioToPreset(p)
            if (withTimeoutOrNull(2500) { Fyt.radio.first { it.preset == p || it.freq != f0 } } != null) return@launch
            val cur = Fyt.radio.value.preset.takeIf { it in 1..6 } ?: 1
            val fwd = (p - cur + 6) % 6
            repeat(if (fwd <= 3) fwd else 6 - fwd) {
                val f = Fyt.radio.value.freq
                Fyt.radioPreset(fwd <= 3, ensure = false)
                withTimeoutOrNull(3000) { Fyt.radio.first { it.freq != f } }?.let { settled(it.freq, 400) }
            }
        }.invokeOnCompletion { tuning = false }
    }

    /**
     * Makes the radio the source and waits for it: coming up, it tunes to its own last station, and a seek or
     * preset sent before that was undone by it.
     */
    private suspend fun radioOn() {
        if (Fyt.appId.value == 1 || Fyt.radioUnconfirmed) return
        Fyt.ensureRadio()
        // the source command, and the radio's activity when that alone didn't do (after 1.5 s)
        withTimeoutOrNull(5000) { Fyt.appId.first { it == 1 } }
        settled(Fyt.radio.value.freq, 1500)
    }

    /** The frequency once the tuner has stayed on it for [quietMs], starting from [from] (at most ~15 s). */
    private suspend fun settled(from: Int, quietMs: Long): Int {
        var f = from
        repeat(15_000 / quietMs.toInt()) {
            f = withTimeoutOrNull(quietMs) { Fyt.radio.first { it.freq != f } }?.freq ?: return f
        }
        return f
    }
}
