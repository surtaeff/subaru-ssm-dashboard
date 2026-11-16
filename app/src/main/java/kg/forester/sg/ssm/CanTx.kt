package kg.forester.sg.ssm

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one gate every frame the app puts on a CAN bus goes through. Off by default; when on, a frame leaves only if
 *
 * - its id (and kind: 11 or 29 bits) is on the driver's list, and is no diagnostic id (OBD requests 7DF, 7E0–7E7,
 *   the 29-bit 18DB33F1 / 18DA..F1);
 * - its bus is marked as the driver's own sensor bus or a comfort bus, never the car's powertrain, nor one not marked
 *   at all; a bus carrying a sports ECU's dash stream (the Link's) counts as powertrain whatever it is marked;
 * - it keeps to the rate limits, per id and in all;
 * - simulation is off (with it on, everything is checked and logged, nothing is written).
 *
 * Nothing car-specific is sent by the app itself: this is the infrastructure plus the manual «отправить кадр» tool for a
 * bench or a sensor bus. Every attempt, let through or not, is logged ([log]) and goes to the trace.
 */
object CanTx {
    enum class Role(val key: String, val title: String) {
        UNKNOWN("", "не указана"),
        POWERTRAIN("powertrain", "силовая (двигатель, АКПП, ABS, руль)"),
        COMFORT("comfort", "комфорт / кузов"),
        SENSOR("sensor", "своя шина датчиков");

        val mayTransmit: Boolean get() = this == COMFORT || this == SENSOR

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: UNKNOWN }
    }

    enum class Verdict(val ok: Boolean, val text: String) {
        SENT(true, "отправлен"),
        SIMULATED(true, "симуляция: проверен, не отправлен"),
        OFF(false, "передача выключена"),
        NOT_LISTED(false, "ID нет в списке разрешённых"),
        DIAGNOSTIC(false, "диагностический ID: запрещён всегда"),
        BAD_FRAME(false, "неверный кадр (ID или длина)"),
        BUS_NOT_MARKED(false, "шина не отмечена как своя или комфорт"),
        POWERTRAIN(false, "силовая шина: передача запрещена"),
        RATE(false, "превышен лимит частоты"),
        NO_ADAPTER(false, "нет адаптера, который умеет передавать"),
        FAILED(false, "адаптер не принял кадр"),
    }

    /** What the gate goes by: the settings of the moment. */
    data class Policy(
        val enabled: Boolean = false,
        val simulate: Boolean = true,
        /** Allowed ids by [key]. */
        val allowed: Set<Long> = emptySet(),
        val perIdPerSec: Int = 10,
        val totalPerSec: Int = 50,
        val roles: Map<Int, Role> = emptyMap(),
    ) {
        fun role(bus: Int) = roles[bus] ?: Role.UNKNOWN
    }

    /** One attempt, for the log on screen. */
    class Entry(val timeMs: Long, val frame: CanFrame, val verdict: Verdict, val via: String)

    /**
     * A one-frame pass from the gate to the adapter: the only way into [CanSource.transmit]. Made in this file only
     * (a test checks no other file makes one).
     */
    class Permit internal constructor(private val frame: CanFrame) {
        fun covers(f: CanFrame) = f === frame
    }

    fun key(id: Int, ext: Boolean): Long = (id.toLong() and 0x1FFFFFFFL) or (if (ext) 1L shl 32 else 0L)

    /** Diagnostic requests and answers: an OBD-II or UDS id never goes out of this gate. */
    fun diagnostic(id: Int, ext: Boolean): Boolean =
        if (!ext) id == 0x7DF || id in 0x7E0..0x7EF
        else (id and 0x1FFF0000) == 0x18DA0000 || (id and 0x1FFF0000) == 0x18DB0000

    /** The checks, apart from the adapter: pure, for the tests. Remembers what passed for the rate limits. */
    class Gate {
        private val sent = ArrayDeque<Pair<Long, Long>>() // time, key

        /** Null when [f] may go out now (or be simulated), else why not. [powertrainBus]: a bus seen to carry an engine ECU. */
        @Synchronized
        fun check(f: CanFrame, p: Policy, now: Long, powertrainBus: (Int) -> Boolean = { false }): Verdict? {
            if (!p.enabled) return Verdict.OFF
            if (!CanFrame.validId(f.id, f.ext) || f.data.size > (if (f.fd) CanFrame.MAX_FD else CanFrame.MAX_CLASSIC)) return Verdict.BAD_FRAME
            if (diagnostic(f.id, f.ext)) return Verdict.DIAGNOSTIC
            val k = key(f.id, f.ext)
            if (k !in p.allowed) return Verdict.NOT_LISTED
            val role = p.role(f.bus)
            if (role == Role.POWERTRAIN || powertrainBus(f.bus)) return Verdict.POWERTRAIN
            if (!role.mayTransmit) return Verdict.BUS_NOT_MARKED
            while (sent.isNotEmpty() && now - sent.first().first >= 1000) sent.removeFirst()
            if (sent.size >= p.totalPerSec || sent.count { it.second == k } >= p.perIdPerSec) return Verdict.RATE
            sent.addLast(now to k)
            return null
        }

        @Synchronized fun reset() = sent.clear()
    }

    private val gate = Gate()
    /** The newest attempts first, at most [LOG_SIZE]. */
    val log = MutableStateFlow<List<Entry>>(emptyList())
    const val LOG_SIZE = 200

    /** The adapter of the CAN session running now (set by the session, cleared when it ends). */
    @Volatile var source: CanSource? = null
        private set

    /** The policy of the moment; the settings put theirs here ([kg.forester.sg.data.CanConfig]). */
    @Volatile var policy: () -> Policy = { Policy() }

    fun attach(s: CanSource) { source = s; gate.reset() }
    fun detach(s: CanSource) { if (source === s) source = null }

    /** The bus carries a sports ECU's stream: its powertrain, whatever the driver marked. */
    private fun powertrain(bus: Int): Boolean = bus == 0 && LinkDash.id != null

    /** [f] through the gate and, if it passes and simulation is off, to the adapter. */
    fun send(f: CanFrame, now: Long = System.currentTimeMillis()): Verdict {
        val p = policy()
        val s = source
        val verdict = gate.check(f, p, now, ::powertrain) ?: when {
            p.simulate -> Verdict.SIMULATED
            s == null || !s.canTransmit -> Verdict.NO_ADAPTER
            f.bus >= s.buses -> Verdict.BAD_FRAME
            s.transmit(f, Permit(f)) -> Verdict.SENT
            else -> Verdict.FAILED
        }
        val via = s?.title ?: "—"
        log.value = (listOf(Entry(now, f, verdict, via)) + log.value).take(LOG_SIZE)
        SsmTrace.log("CAN TX ${verdict.name} via $via: $f")
        return verdict
    }

    fun clearLog() { log.value = emptyList() }
}
