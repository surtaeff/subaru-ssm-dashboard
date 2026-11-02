package kg.forester.sg.service.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import kg.forester.sg.service.BtAudio
import kg.forester.sg.ssm.SsmTrace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

const val FYT = "fyt"
const val MICRONTEK = "microntek"
const val NWD = "nwd"
const val TS = "ts"
const val TOPWAY = "topway"
const val SPD = "spd"
const val HCN = "hcn"
const val GENERIC = "generic"

val detectOrder = listOf(FYT, MICRONTEK, NWD, TS, TOPWAY, SPD, HCN, GENERIC)

/**
 * What a head unit shows of itself. The brand and Build.MODEL are not here on purpose: they are faked on these units.
 *
 * [packages]: of those asked about; [launchers]: packages with a launcher entry; [avChannel]: what the audio HAL
 * answers to "av_channel="; [mcuSource]: Settings.System mcu_current_source; [tsBinder]: the TS service's binder
 * says it is ITsCommon; [twOpen]: what TWUtil's open returned, null if it isn't there or didn't answer.
 */
data class PlatformSignals(
    val packages: Set<String> = emptySet(),
    val launchers: Set<String> = emptySet(),
    val props: Map<String, String> = emptyMap(),
    val classes: Set<String> = emptySet(),
    val avChannel: String? = null,
    val mcuSource: String? = null,
    val tsBinder: Boolean = false,
    val twOpen: Int? = null,
)

/** [id]: the family, [GENERIC] for none or for more than one of them; [matches]: the families whose signs were seen. */
data class Detected(val id: String, val matches: List<String>)

private val MICRONTEK_PACKAGES = listOf("android.microntek.service", "com.microntek.bluetooth", "android.microntek.mtcser")
private const val MICRONTEK_CLASS = "android.microntek.CarManager"
private const val TOPWAY_CLASS = "android.tw.john.TWUtil"

private const val TW_RETRY_MS = 30L * 24 * 60 * 60 * 1000

/** The TWUtil probe that began at [startedAt] and never finished (the process died in it) is tried again after 30 days. */
internal fun twProbeDue(startedAt: Long, now: Long) = startedAt <= 0L || now < startedAt || now - startedAt >= TW_RETRY_MS

/** The mark of a TWUtil open that never came back (the process died in it), kept in time; see [twProbeDue]. */
internal object TwMark {
    private const val KEY = "twStartedAt"
    private const val OLD = "twStarted"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("audio_probe", Context.MODE_PRIVATE)

    fun due(ctx: Context): Boolean {
        val sp = sp(ctx)
        val now = System.currentTimeMillis()
        val at = twMarkFrom(sp.contains(OLD), sp.getLong(KEY, 0L), now)
        // the flag of earlier builds stayed for good: it becomes a mark of today
        if (sp.contains(OLD)) sp.edit().remove(OLD).putLong(KEY, at).commit()
        return twProbeDue(at, now)
    }

    fun begin(ctx: Context) { sp(ctx).edit().putLong(KEY, System.currentTimeMillis()).commit() }
    fun end(ctx: Context) { sp(ctx).edit().remove(KEY).apply() }
}

/** The mark's time: the one kept, else now for the old flag of earlier builds, else none (0). */
internal fun twMarkFrom(oldFlag: Boolean, at: Long, now: Long) = if (at > 0L) at else if (oldFlag) now else 0L

private fun PlatformSignals.prop(name: String) = !props[name].isNullOrBlank()

private val CHANNEL_NAME = Regex("^[a-z0-9_]+$")

/** "av_channel=sys" or a bare "sys": one name. A HAL that doesn't know the key answers nothing, the key back or something else. */
private fun channelValue(raw: String?): Boolean {
    val v = raw.orEmpty().trim().removePrefix("av_channel=").trim()
    return CHANNEL_NAME.matches(v) && v != "av_channel"
}

