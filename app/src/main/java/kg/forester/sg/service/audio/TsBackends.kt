package kg.forester.sg.service.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kg.forester.sg.service.RadioState
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * ITsRadioCommon of the TS firmware, EXPERIMENTAL and never run on a unit. DORMANT: detection takes a unit for TS
 * when the MainUI binder says ITsCommon, while this wants ITsRadioCommon, so as it stands every TS unit ends up on
 * internet radio (the descriptor seen is logged). It stays as a small path until an APK shows where the radio binder
 * comes from; the fingerprint is not to be loosened. The transaction numbers are those of one
 * APK and differ between builds, so none of them is used before the binder says it is ITsRadioCommon and its frequency
 * call (tx 25) answers with something that is a frequency; otherwise the radio is left alone (see [TsRadio.open]).
 */
private const val TX_GRID_COUNT = 8
private const val TX_GRID_VALUE = 9
private const val TX_FM = 14
private const val TX_AM = 15
private const val TX_BAND_NEXT = 19
private const val TX_SEEK = 20
private const val TX_FREQ = 25
private const val TX_SOURCE = 29
private const val TX_TUNE_INDEX = 34

private const val TS_RADIO_INTERFACE = "ITsRadioCommon"

/** A reported frequency as the tuner's unit: FM 87.50..108.00 MHz in 10 kHz units, AM in kHz; null: not a frequency. */
internal fun tsNormFreq(raw: Int?): Int? = when (raw) {
    null -> null
    in 8750..10800, in 522..1710 -> raw
    in 87500..108000 -> raw / 10 // kHz
    else -> null
}

/** The binder is the radio interface and the frequency it answers with looks like one. */
internal fun tsFingerprintOk(descriptor: String?, freq: Int?): Boolean =
    descriptor != null && TS_RADIO_INTERFACE in descriptor && tsNormFreq(freq) != null

/** The index of exactly [f] in the ascending grid of [count] values (binary search), null if it isn't on it. */
internal fun tsGridIndex(count: Int, f: Int, valueAt: (Int) -> Int): Int? {
    var lo = 0
    var hi = count - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = valueAt(mid)
        when {
            v == f -> return mid
            v < f -> lo = mid + 1
            else -> hi = mid - 1
        }
    }
    return null
}

private class TsLink(val binder: IBinder, val descriptor: String) {
    /** One transaction with int [args]; the reply's first int, null if the call failed. */
    fun call(code: Int, vararg args: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            args.forEach { data.writeInt(it) }
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (_: Exception) {
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    val alive get() = binder.isBinderAlive
}

class TsRadio(private val ctx: Context) : RadioBackend {
    private val capsState = MutableStateFlow(emptySet<RadioCap>())
    override val capsFlow: StateFlow<Set<RadioCap>> = capsState.asStateFlow()
    override val tryable = true
    private val st = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = st.asStateFlow()
    private val running = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = running.asStateFlow()

    @Volatile private var link: TsLink? = null
    @Volatile private var conn: ServiceConnection? = null
    private var poll: Job? = null
    private val opening = AtomicBoolean(false)
    /** Counts the frequency reports read from the tuner; a state seen before a command can't confirm it. */
    private val reports = AtomicInteger()

    private fun read() {
        val l = link ?: return
        val f = tsNormFreq(l.call(TX_FREQ, 1)) ?: return
        reports.incrementAndGet()
        val band = if (f >= 8750) 65536 else 0
        if (st.value.freq != f || st.value.band != band) st.value = st.value.copy(freq = f, band = band)
    }

    private fun startPoll() {
        poll?.cancel()
        poll = audioScope.launch(Dispatchers.IO) { while (isActive && link?.alive == true) { read(); delay(500) } }
    }

    /** The service went away or never answered: the tuner is let go and the screen falls back to internet radio. */
    private fun drop() {
        link = null
        running.value = false
        capsState.value = emptySet()
        poll?.cancel()
        conn?.let { c -> runCatching { ctx.unbindService(c) } }
        conn = null
    }

    /** Binds the unit's main service and checks the fingerprint; only then the tuner is ours. At most ~8 s. */
    override suspend fun on() {
        if (link?.alive == true) { running.value = true; return }
        if (!opening.compareAndSet(false, true)) return
        try {
            val job = audioScope.async(Dispatchers.IO) { runCatching { open() }.onFailure { SsmTrace.log("ts: open failed: $it", keep = true) } }
            if (withTimeoutOrNull(8000) { job.await() } == null) SsmTrace.log("ts: no answer in 8 s", keep = true)
        } finally {
            opening.set(false)
        }
    }

    private suspend fun open() {
        val got = CompletableDeferred<IBinder?>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { got.complete(service) }
            override fun onServiceDisconnected(name: ComponentName?) { SsmTrace.log("ts: service gone", keep = true); drop() }
        }
        conn = c
        val intent = Intent("android.intent.action.MAIN_UI").setComponent(ComponentName("com.ts.MainUI", "com.ts.main.common.MainUI"))
        fun giveUp(why: String) { SsmTrace.log("ts: radio left alone: $why", keep = true); drop() }
        if (!ctx.bindService(intent, c, Context.BIND_AUTO_CREATE)) return giveUp("no bind")
        val b = withTimeoutOrNull(5000) { got.await() } ?: return giveUp("no binder")
        val d = runCatching { b.interfaceDescriptor }.getOrNull()
        SsmTrace.log("ts: binder descriptor $d", keep = true)
        val l = TsLink(b, d.orEmpty())
        val freq = if (d != null && TS_RADIO_INTERFACE in d) l.call(TX_FREQ, 1) else null
        if (!tsFingerprintOk(d, freq)) return giveUp("fingerprint $d freq=$freq")
        link = l
        l.call(TX_SOURCE, 1)
        read()
        if (tsNormFreq(st.value.freq) == null) return giveUp("no frequency report")
        capsState.value = setOf(RadioCap.TUNE, RadioCap.SEEK, RadioCap.BAND)
        running.value = true
        startPoll()
        SsmTrace.log("ts: radio fingerprint ok", keep = true)
    }

    override suspend fun off() {
        val l = link
        if (l != null) withContext(Dispatchers.IO) { l.call(TX_SOURCE, 0) }
        drop()
    }

    /** By the grid index (tx 34); true only when a frequency read from the tuner after the command is the one asked for. */
    override suspend fun tune(f: Int): Boolean = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext false
        if (!l.alive) return@withContext false
        val tol = tuneTolerance(f)
        val count = l.call(TX_GRID_COUNT) ?: return@withContext false
        val index = tsGridIndex(count, f) { i -> tsNormFreq(l.call(TX_GRID_VALUE, i)) ?: 0 }
        if (index == null) {
            SsmTrace.log("ts: $f isn't on the tuner's grid ($count steps)", keep = true)
            return@withContext false
        }
        val before = reports.get()
        l.call(TX_TUNE_INDEX, index)
        withTimeoutOrNull(2500) {
            while (true) { read(); if (readBackConfirms(f, st.value.freq.takeIf { it > 0 }, tol, before, reports.get())) break; delay(200) }
            true
        } == true
    }

    override fun seek(up: Boolean) {
        audioScope.launch(Dispatchers.IO) { runCatching { link?.call(TX_SEEK, if (up) 1 else 0) } }
    }

    override fun band(b: Int) {
        audioScope.launch(Dispatchers.IO) {
            runCatching {
                when {
                    b == -1 -> link?.call(TX_BAND_NEXT)
                    b == 0 || b == 1 || b == -2 -> link?.call(TX_AM)
                    else -> link?.call(TX_FM)
                }
            }
        }
    }
}
