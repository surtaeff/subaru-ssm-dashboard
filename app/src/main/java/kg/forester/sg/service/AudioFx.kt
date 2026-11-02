package kg.forester.sg.service

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Sound of the own player: equalizer (presets and bands), bass, surround, extra loudness, and the volume that
 * rises with the car's speed to cover road noise, like the speed-compensated volume of factory head units.
 * Android's audio effects on the player's audio session; a unit without one of them just doesn't offer it.
 */
object AudioFx {
    data class Cfg(
        val preset: String = "Дорога",
        /** Band gains in dB, as many as the unit's equalizer has. */
        val bands: List<Float> = emptyList(),
        /** 0..1 each. */
        val bass: Float = 0.3f,
        val surround: Float = 0f,
        /** Extra loudness, dB. */
        val loud: Float = 0f,
        val speedVolume: Boolean = true,
        val fadeIn: Boolean = true,
        /** A strip under the cover: "bars" (with falling peaks), "wave" (a mirrored curve), "off" (the default). */
        val vis: String = "off",
        /** Rays round the cover that move with the music. */
        val ring: Boolean = true,
    )

    /** Gains (dB) at 60, 230, 910, 3600, 14000 Hz; other band layouts get them interpolated. */
    val PRESETS = linkedMapOf(
        "Обычный" to listOf(0f, 0f, 0f, 0f, 0f),
        "Дорога" to listOf(4f, 2f, 0f, 2.5f, 3.5f),
        "Бас" to listOf(7f, 4.5f, 0f, 0f, 1f),
        "Рок" to listOf(5f, 3f, -1.5f, 3f, 5f),
        "Поп" to listOf(-1f, 2f, 4f, 2f, -1f),
        "Электроника" to listOf(5.5f, 3f, 0f, 2f, 4.5f),
        "Хип-хоп" to listOf(6f, 4f, 0f, 1f, 2.5f),
        "Джаз" to listOf(3f, 2f, -1f, 2f, 4f),
        "Классика" to listOf(4f, 2.5f, -1f, 2f, 3.5f),
        "Голос" to listOf(-3f, -1f, 4f, 3.5f, 1f),
    )
    private val PRESET_HZ = listOf(60f, 230f, 910f, 3600f, 14000f)

    val cfg = MutableStateFlow(Cfg())
    /** Centre frequencies (Hz) of the unit's equalizer bands and its gain range (dB); empty without one. */
    val bandsHz = MutableStateFlow<List<Float>>(emptyList())
    var rangeDb = -15f to 15f
        private set

