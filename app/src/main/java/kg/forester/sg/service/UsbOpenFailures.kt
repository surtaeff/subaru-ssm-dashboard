package kg.forester.sg.service

/**
 * A USB serial chip that is listed but will not open, pass after pass. Roman's FT232R KKL cable on a ums512 unit
 * (beta12 log of 2026-11-20): a write timed out as the unit's hub took a new device (3322:6655), and from then on the
 * chip answered no control request. usb-serial's open() gave «Reset failed: result=-1» after its 5 s timeout, and the
 * pollLoop made the identical attempt every 6 s for 9 minutes: a plain IOException, so the write-timeout streak that
 * leads to the bus reset (see EcuService.writeTimeoutStreak) never grew and nothing else was ever tried.
 *
 * Skipping usb-serial's reset request would not help: the baud rate and the DTR/RTS lines are set by control requests
 * too, and a chip mute on EP0 refuses those as well. A bus reset is what brings such a chip back without a re-plug, so:
 * the first failure is retried as before (a device that has only just appeared), the second and then every third ends
 * with a bus reset on a fresh connection (usb-serial's failed open() has closed ours), and the pause between attempts
 * grows to [MAX_PAUSE_MS] instead of a tight loop. [opened] starts over.
 *
 * The streak belongs to one device path. Cranking drops the USB on many head units: the cable goes away ([gone]) and
 * comes back under a new /dev/bus/usb name, and its first opens right after enumeration may fail by themselves. That
 * new device starts from 0, and one seen less than [FRESH_MS] ago is retried, never reset: a reset then only drops it
 * again (and re-enumeration brings the USB-attached intent, which can bring the activity up).
 */
class UsbOpenFailures {
    enum class Step { RETRY, BUS_RESET }

    /** Failed opens in a row. */
    var streak = 0
        private set
    private var device: String? = null
    private var seenAtMs = 0L
    /** Bus resets since the last open that worked: a reset that re-enumerates the chip looks like a new device ([gone], a new path), so the streak alone never ends them. */
    private var resets = 0

    /** A failed open of [device] (its deviceName) at [nowMs] (elapsedRealtime); without them the same device, not new. */
    fun failed(device: String? = null, nowMs: Long? = null): Step {
        if (device != null && device != this.device) {
            this.device = device; streak = 0; seenAtMs = nowMs ?: 0L
        }
        streak++
        val fresh = nowMs != null && nowMs - seenAtMs < FRESH_MS
        val due = streak >= BUS_RESET_FROM && (streak - BUS_RESET_FROM) % BUS_RESET_EVERY == 0
        if (!due || fresh || resets >= MAX_RESETS) return Step.RETRY
        resets++
        return Step.BUS_RESET
    }

    fun opened() { streak = 0; resets = 0 }

    /** Nothing on USB: whatever comes next is a new device. */
    fun gone() { streak = 0; device = null }

    /** The pause before the next attempt; after a bus reset at least the time a device takes to come back. */
    fun pauseMs(step: Step): Long {
        val grown = minOf(MAX_PAUSE_MS, 1000L shl (streak - 1).coerceIn(0, 15))
        return if (step == Step.BUS_RESET) maxOf(grown, BUS_RESET_PAUSE_MS) else grown
    }

    companion object {
        const val BUS_RESET_FROM = 2
        const val BUS_RESET_EVERY = 3
        /** Resets that did not bring one working open: no more of them (each may cost a permission dialog), plain retries. */
        const val MAX_RESETS = 3
        const val BUS_RESET_PAUSE_MS = 3000L
        const val MAX_PAUSE_MS = 30_000L
        /** A device this new is retried, not reset. */
        const val FRESH_MS = 5000L
    }
}

/**
 * USB losses blamed on a ROM's long requests or stream start: two strikes in one service run, not one. A single loss
 * in those windows is what cranking does on many head units (ACC on, ECU init, then the starter drops the USB a few
 * seconds later), and one strike used to cost the whole drive its fast poll and long reads. A cable that really fails
 * on long frames (Alexey's FT232RL, the Xiaomi's stream start) does it on every reconnect, so the second comes at once.
 */
class UsbBlame(private val withinMs: Long = WITHIN_MS) {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * One more loss for [rom] at [nowMs] (elapsedRealtime); true when the one before it was less than [withinMs] ago.
     * Two engine starts of one service run (a head unit that sleeps keeps the process for days) are no two strikes.
     */
    fun strike(rom: String, nowMs: Long): Boolean {
        val prev = last.put(rom, nowMs)
        return prev != null && nowMs - prev in 0 until withinMs
    }

    fun clear() = last.clear()

    companion object {
        /** A cable that fails on long frames loses the USB again seconds after each reconnect. */
        const val WITHIN_MS = 120_000L
    }
}
