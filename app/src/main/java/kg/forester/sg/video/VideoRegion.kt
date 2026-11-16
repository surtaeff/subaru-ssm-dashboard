package kg.forester.sg.video

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where the driver is, for «Видео». ts.kg and oc.kg answer only Kyrgyz addresses (checked 2026-11-16 from 8+ countries
 * incl. Russia and Kazakhstan: 403 from www.ts.kg and www.oc.kg, the HLS nodes don't answer at all), so outside
 * Kyrgyzstan the screen offers the legal services instead (Rutube, the official apps).
 *
 * «Регион видео»: [AUTO] (default), [KG] or [OTHER]. Auto asks the ts.kg favicon (9 KB, status only): 2xx is Kyrgyzstan,
 * 403 is another country, no answer decides nothing. The answer is kept 12 h; a 403 from a site later also counts.
 */
object VideoRegion {
    const val AUTO = "auto"
    const val KG = "kg"
    const val OTHER = "other"

    private const val PROBE_URL = "https://www.ts.kg/img/favicon.png"
    private const val KEEP_MS = 12 * 3600_000L

    /** What the screen goes by: true Kyrgyz sources, false the others, null not known yet (being checked, or offline). */
    val inKyrgyzstan = MutableStateFlow<Boolean?>(null)
    /** The setting, as Compose state for the chips. */
    val setting = MutableStateFlow(AUTO)

    private val mutex = Mutex()
    @Volatile private var inited = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        setting.value = p.getString("region", AUTO) ?: AUTO
        inKyrgyzstan.value = when (setting.value) {
            KG -> true
            OTHER -> false
            else -> if (p.contains("autoKg")) p.getBoolean("autoKg", true) else null
        }
    }

    fun set(c: Context, v: String) {
        init(c)
        prefs(c).edit().putString("region", v).apply()
        setting.value = v
        inKyrgyzstan.value = when (v) {
            KG -> true
            OTHER -> false
            else -> prefs(c).takeIf { it.contains("autoKg") }?.getBoolean("autoKg", true)
        }
    }

    /** «Авто»: asks again when the kept answer is older than 12 h, or [force]. Suspends on IO. */
    suspend fun refresh(c: Context, force: Boolean = false) {
        init(c)
        if (setting.value != AUTO) return
        mutex.withLock {
            val p = prefs(c)
            val at = p.getLong("autoAtWall", 0L)
            val fresh = p.contains("autoKg") && System.currentTimeMillis() - at in 0..KEEP_MS
            if (fresh && !force) return
            val code = withContext(Dispatchers.IO) { VideoNet.status(PROBE_URL, 6_000, 6_000) }
            val kg = decide(code) ?: return
            remember(c, kg)
        }
    }

    /** The probe's status → Kyrgyzstan or not; null when it says nothing (no network, a server error). */
    fun decide(code: Int): Boolean? = when {
        code in 200..399 -> true
        code == 403 || code == 451 -> false
        else -> null
    }

    /** A site said 403: in «авто» the driver is outside Kyrgyzstan, whatever was kept. */
    fun blockedSeen(c: Context) {
        if (setting.value == AUTO) remember(c, false)
    }

    private fun remember(c: Context, kg: Boolean) {
        prefs(c).edit().putBoolean("autoKg", kg).putLong("autoAtWall", System.currentTimeMillis()).apply()
        if (setting.value == AUTO) inKyrgyzstan.value = kg
    }
}