    private var eq: Equalizer? = null
    private var bassFx: BassBoost? = null
    private var surroundFx: Virtualizer? = null
    private var loudFx: LoudnessEnhancer? = null
    private var appCtx: Context? = null
    @Volatile private var speedGainDb = 0f

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        val s = ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getString("fx", null) ?: return
        runCatching {
            val o = JSONObject(s)
            val b = o.optJSONArray("bands")
            cfg.value = Cfg(
                preset = o.optString("preset", "Дорога"),
                bands = if (b == null) emptyList() else List(b.length()) { b.getDouble(it).toFloat() },
                bass = o.optDouble("bass", 0.3).toFloat(), surround = o.optDouble("surround", 0.0).toFloat(),
                loud = o.optDouble("loud", 0.0).toFloat(), speedVolume = o.optBoolean("speedVolume", true),
                fadeIn = o.optBoolean("fadeIn", true),
                vis = o.optString("strip", "off"), ring = o.optBoolean("ring", true),
            )
        }
    }

    private fun save() {
        val c = cfg.value
        appCtx?.getSharedPreferences("music", Context.MODE_PRIVATE)?.edit()?.putString("fx", JSONObject().apply {
            put("preset", c.preset); put("bands", JSONArray(c.bands)); put("bass", c.bass.toDouble())
            put("surround", c.surround.toDouble()); put("loud", c.loud.toDouble())
            put("speedVolume", c.speedVolume); put("fadeIn", c.fadeIn); put("strip", c.vis); put("ring", c.ring)
        }.toString())?.apply()
    }

    /** Puts the effects on the player's audio [session]. */
    fun attach(session: Int) {
        release()
        if (session <= 0) return
        eq = runCatching { Equalizer(0, session) }.getOrNull()
        bassFx = runCatching { BassBoost(0, session) }.getOrNull()?.takeIf { it.strengthSupported }
        surroundFx = runCatching { Virtualizer(0, session) }.getOrNull()?.takeIf { it.strengthSupported }
        loudFx = runCatching { LoudnessEnhancer(session) }.getOrNull()
        eq?.let { e ->
            val r = e.bandLevelRange
            rangeDb = r[0] / 100f to r[1] / 100f
            bandsHz.value = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000f }
        }
        if (cfg.value.bands.size != bandsHz.value.size) cfg.value = cfg.value.copy(bands = presetBands(cfg.value.preset))
        apply()
    }

    fun release() {
        listOf(eq, bassFx, surroundFx, loudFx).forEach { runCatching { it?.release() } }
        eq = null; bassFx = null; surroundFx = null; loudFx = null
    }

    /** A preset spread over this equalizer's bands (log-frequency interpolation). */
    fun presetBands(name: String): List<Float> {
        val g = PRESETS[name] ?: PRESETS.values.first()
        return bandsHz.value.map { hz ->
            val x = log10(max(hz, 20f))
            val xs = PRESET_HZ.map { log10(it) }
            when {
                x <= xs.first() -> g.first()
                x >= xs.last() -> g.last()
                else -> {
                    val i = xs.indexOfLast { it <= x }
                    g[i] + (g[i + 1] - g[i]) * (x - xs[i]) / (xs[i + 1] - xs[i])
                }
            }
        }
    }

    fun setPreset(name: String) { cfg.value = cfg.value.copy(preset = name, bands = presetBands(name)); apply(); save() }
    fun setBand(i: Int, db: Float) {
        val b = cfg.value.bands.toMutableList().also { if (i in it.indices) it[i] = db.coerceIn(rangeDb.first, rangeDb.second) }
        cfg.value = cfg.value.copy(preset = "Свой", bands = b); apply(); save()
    }
    fun update(block: (Cfg) -> Cfg) { cfg.value = block(cfg.value); apply(); save() }
    fun reset() { cfg.value = Cfg(bands = presetBands("Дорога")); apply(); save() }

    val hasEq get() = eq != null
    val hasBass get() = bassFx != null
    val hasSurround get() = surroundFx != null
    val hasLoud get() = loudFx != null

    private fun apply() {
        val c = cfg.value
        runCatching {
            eq?.let { e ->
                c.bands.forEachIndexed { i, db -> e.setBandLevel(i.toShort(), (db * 100).toInt().toShort()) }
                e.enabled = true
            }
        }
        runCatching { bassFx?.let { it.setStrength((c.bass * 1000).toInt().toShort()); it.enabled = c.bass > 0.01f } }
        runCatching { surroundFx?.let { it.setStrength((c.surround * 1000).toInt().toShort()); it.enabled = c.surround > 0.01f } }
        applyLoud()
    }

    private fun applyLoud() {
        val db = cfg.value.loud + if (cfg.value.speedVolume) speedGainDb else 0f
        runCatching { loudFx?.let { it.setTargetGain((db * 100).toInt()); it.enabled = db > 0.05f } }
    }

    /**
     * From the car's speed: nothing up to 30 km/h, then up to +6 dB at 130 km/h, changed in small steps so the
     * music doesn't pump at every acceleration.
     */
    fun onSpeed(kmh: Float?) {
        val target = if (kmh == null) 0f else ((kmh - 30f) / 100f * 6f).coerceIn(0f, 6f)
        val stepped = (target * 2).toInt() / 2f
        if (abs(stepped - speedGainDb) >= 0.5f) { speedGainDb = stepped; applyLoud() }
    }
    val speedBoostDb get() = speedGainDb

    // --- spectrum for the screen ------------------------------------------------------------------------

    /** The most bands there can be. */
    const val BARS = 40
    /** How many this head unit draws (see DeviceTier). */
    val barCount = MutableStateFlow(BARS)
    var bars: Int
        get() = barCount.value
        set(v) { barCount.value = v }

    /** 0..1 levels of [bars] log-spaced bands, smoothed, while the screen shows them. */
    val spectrum = MutableStateFlow(FloatArray(BARS))
    /** True when the levels come from the music itself (Visualizer allowed), else the screen animates on its own. */
    val live = MutableStateFlow(false)
    private var vis: Visualizer? = null
    private var smooth = FloatArray(BARS)

    fun startSpectrum(session: Int) {
        stopSpectrum()
        if (session <= 0) return
        vis = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) onFft(fft, rate) }
                }, min(Visualizer.getMaxCaptureRate(), 20000), false, true)
                enabled = true
            }
        }.getOrNull()
        live.value = vis != null
    }

    fun stopSpectrum() {
        runCatching { vis?.enabled = false; vis?.release() }
        vis = null
        live.value = false
    }

    private fun onFft(fft: ByteArray, rateMilliHz: Int) {
        val n = fft.size / 2
        val nyquist = rateMilliHz / 2000f
        val count = bars
        if (smooth.size != count) smooth = FloatArray(count)
        val out = FloatArray(count)
        for (b in 0 until count) {
            // 40 Hz … 16 kHz, log spaced
            val f0 = 40f * Math.pow(400.0, b / count.toDouble()).toFloat()
            val f1 = 40f * Math.pow(400.0, (b + 1) / count.toDouble()).toFloat()
            val k0 = max(1, (f0 / nyquist * n).toInt())
            val k1 = max(k0 + 1, min(n - 1, (f1 / nyquist * n).toInt()))
            var m = 0f
            for (k in k0 until k1) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                m = max(m, hypot(re, im))
            }
            val db = if (m > 0) 20 * log10(m) else 0f
            val v = ((db - 8f) / 34f).coerceIn(0f, 1f)
            smooth[b] = if (v > smooth[b]) v else smooth[b] * 0.82f + v * 0.18f
            out[b] = smooth[b]
        }
        spectrum.value = out
    }
}