private fun firmSign(family: String, s: PlatformSignals): Boolean = when (family) {
    FYT -> "com.syu.ms" in s.packages || s.prop("ro.fyt.platform") || s.prop("sys.fyt.radio_type")
    MICRONTEK -> MICRONTEK_CLASS in s.classes || channelValue(s.avChannel) || MICRONTEK_PACKAGES.any { it in s.packages }
    NWD -> "com.nwd.kernel" in s.packages && "com.nwd.radio.service" in s.packages
    TS -> "com.ts.MainUI" in s.packages && s.tsBinder
    TOPWAY -> s.twOpen == 0
    SPD -> "com.spd.radio" in s.packages
    HCN -> "com.hcn.autoradio" in s.packages
    else -> false
}

/**
 * A launcher entry with "microntek" in its name can be any installed app, and mcu_current_source isn't known to be
 * NWD's alone: they tell a family only when nothing firm does, or an FYT unit could come out as two families.
 */
private fun looseSign(family: String, s: PlatformSignals): Boolean = when (family) {
    MICRONTEK -> s.launchers.any { "microntek" in it }
    NWD -> !s.mcuSource.isNullOrBlank()
    else -> false
}

/** Only what [PlatformProbe.quick] can see: packages and classes. */
private fun PlatformSignals.quickOnly() = copy(props = emptyMap(), avChannel = null, mcuSource = null, tsBinder = false, twOpen = null)

/**
 * The head unit's family from its signs: exactly one family, else [GENERIC]. One exception: with the FYT service
 * installed, a family only the late probes saw doesn't count (a stray HAL answer would cost a working unit its
 * radio and volume), it stays FYT with both in [Detected.matches] for the log.
 */
fun detectFamily(s: PlatformSignals): Detected {
    val firm = detectOrder.filter { firmSign(it, s) }
    if ("com.syu.ms" in s.packages && firm.size > 1 && detectOrder.filter { firmSign(it, s.quickOnly()) } == listOf(FYT)) return Detected(FYT, firm)
    val matches = firm.ifEmpty { detectOrder.filter { looseSign(it, s) } }
    return Detected(matches.singleOrNull() ?: GENERIC, matches)
}

/** The family by the installed packages and classes alone, with no lock and no scan of the launchers. */
fun quickFamily(ctx: Context): String = detectFamily(PlatformProbe.quick(ctx, launchers = false)).id

/** The launcher list only serves as a loose sign: not worth asking for once a firm one is there. */
internal fun needsLaunchers(s: PlatformSignals) = detectOrder.none { firmSign(it, s) }

private val FAMILY_PREFIXES = listOf("com.syu.", "android.microntek.", "com.microntek.", "com.nwd.", "com.ts.", "android.tw.", "com.spd.", "com.hcn.")

/** For the log, which drivers send: the signs and the head-unit families' own packages, never the apps they installed. */
internal fun PlatformSignals.forLog(): String = listOfNotNull(
    packages.sorted().joinToString(",").ifEmpty { null }?.let { "packages=$it" },
    launchers.filter { p -> "microntek" in p || FAMILY_PREFIXES.any { p.startsWith(it) } }.sorted().joinToString(",").ifEmpty { null }?.let { "launchers=$it" },
    props.filterValues { it.isNotBlank() }.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { null },
    classes.sorted().joinToString(",").ifEmpty { null }?.let { "classes=$it" },
    avChannel?.takeIf { channelValue(it) }?.let { "av_channel=${it.trim().removePrefix("av_channel=").trim()}" },
    mcuSource?.takeIf { it.isNotBlank() }?.let { "mcu_current_source=$it" },
    "ts_binder".takeIf { tsBinder },
    twOpen?.let { "tw_open=$it" },
).joinToString(" ")

/** Gathers [PlatformSignals] on a real unit. */
internal object PlatformProbe {
    private const val LIMIT_MS = 1500L
    /** onServiceConnected comes on the main thread, which is busy while the app starts. */
    private const val BIND_LIMIT_MS = 5000L
    private val ASKED = MICRONTEK_PACKAGES + listOf(
        "com.syu.ms", "com.nwd.kernel", "com.nwd.radio.service", "com.ts.MainUI", "com.spd.radio", "com.hcn.autoradio",
    )

