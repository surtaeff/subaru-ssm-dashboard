package kg.forester.sg.service

import android.content.Context
import kg.forester.sg.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** The [Analyzer] in the app: its memory in the preferences, its findings through [Alerts], the last idle sample. */
object RulesHost {
    /** The last half minute of warm idle (for «Проверка»), restored at start. */
    val idle = MutableStateFlow<IdleSample?>(null)
    /** Earlier idle samples, oldest first, one per half hour at most: the trend (a MAF getting dirty, a leak growing). */
    val idleHistory = MutableStateFlow<List<IdleSample>>(emptyList())
    /** The knock map, the spool trend, the last pull, the gears: «Мониторинг → Откаты и наддув». */
    val diag = MutableStateFlow(DiagState())
    /** The ECU's A/F reading is a constant 14.7 (no sensor): the AFR card says «нет ШДК» instead. */
    val noWideband = MutableStateFlow(false)

    private var litresFor: Pair<String?, Float?>? = null
    private var profileFor: Pair<String, kg.forester.sg.ssm.EngineProfile>? = null

    /** The displacement [displacement] found for the connected ROM, for places without a Context (gauges, the log); null before it ran. */
    fun displacementNow(): Float? = litresFor?.takeIf { it.first == Repo.ecuInit.value?.romId }?.second

    /** Engine displacement by what the ECU says (EJ20 2.0, EJ25 2.5…), null when the size isn't known; cached per ROM. */
    fun displacement(ctx: Context): Float? {
        val rom = Repo.ecuInit.value?.romId
        litresFor?.let { (r, l) -> if (r == rom) return l }
        val family = runCatching { kg.forester.sg.ssm.EngineId.detect(ctx).family }.getOrDefault("EJ")
        val l = when (family) { "EJ20", "FA20" -> 2.0f; "EJ22" -> 2.2f; "EJ25" -> 2.5f; "EZ30" -> 3.0f; "EZ36" -> 3.6f; else -> null }
        litresFor = rom to l
        return l
    }

    /**
     * What is normal for this engine, by what the ECU says; cached per ROM and for whatever else detect goes by (boost
     * seen, a sports ECU, the demo). Another make never gets a Subaru's numbers, not even the last connected one's.
     */
    fun profile(ctx: Context): kg.forester.sg.ssm.EngineProfile {
        val other = kg.forester.sg.data.CarKind.other.value
        val state = "${Repo.ecuInit.value?.romId} ${Repo.guard.value.turboCar} $other ${kg.forester.sg.data.CanEcu.active} ${Repo.settings.demo}"
        profileFor?.let { (s, p) -> if (s == state) return p }
        val p = if (other) kg.forester.sg.ssm.EngineProfiles.GENERIC
            else runCatching { kg.forester.sg.ssm.EngineProfiles.of(kg.forester.sg.ssm.EngineId.detect(ctx)) }.getOrDefault(kg.forester.sg.ssm.EngineProfiles.GENERIC)
        profileFor = state to p
        Repo.engineProfile.value = p
        return p
    }

    /** IAM at the start of the last drive before [timeMs], from the guard's IAM log. */
    private fun iamLogBefore(timeMs: Long): Float? = runCatching {
        val a = org.json.JSONArray(Repo.settings.iamLog)
        (0 until a.length()).map { a.getJSONObject(it) }.lastOrNull { it.getLong("t") < timeMs }?.getDouble("v")?.toFloat()
    }.getOrNull()

    private fun toJson(s: IdleSample): JSONObject {
        val v = JSONObject(); s.values.forEach { (k, x) -> v.put(k, x.toDouble()) }
        return JSONObject().put("t", s.timeMs).put("v", v)
    }

    private fun fromJson(o: JSONObject): IdleSample {
        val v = o.getJSONObject("v")
        return IdleSample(o.getLong("t"), v.keys().asSequence().associateWith { v.getDouble(it).toFloat() })
    }

    /** A new sample: the latest one replaces a sample of the same half hour, the list keeps the last 40. */
    internal fun addToHistory(list: List<IdleSample>, s: IdleSample, sameStopMs: Long = 30 * 60_000L): List<IdleSample> {
        val last = list.lastOrNull()
        val kept = if (last != null && s.timeMs - last.timeMs < sameStopMs) list.dropLast(1) else list
        return (kept + s).takeLast(40)
    }

