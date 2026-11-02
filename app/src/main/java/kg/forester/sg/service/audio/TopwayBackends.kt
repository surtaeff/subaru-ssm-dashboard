package kg.forester.sg.service.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import kg.forester.sg.service.RadioState
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Topway's TWUtil, EXPERIMENTAL and never run on a unit. The write calls are as the stock radio makes them; what the
 * unit answers with isn't known, so the frequency is taken only from a message of the radio's channel (1026) that
 * carries something that is a frequency. The channels are opened only when the driver picks the radio (a crash in
 * the open leaves the mark of [TwMark]), kept while it plays and closed again by [TopwayRadio.off]. [caps] stays
 * empty until such a report has been seen: a unit that says nothing gets internet radio.
 */
private const val TW_CHANNELS = "265,513,769,1025,1026,1028,1029,1030"
private const val TW_TUNE = 1026
private const val TW_SEEK = 1025
private const val TW_SOURCE = 769
private const val TW_SOURCE_HI = 40465
private const val TW_CLASS = "android.tw.john.TWUtil"

class TopwayRadio(private val ctx: Context) : RadioBackend {
    private val capsState = MutableStateFlow(emptySet<RadioCap>())
    override val capsFlow: StateFlow<Set<RadioCap>> = capsState.asStateFlow()
    override val tryable = true
    private val st = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = st.asStateFlow()
    private val running = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = running.asStateFlow()

    @Volatile private var tw: Any? = null
    private var seen = 0
    /** Counts the valid frequency reports; a state seen before a command can't confirm it. */
    private val reports = AtomicInteger()
    private val opening = AtomicBoolean(false)
    @Volatile private var first = CompletableDeferred<Unit>()

    private fun write(what: Int, a: Int, b: Int) {
        val t = tw ?: return
        runCatching { t.javaClass.getMethod("write", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(t, what, a, b) }
            .onFailure { SsmTrace.log("topway: write $what failed: $it", keep = true) }
    }

    /** A message of the unit: the first few go to the log, so the format can be learned from a real unit. */
    private fun onMessage(m: Message) {
        if (seen < 12) { seen++; SsmTrace.log("topway: msg what=${m.what} arg1=${m.arg1} arg2=${m.arg2}", keep = true) }
        if (m.what != TW_TUNE) return
        val f = listOf(m.arg2, m.arg1).firstNotNullOfOrNull { tsNormFreq(it) } ?: return
        st.value = st.value.copy(freq = f, band = if (f >= 8750) 65536 else 0)
        reports.incrementAndGet()
        first.complete(Unit)
    }

    /** Opens the channels (they fail while the stock radio holds them) and waits for the unit's first frequency. */
    override suspend fun on() {
        if (tw != null) { write(TW_SOURCE, 192, 1); write(TW_SOURCE_HI, 192, 1); return }
        if (!opening.compareAndSet(false, true)) return
        try {
            if (!TwMark.due(ctx)) { SsmTrace.log("topway: the last open didn't come back, not tried", keep = true); return }
            first = CompletableDeferred()
            val opened = withContext(Dispatchers.IO) { openWithin(5000) }
            if (opened == null) return
            tw = opened
            write(TW_SOURCE, 192, 1)
            write(TW_SOURCE_HI, 192, 1)
            if (withTimeoutOrNull(2500) { first.await() } == null) {
                SsmTrace.log("topway: no frequency report, radio left alone", keep = true)
                off()
                return
            }
            capsState.value = setOf(RadioCap.TUNE, RadioCap.SEEK)
            running.value = true
        } finally {
            opening.set(false)
        }
    }

    /** The open on its own thread with a limit; the mark stays if it doesn't come back, so a hang isn't repeated. */
    private suspend fun openWithin(limitMs: Long): Any? {
        val done = CompletableDeferred<Any?>()
        val abandoned = AtomicBoolean(false)
        TwMark.begin(ctx)
        val t = Thread({
            var util: Any? = null
            try {
                val cls = Class.forName(TW_CLASS)
                util = cls.getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                val channels = TW_CHANNELS.split(',').map { it.toShort() }.toShortArray()
                val code = cls.getMethod("open", ShortArray::class.java).invoke(util, channels) as? Int
                if (code != 0) {
                    SsmTrace.log("topway: open answered $code, radio left alone", keep = true)
                    util = null
                } else {
                    val h = object : Handler(Looper.getMainLooper()) { override fun handleMessage(msg: Message) { onMessage(msg) } }
                    cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(util, "carpulse", h)
                    cls.getMethod("start").invoke(util)
                }
            } catch (e: Throwable) {
                SsmTrace.log("topway: open failed: $e", keep = true)
                util?.let { close(it) }
                util = null
            }
            TwMark.end(ctx)
            if (abandoned.get()) util?.let { close(it) } else done.complete(util)
        }, "tw-open")
        t.isDaemon = true
        t.start()
        val got = withTimeoutOrNull(limitMs) { done.await() }
        if (got == null) abandoned.set(true)
        return got
    }

    private fun close(util: Any) {
        runCatching { util.javaClass.getMethod("removeHandler", String::class.java).invoke(util, "carpulse") }
        runCatching { util.javaClass.getMethod("close").invoke(util) }
    }

    /** Source off, handler removed, channels closed: the stock radio can have them again. */
    override suspend fun off() {
        val t = tw ?: return
        withContext(Dispatchers.IO) {
            write(TW_SOURCE, 192, 0)
            write(TW_SOURCE_HI, 192, 129)
            tw = null
            close(t)
        }
        running.value = false
        capsState.value = emptySet()
        st.value = RadioState()
    }

    override suspend fun tune(f: Int): Boolean {
        if (tw == null) return false
        val tol = tuneTolerance(f)
        val before = reports.get()
        withContext(Dispatchers.IO) { write(TW_TUNE, 255, f) }
        // only a report of the unit that came after the command counts
        return withTimeoutOrNull(2500) {
            while (!readBackConfirms(f, st.value.freq.takeIf { it > 0 }, tol, before, reports.get())) delay(100)
            true
        } == true
    }

    override fun seek(up: Boolean) { audioScope.launch(Dispatchers.IO) { write(TW_SEEK, 1, if (up) 1 else 0) } }
    override fun band(b: Int) {}
}
