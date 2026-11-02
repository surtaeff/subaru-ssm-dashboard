package kg.forester.sg.service

/** When the loaded voice model has been of no use long enough to give its memory back. */
class SpeechIdle {
    @Volatile private var last = 0L

    fun touch(now: Long) { last = now }

    /** [pressure]: the system is short of memory, sooner, but not right after a phrase: the next may follow. */
    fun due(now: Long, pressure: Boolean = false) = now - last >= if (pressure) PRESSURE_MS else IDLE_MS

    companion object {
        const val IDLE_MS = 60_000L
        const val PRESSURE_MS = 5_000L
    }
}

enum class SayRoute {
    /** By the model; if it is not in memory, the phrase waits for it to load. */
    MODEL,
    /** The model if it is ready within [SAY_WAIT_MS], else the head unit's own voice. */
    MODEL_OR_SYSTEM_AFTER_WAIT,
    /** The head unit's own voice, right now, the model not touched. */
    SYSTEM_NOW,
    /** A danger with nothing to say it right now: a beep at once, the phrase when the model has loaded. */
    BEEP_THEN_MODEL,
    /** The voice isn't downloaded: the caller has its own fallback. */
    CALLER,
}

/** How long an ordinary phrase waits for the chosen voice to load. */
const val SAY_WAIT_MS = 2000L

/**
 * How a phrase is said. The chosen voice is the normal one; a [danger] can't wait, it takes the head unit's voice
 * whenever that can speak. [preview]: the driver is trying this very voice in the settings.
 */
fun sayRoute(modelLoaded: Boolean, installed: Boolean, systemCanSpeak: Boolean, preview: Boolean, danger: Boolean) = when {
    danger && systemCanSpeak -> SayRoute.SYSTEM_NOW
    modelLoaded -> SayRoute.MODEL
    !installed -> SayRoute.CALLER
    danger -> SayRoute.BEEP_THEN_MODEL
    systemCanSpeak && !preview -> SayRoute.MODEL_OR_SYSTEM_AFTER_WAIT
    else -> SayRoute.MODEL
}

/** Phrases waiting for the model: first come, first said, an urgent one ahead of all but the urgent before it. */
class PhraseQueue<T : Any> {
    private val q = java.util.ArrayDeque<T>()
    private var urgentOnes = 0

    @Synchronized fun add(x: T, urgent: Boolean) {
        if (!urgent) { q.addLast(x); return }
        val rest = q.toList().drop(urgentOnes)
        repeat(rest.size) { q.removeLast() }
        q.addLast(x)
        rest.forEach { q.addLast(it) }
        urgentOnes++
    }

    @Synchronized fun poll(): T? {
        if (urgentOnes > 0) urgentOnes--
        return q.pollFirst()
    }
}

/** One phrase: whichever voice claims it says it, the other leaves it alone. */
class Phrase(
    val spoken: String, val urgent: Boolean,
    /** Lost altogether, it should at least beep. */
    val beepIfLost: Boolean = false,
    /** The head unit's voice takes it if the model hasn't by the end of the wait. */
    val systemWaiting: Boolean = false,
) {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
    val taken get() = claimed.get()
    fun claim() = claimed.compareAndSet(false, true)
}

/**
 * A phrase handed to the head unit's own voice, where speak() returning means only that it was queued: [onFail] once
 * if it turns out not to be said, [onEnd] once whatever happens.
 */
class Utterance(private val onEnd: () -> Unit, private val onFail: () -> Unit, val urgent: Boolean = false) {
    private var heardTalking = false
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var startedOk = false
        private set

    /**
     * What to do about a phrase the engine may say nothing about: some never call back. Seen talking is taken as said,
     * for this only; [startedOk] stays what the engine itself reported.
     */
    fun look(speaking: Boolean, elapsedMs: Long): UtteranceStep {
        if (speaking) heardTalking = true
        return utteranceStep(startedOk || heardTalking, speaking, elapsedMs)
    }

    fun start() { startedOk = true }
    fun done() { if (finished.compareAndSet(false, true)) onEnd() }
    fun fail() { if (finished.compareAndSet(false, true)) { onFail(); onEnd() } }
}

enum class UtteranceStep { WAIT, FAIL, END }

/** A look at a phrase after [elapsedMs]; [speaking]: the engine is talking, this phrase or one before it. */
fun utteranceStep(started: Boolean, speaking: Boolean, elapsedMs: Long) = when {
    speaking -> if (elapsedMs >= 60_000) UtteranceStep.END else UtteranceStep.WAIT
    started -> UtteranceStep.END
    elapsedMs >= SAY_WAIT_MS -> UtteranceStep.FAIL
    else -> UtteranceStep.WAIT
}

/** A danger cuts ordinary speech to get ahead of it; another danger waits its turn, both must be heard in full. */
fun systemFlushes(urgent: Boolean, dangerInAir: Boolean) = urgent && !dangerInAir

/**
 * The voices at work, the head unit's or the model's, from a phrase being taken to its end: music left paused is played
 * again only when the last is done. [end] says whether it was the last.
 */
class VoiceTurns {
    private var n = 0
    val busy: Boolean @Synchronized get() = n > 0

    @Synchronized fun begin() { n++ }

    @Synchronized fun end(): Boolean {
        if (n > 0) n--
        return n == 0
    }
}

/**
 * Whether a failed download of the voice goes again without being asked: switched on, failed, not going already, and no
 * more often than [everyMs] (a network that flaps must not start a download per flap).
 */
class SpeechRetry(private val everyMs: Long) {
    private var last = Long.MIN_VALUE / 2

    fun due(on: Boolean, failed: Boolean, busy: Boolean, nowMs: Long): Boolean {
        if (!on || !failed || busy) return false
        if (nowMs - last < everyMs) return false
        last = nowMs
        return true
    }
}