    private class Store(ctx: Context) : RuleStore {
        private val p = ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE)
        override var lastRunMs: Long
            get() = p.getLong("lastRun", 0L)
            set(v) = p.edit().putLong("lastRun", v).apply()
        override var lastIam: Float
            get() = p.getFloat("lastIam", Float.NaN)
            set(v) = p.edit().putFloat("lastIam", v).apply()
        override var iamResetMs: Long
            get() = p.getLong("iamReset", 0L)
            set(v) = p.edit().putLong("iamReset", v).apply()
        override var boostBase: Float
            get() = p.getFloat("boostBase", 0f)
            set(v) = p.edit().putFloat("boostBase", v).apply()
        override var boostNorm: String
            get() = p.getString("boostNorm", "")!!
            set(v) = p.edit().putString("boostNorm", v).apply()
        override var lastAfLearn: Float
            get() = p.getFloat("lastAfLearn", Float.NaN)
            set(v) = p.edit().putFloat("lastAfLearn", v).apply()
        override var ambientC: Float
            get() = p.getFloat("ambientC", 0f)
            set(v) = p.edit().putFloat("ambientC", v).apply()
        override var ambientMs: Long
            get() = p.getLong("ambientMs", 0L)
            set(v) = p.edit().putLong("ambientMs", v).apply()
        override var gears: String
            get() = p.getString("gears", "")!!
            set(v) = p.edit().putString("gears", v).apply()
        override var spool: String
            get() = p.getString("spool", "")!!
            set(v) = p.edit().putString("spool", v).apply()
        // the all-time knock map: a few KB, saved at most every half minute (see Analyzer.commit)
        override var knockMap: String
            get() = p.getString("knockMap", "")!!
            set(v) = p.edit().putString("knockMap", v).apply()
        override var spikeDrives: Int
            get() = p.getInt("spikeDrives", 0)
            set(v) = p.edit().putInt("spikeDrives", v).apply()
        override var spikeLastMs: Long
            get() = p.getLong("spikeLastMs", 0L)
            set(v) = p.edit().putLong("spikeLastMs", v).apply()
        override fun lastRefillMs(): Long? = runCatching { Repo.db.refills().maxOfOrNull { it.timeMs } }.getOrNull()
        override fun iamBefore(timeMs: Long) = iamLogBefore(timeMs)
    }

    /** The idle history lives in a file of its own: the rules' small values are saved every minute, this ~16 KB is not. */
    private fun histPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("idle_history", Context.MODE_PRIVATE)

    /** This car's own idle samples from storage, dropping whatever the demo showed. */
    fun restore(ctx: Context) {
        val h = histPrefs(ctx)
        idle.value = runCatching { fromJson(JSONObject(h.getString("idle", null) ?: return@runCatching null)) }.getOrNull()
        idleHistory.value = runCatching {
            val a = org.json.JSONArray(h.getString("idleHist", "[]"))
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Another car on the cable: its own IAM, usual boost and idle history, nothing from the last one. */
    fun forgetCar(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE).edit()
            .remove("lastIam").remove("iamReset").remove("boostBase").remove("lastRun").remove("lastAfLearn")
            .remove("gears").remove("spool").remove("knockMap").remove("spikeDrives").remove("spikeLastMs").apply()
        histPrefs(ctx).edit().clear().apply()
        idle.value = null
        idleHistory.value = emptyList()
        diag.value = DiagState()
        litresFor = null
        profileFor = null
    }

    /** «Сбросить» under the all-time knock map: this car's map starts over (after a fix, another fuel). */
    fun clearKnockMap(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("analyzer", Context.MODE_PRIVATE).edit().remove("knockMap").apply()
        analyzer?.clearKnockMap()
        diag.value = diag.value.copy(allKnock = kg.forester.sg.data.KnockMap(System.currentTimeMillis()))
    }

    private var analyzer: Analyzer? = null

    fun create(ctx: Context): Analyzer {
        restore(ctx)
        // in demo mode the findings are shown, not spoken; an info is not spoken either
        val sink = RuleSink { id, text, level, cooldown -> Alerts.raise(id, text, level, cooldown, speak = level != Alerts.Level.INFO) }
        // the demo's made-up drives must not become this car's memory (IAM, usual boost, parking time)
        val real = Store(ctx)
        val demo = MemoryRuleStore()
        val store = object : RuleStore {
            private val s get() = if (Repo.settings.demo) demo else real
            override var lastRunMs: Long get() = s.lastRunMs; set(v) { s.lastRunMs = v }
            override var lastIam: Float get() = s.lastIam; set(v) { s.lastIam = v }
            override var iamResetMs: Long get() = s.iamResetMs; set(v) { s.iamResetMs = v }
            override var boostBase: Float get() = s.boostBase; set(v) { s.boostBase = v }
            override var boostNorm: String get() = s.boostNorm; set(v) { s.boostNorm = v }
            override var lastAfLearn: Float get() = s.lastAfLearn; set(v) { s.lastAfLearn = v }
            override var ambientC: Float get() = s.ambientC; set(v) { s.ambientC = v }
            override var ambientMs: Long get() = s.ambientMs; set(v) { s.ambientMs = v }
            override var gears: String get() = s.gears; set(v) { s.gears = v }
            override var spool: String get() = s.spool; set(v) { s.spool = v }
            override var knockMap: String get() = s.knockMap; set(v) { s.knockMap = v }
            override var spikeDrives: Int get() = s.spikeDrives; set(v) { s.spikeDrives = v }
            override var spikeLastMs: Long get() = s.spikeLastMs; set(v) { s.spikeLastMs = v }
            override fun lastRefillMs() = s.lastRefillMs()
            override fun iamBefore(timeMs: Long) = s.iamBefore(timeMs)
        }
        val onDiag: (DiagState) -> Unit = { diag.value = it; noWideband.value = it.noWideband }
        val a = Analyzer(store, sink, { Repo.settings.feature(it) }, onDiag) { sample ->
            idle.value = sample
            // the demo's samples show on the screen (one per stop), they never go into this car's saved history
            if (Repo.settings.demo) idleHistory.value = addToHistory(idleHistory.value, sample, 60_000L)
            else {
                idleHistory.value = addToHistory(idleHistory.value, sample)
                val arr = org.json.JSONArray(); idleHistory.value.forEach { arr.put(toJson(it)) }
                histPrefs(ctx).edit().putString("idle", toJson(sample).toString()).putString("idleHist", arr.toString()).apply()
            }
        }
        analyzer = a
        return a
    }
}
