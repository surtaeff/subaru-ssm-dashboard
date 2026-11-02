package kg.forester.sg.service

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

enum class GateEvent {
    /** Nothing for the recogniser. */
    SILENT,
    /** Speech began: the pre-roll and this chunk went out. */
    OPENED,
    /** This chunk went out. */
    OPEN,
    /** Silence long enough: nothing more goes out, the recogniser should finish what it has. */
    CLOSED,
}

/**
 * Keeps silence from the recogniser (the big part of the voice's CPU), not speech. Listening to a parked car costs as
 * much as listening to a phrase otherwise. Has not met a real voice in a real cabin: there is a switch in the settings.
 *
 * It decides on the signal with its low frequencies taken down (pre-emphasis, a first difference: -24 dB at 100 Hz,
 * -8 dB at 1 kHz): road rumble is low and carries most of a moving car's loudness, a voice lives above it.
 *
 * The threshold follows the cabin: a fixed one is wrong by 30 dB between a parked car and a motorway with the window
 * down. The noise floor drops quickly to anything quieter and creeps up to anything steady, so a change in the noise
 * is learnt in seconds and the pauses between words keep speech from counting as noise. Anything [RISE_DB] over the
 * floor counts as voice; that is low on purpose, a gate opened by a bump only costs CPU, one that stays shut on a
 * quiet word costs the wake word. Over [MAX_THRESHOLD_DB] the cabin is too loud to tell a voice by loudness, and the
 * gate stays open as if it wasn't there.
 *
 * The start of a word is quiet («ф» in «форестер») and comes before the loudness that opens the gate, so the last
 * [PRE_ROLL_MS] are always kept and go out first. The gate stays open [HOLD_MS] after the last loud chunk, and closes
 * with [TAIL_MS] of silence: the recogniser finds the end of the phrase by itself, without being reset to start cold.
 */
class RmsGate(private val rate: Int = 16000) {
    private val ring = ShortArray(rate * PRE_ROLL_MS / 1000)
    private val tail = ShortArray(rate * TAIL_MS / 1000)
    private var ringLen = 0
    private var ringEnd = 0
    private var floorDb = Double.NaN
    private var holdMs = 0.0
    private var open = false
    private var lastSample = 0.0

    var opened = 0
        private set
    var closed = 0
        private set

    /** Audio for the recogniser goes through [emit]. */
    fun feed(chunk: ShortArray, n: Int, emit: (ShortArray, Int) -> Unit): GateEvent {
        val db = 20 * log10(highPassedRms(chunk, n).coerceAtLeast(1e-5))
        val sec = n.toDouble() / rate
        if (floorDb.isNaN()) floorDb = db
        val loud = db > min(floorDb + RISE_DB, MAX_THRESHOLD_DB)
        val tau = if (db < floorDb) FALL_SEC else if (open || loud) RISE_OPEN_SEC else RISE_SEC
        floorDb += (db - floorDb) * (1 - exp(-sec / tau))
        if (loud) holdMs = HOLD_MS else holdMs -= sec * 1000
        return when {
            open && holdMs > 0 -> { emit(chunk, n); GateEvent.OPEN }
            open -> {
                open = false
                closed++
                remember(chunk, n)
                emit(tail, tail.size)
                GateEvent.CLOSED
            }
            loud -> {
                open = true
                opened++
                emitPreRoll(emit)
                emit(chunk, n)
                GateEvent.OPENED
            }
            else -> { remember(chunk, n); GateEvent.SILENT }
        }
    }

    private fun highPassedRms(chunk: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val x = chunk[i].toDouble()
            val y = x - PRE_EMPHASIS * lastSample
            lastSample = x
            sum += y * y
        }
        return sqrt(sum / n) / 32768.0
    }

    private fun remember(chunk: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[ringEnd] = chunk[i]
            ringEnd = (ringEnd + 1) % ring.size
        }
        ringLen = min(ring.size, ringLen + n)
    }

    private fun emitPreRoll(emit: (ShortArray, Int) -> Unit) {
        if (ringLen == 0) return
        val start = (ringEnd - ringLen + ring.size) % ring.size
        emit(ShortArray(ringLen) { ring[(start + it) % ring.size] }, ringLen)
        ringLen = 0
    }

    companion object {
        const val PRE_ROLL_MS = 300
        const val HOLD_MS = 1500.0
        const val TAIL_MS = 400
        const val RISE_DB = 4.0
        /** On the high-passed signal: some 10 dB lower than the plain loudness of a voice. */
        const val MAX_THRESHOLD_DB = -36.0
        private const val PRE_EMPHASIS = 0.95
        private const val FALL_SEC = 0.4
        private const val RISE_SEC = 2.5
        private const val RISE_OPEN_SEC = 10.0
    }
}

/** What the gate did, for the sent log: at most a line a minute, none if nothing happened. */
class GateLog(start: Long = 0) {
    private var last = start
    private var said = 0 to 0

    fun line(now: Long, opened: Int, closed: Int): String? {
        if (now - last < 60_000 || (opened to closed) == said) return null
        last = now
        said = opened to closed
        return "voice gate: opened $opened, closed $closed"
    }
}

/** Whether a start still on its way may go on: a stop stops it, unless a start came after that stop. */
class VoiceRun {
    @Volatile private var stopRequested = false

    fun started() { stopRequested = false }
    fun stopped() { stopRequested = true }
    fun mayListen(enabled: Boolean) = enabled && !stopRequested
}

/**
 * A native model that several users share and that is closed when the last of them is done. A user lets go only when
 * it has stopped calling into the model, so closing the voice in the middle of a phrase can't pull the model from
 * under a running recognition, even if the capture thread outlives its stop by seconds or a restart comes meanwhile.
 */
class NativeLease<T : Any>(private val open: () -> T, private val close: (T) -> Unit) {
    private var value: T? = null
    private var users = 0

    val held: Boolean @Synchronized get() = value != null

    @Synchronized fun acquire(): T {
        val v = value ?: open().also { value = it }
        users++
        return v
    }

    @Synchronized fun release() {
        if (users == 0 || --users > 0) return
        val v = value
        value = null
        if (v != null) runCatching { close(v) }
    }
}