    /** At once, on any thread: the package manager and the class loader, as the old checks on the UI thread. */
    fun quick(ctx: Context, launchers: Boolean = true): PlatformSignals {
        val s = PlatformSignals(
            packages = ASKED.filter { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }.toSet(),
            classes = listOf(MICRONTEK_CLASS, TOPWAY_CLASS).filter { c ->
                runCatching { Class.forName(c, false, ctx.classLoader); true }.getOrDefault(false)
            }.toSet(),
        )
        return if (launchers && needsLaunchers(s)) s.copy(launchers = BtAudio.launchable(ctx).map { it.activityInfo.packageName }.toSet()) else s
    }

    /** The rest, which may hang on some firmwares: each with a time limit, never on the main thread. */
    fun full(ctx: Context): PlatformSignals {
        val q = quick(ctx)
        return q.copy(
            props = limited { listOf("ro.fyt.platform", "sys.fyt.radio_type").associateWith { getprop(it) } }.orEmpty(),
            avChannel = limited { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getParameters("av_channel=") },
            mcuSource = limited { android.provider.Settings.System.getString(ctx.contentResolver, "mcu_current_source") },
            tsBinder = "com.ts.MainUI" in q.packages && tsBinder(ctx),
            twOpen = if (TOPWAY_CLASS in q.classes) twProbe(ctx) else null,
        )
    }

    /** In a thread of its own: a binder call that hangs can't be interrupted, it is left behind. */
    private fun <T : Any> limited(probe: () -> T?): T? {
        val out = AtomicReference<T?>()
        val t = Thread({ out.set(runCatching(probe).getOrNull()) }, "audio-probe")
        t.isDaemon = true
        t.start()
        t.join(LIMIT_MS)
        return out.get()
    }

    private fun getprop(name: String): String =
        Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText().trim() }

    /** Binds the service of the stock TS interface for a moment and asks what its binder is. Not tried on a unit yet. */
    private fun tsBinder(ctx: Context): Boolean {
        val got = AtomicReference<IBinder?>()
        val done = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { got.set(service); done.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent("android.intent.action.MAIN_UI").setComponent(ComponentName("com.ts.MainUI", "com.ts.main.common.MainUI"))
        try {
            if (!ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)) return false
            done.await(BIND_LIMIT_MS, TimeUnit.MILLISECONDS)
            return got.get()?.let { b -> limited { b.interfaceDescriptor } }?.contains("ITsCommon") == true
        } catch (_: Exception) {
            return false
        } finally {
            // also after a false from bindService: the connection stays registered until then
            runCatching { ctx.unbindService(conn) }
        }
    }

    /**
     * A crash in TWUtil's native code can't be caught and would come back on every start: the mark is written first
     * and stays if the process died (or the call hung) in there, and the probe isn't made again.
     */
    private fun twProbe(ctx: Context): Int? {
        if (!TwMark.due(ctx)) {
            SsmTrace.log("audio: TWUtil probe didn't finish last time, skipped", keep = true)
            return null
        }
        // written before the call, not in the background: a crash wouldn't leave time for that
        TwMark.begin(ctx)
        return limited {
            try { twOpen() } finally { TwMark.end(ctx) }
        }
    }

    /** TWUtil's own open over the radio's channels, closed again at once if it opened; it fails while the stock radio holds them. */
    private fun twOpen(): Int? {
        val cls = Class.forName(TOPWAY_CLASS)
        val tw = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        val channels = intArrayOf(265, 513, 769, 1025, 1026, 1028, 1029, 1030).map { it.toShort() }.toShortArray()
        val code = cls.getMethod("open", ShortArray::class.java).invoke(tw, channels) as? Int
        if (code == 0) runCatching { cls.getMethod("close").invoke(tw) }
        return code
    }
}
