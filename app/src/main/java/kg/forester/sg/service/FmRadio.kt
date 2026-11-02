package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.service.audio.AudioPlatforms
import kg.forester.sg.service.audio.RadioBackend
import kg.forester.sg.service.audio.RadioCap
import kg.forester.sg.service.audio.settled
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
 * The driver's own FM stations and tuning to them through the platform's tuner, for the player and the voice alike.
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

    private fun radio() = app?.let { AudioPlatforms.current(it).radio }

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
        val radio = radio() ?: return
        val s = neighbour(radio.state.value.freq, next)
        if (s != null) tune(s.freq, s.band) else if (RadioCap.PRESET in radio.caps) Fyt.radioPreset(next) else if (RadioCap.SEEK in radio.caps) radio.seek(next)
    }

    /** The last tune didn't reach the station (the screen says so instead of looking as if it had). */
    var failed by mutableStateOf(false)
        private set

    /**
     * To [target] (FM in 10 kHz units, AM in kHz): the radio made the source first, out of AM for an FM station, then
     * the platform's tuning. True only when the tuner got there; false at once if one is under way or none is there.
     */
    suspend fun tuneNow(target: Int, band: Int = 65536): Boolean {
        if (tuning) return false
        val radio = radio()?.takeIf { RadioCap.TUNE in it.caps } ?: return false
        tuning = true
        failed = false
        try {
            radio.on()
            if (target >= 8750 && !(radio.state.value.isFm || radio.state.value.band == -1)) toFm(radio, band)
            val ok = radio.tune(target)
            failed = !ok
            return ok
        } finally {
            tuning = false
        }
    }

    fun tune(target: Int, band: Int = 65536) { scope.launch { tuneNow(target, band) } }

    /** Out of AM for an FM station: the band by its number, else the other builds' "FM" (-3), else the band key. */
    private suspend fun toFm(radio: RadioBackend, band: Int) {
        for (b in listOf(if (band >= 65536) band else 65536, -3, -1)) {
            radio.band(b)
            if (withTimeoutOrNull(1500) { radio.state.first { it.isFm } } != null) break
        }
        radio.state.settled(radio.state.value.freq, 600)
    }

    /** The stock radio's preset [p] (1..6) by its slot: only for a slot we have never seen the frequency of. */
    fun toPreset(p: Int) {
        if (tuning) return
        val radio = radio()?.takeIf { RadioCap.PRESET in it.caps } ?: return
        tuning = true
        scope.launch {
            radio.on()
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
                withTimeoutOrNull(3000) { Fyt.radio.first { it.freq != f } }?.let { Fyt.radio.settled(it.freq, 400) }
            }
        }.invokeOnCompletion { tuning = false }
    }
}
