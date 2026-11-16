package kg.forester.sg.ssm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * «Воспроизведение»: a CAN log played back in its own time through what a live bus feeds ([CanBus.dispatch]) — the
 * Link's stream decoding, the monitor «Что идёт по CAN», the own channels and the «нажмите кнопку» capture — so the
 * sniffer, the bit viewer and new channels can be worked out at home. The screens say it is a replay ([state]); the
 * recorder leaves its frames out.
 */
object CanReplay {
    class Playing(val name: String, val frames: Int, val pos: Int, val loop: Boolean, val speed: Float, val round: Int)

    val state = MutableStateFlow<Playing?>(null)
    val active: Boolean get() = state.value != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Where the frames go: the same hub every adapter's session feeds. Replaced in tests. */
    var sink: (CanFrame, Long) -> Unit = { f, now -> CanBus.dispatch(f, now, link = true) }

    fun start(file: File, name: String = file.name, loop: Boolean = true, speed: Float = 1f) {
        stop()
        LinkDash.reset()
        CanMonitor.clear()
        state.value = Playing(name, 0, 0, loop, speed, 1)
        job = scope.launch {
            val total = CanLogs.summary(file).frames
            var round = 1
            try {
                do {
                    play(file) { pos -> state.value = Playing(name, total, pos, loop, speed, round) }
                    round++
                } while (loop && isActive && total > 0)
            } finally {
                state.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        state.value = null
    }

    /** One pass of [file] in its own timing (× [speed]); [progress] every few hundred frames. */
    private suspend fun play(file: File, speed: Float = state.value?.speed ?: 1f, progress: (Int) -> Unit) {
        file.bufferedReader().use { r ->
            var t0Log = Long.MIN_VALUE
            val t0 = System.currentTimeMillis()
            var n = 0
            var shift = 0L
            for (f in CanLogs.read(r)) {
                if (t0Log == Long.MIN_VALUE) t0Log = f.timeUs
                val due = t0 + shift + ((f.timeUs - t0Log) / 1000 / speed).toLong()
                var wait = due - System.currentTimeMillis()
                // a gap of hours in a log (two drives in one file) is no reason to sit still: on after a second
                if (wait > 5000) { shift -= wait - 1000; wait = 1000 }
                if (wait >= 3) delay(wait)
                sink(f, System.currentTimeMillis())
                if (++n % 200 == 0) progress(n)
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
            }
            progress(n)
        }
    }
}
