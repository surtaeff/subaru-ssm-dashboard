package kg.forester.sg.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The last readings for the charts, filled by the service, read by the screen: fixed places filled round and round, so
 * a new reading copies nothing. A chart takes the whole log once when it opens, after that only what is new. Each
 * reading is stamped here from [clock], which never goes back: the wall clock in a reading's timeMs can (GPS, the user).
 */
class SampleLog<T : Any>(private val capacity: Int, private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val items = arrayOfNulls<Any>(capacity)
    private val stamps = LongArray(capacity)
    private var lastStamp = Long.MIN_VALUE
    private var head = 0
    private var size = 0

    /** Changes with each reading and when the log is emptied. */
    val version = MutableStateFlow(0L)

    /** How many times the log has been emptied. */
    @Volatile var clears = 0
        private set

    @Synchronized fun add(x: T) {
        lastStamp = maxOf(clock(), lastStamp + 1)
        items[head] = x
        stamps[head] = lastStamp
        head = (head + 1) % capacity
        if (size < capacity) size++
        version.value++
    }

    @Synchronized fun clear() {
        items.fill(null)
        head = 0
        size = 0
        lastStamp = Long.MIN_VALUE
        clears++
        version.value++
    }

    /** What came after [afterMs], oldest first, each with its stamp. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized fun since(afterMs: Long, f: (Long, T) -> Unit) {
        var i = size
        while (i > 0 && stamps[(head - i + capacity) % capacity] <= afterMs) i--
        while (i > 0) {
            val at = (head - i + capacity) % capacity
            f(stamps[at], items[at] as T)
            i--
        }
    }
}
