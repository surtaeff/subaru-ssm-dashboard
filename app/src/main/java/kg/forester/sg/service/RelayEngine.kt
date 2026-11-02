package kg.forester.sg.service

import kg.forester.sg.data.Manual
import kg.forester.sg.data.OutChannel
import kg.forester.sg.data.OutEvent
import kg.forester.sg.data.OutRule
import kg.forester.sg.data.OutStatus
import kg.forester.sg.data.Relays
import kg.forester.sg.data.Repo
import kg.forester.sg.data.RuleKind
import kg.forester.sg.data.RuleSources
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.Threshold
import kg.forester.sg.data.LostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The outputs' rules, pure: [evaluate] gets the values through a function and the time as a number, so the tests run
 * whole drives through it. An output is on when any of its rules is (OR); a "never switch on if" rule beats them all,
 * a manual on/off beats the automatic ones.
 */
class RuleRunner {
    /** [since]: when the condition started to differ from [active] ([NONE] while it doesn't). */
    private class St(var active: Boolean = false, var since: Long = NONE)
    private val st = HashMap<String, St>()
    private val holds = HashMap<String, Long>()

    /** An event happened: the rules waiting for it hold their output on (for their time, or until the app stops). */
    fun fire(ev: OutEvent, now: Long, chans: List<OutChannel>) {
        for (c in chans) for (r in c.rules) if (r.enabled && r.kind == RuleKind.EVENT && r.event == ev)
            holds[r.id] = if (r.holdS > 0) now + r.holdS * 1000L else Long.MAX_VALUE
    }

    /** [t] holds for [v], with its own hysteresis: once on, it stays until the value crosses back over [Threshold.off]. */
    private fun holds(t: Threshold, v: Float, wasOn: Boolean) = if (t.above) (if (wasOn) v >= t.off else v >= t.on) else (if (wasOn) v <= t.off else v <= t.on)

    /** A LEVEL or BLOCK rule's state after this sample; a missing value counts as "on" only where losing it should switch on. */
    private fun step(r: OutRule, value: (String) -> Float?, now: Long, noDataOn: Boolean): Boolean {
        val s = st.getOrPut(r.id) { St() }
        val conds = listOfNotNull(r.main) + r.also
        if (conds.isEmpty()) return false
        val cond = conds.all { t -> value(t.src)?.let { holds(t, it, s.active) } ?: noDataOn }
        if (cond == s.active) { s.since = NONE; return s.active }
        if (s.since == NONE) s.since = now
        val wait = ((if (cond) r.onDelayS else r.offDelayS) * 1000).toLong()
        if (now - s.since >= wait) { s.active = cond; s.since = NONE }
        return s.active
    }

    fun pending(r: OutRule) = st[r.id]?.since?.let { it != NONE } == true

    fun evaluate(c: OutChannel, value: (String) -> Float?, now: Long, allOff: Boolean): OutStatus {
        if (allOff) return OutStatus(false, false, "Выключено кнопкой «Всё выключить»")
        for (r in c.rules) if (r.enabled && r.kind == RuleKind.BLOCK && step(r, value, now, false)) {
            val m = r.main!!
            return OutStatus(false, false, "Не включается: ${say(m.src)} ${value(m.src)?.let { num(m.src, it) } ?: "—"}, запрет ${if (m.above) "выше" else "ниже"} ${num(m.src, m.on)}")
        }
        when (c.manual) {
            Manual.ON -> return OutStatus(true, false, "Включено вручную")
            Manual.OFF -> return OutStatus(false, false, if (c.template == "button") "Выключено. Включается кнопкой" else "Выключено вручную")
            Manual.AUTO -> Unit
        }
        var on = false
        var blink = false
        var why = ""
        val noDataOn = c.lost == LostState.ON
        for (r in c.rules) {
            if (!r.enabled) continue
            when (r.kind) {
                RuleKind.LEVEL -> if (step(r, value, now, noDataOn)) {
                    on = true
                    if (r.blink) blink = true
                    val m = r.main!!
                    if (why.isEmpty() || r.blink) why = value(m.src)?.let { "Включено: ${say(m.src)} ${num(m.src, it)} (${if (m.above) "выше" else "ниже"} ${num(m.src, m.on)})" }
                        ?: "Включено: нет данных о ${say(m.src)}, по правилу «если связи нет» включаю"
                }
                RuleKind.EVENT -> holds[r.id]?.let { until -> if (now < until) { on = true; if (why.isEmpty()) why = "Включено: ${r.event?.title}" } }
                RuleKind.BLOCK -> Unit
            }
        }
        if (!on) {
            val first = c.rules.firstOrNull { it.enabled && it.kind == RuleKind.LEVEL }
            why = when {
                first != null && pending(first) -> "Условие выполнено, жду ${fmt(first.onDelayS)} с"
                first?.main != null -> {
                    val m = first.main
                    "Выключено: ${say(m.src)} ${value(m.src)?.let { num(m.src, it) } ?: "нет данных"}, включится ${if (m.above) "выше" else "ниже"} ${num(m.src, m.on)}"
                }
                else -> "Выключено"
            }
        }
        return OutStatus(on, blink, why)
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        fun say(src: String) = RuleSources.of(src)?.title?.lowercase(Locale("ru")) ?: src
        fun num(src: String, v: Float): String {
            val s = RuleSources.of(src)
            if (s?.bool == true) return if (v >= 0.5f) "включён" else "выключен"
            val d = s?.decimals ?: 1
            // a speed rule is kept in km/h, as the log and the engine read it, and said in the driver's unit
            if (src == "speed") return "%.0f".format(Locale("ru"), kg.forester.sg.data.DistanceUnits.speed(v)) + " " + kg.forester.sg.data.DistanceUnits.speedUnit
            return "%.${d}f".format(Locale("ru"), v) + (s?.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        }
        fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(Locale("ru"), v)
    }
}

/**
 * Runs the rules four times a second in the service and keeps the box in step: sets the channels up whenever the box
 * (re)connects, sends a change only when the computed state changes (and again every few seconds, in case a line was
 * lost), and keeps the box's failsafe armed with a heartbeat.
 */
object RelayEngine {
    private val runner = RuleRunner()
    private val sent = HashMap<Int, String>()
    private var session = -1
    private var configHash = 0
    private var configured = emptySet<Int>()
    private var lastResend = 0L
    private var lastBeat = 0L
    private val runEdge = RunEdge()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runner.fire(OutEvent.WOKE, System.currentTimeMillis(), Relays.channels.value)
            while (isActive) {
                runCatching { tick(System.currentTimeMillis()) }.onFailure { kg.forester.sg.ssm.SsmTrace.log("relays: $it") }
                delay(250)
            }
        }
    }

    /** A source's value now, or null when it is stale (ECU silent over 2 s) or not there. */
    fun value(id: String, now: Long = System.currentTimeMillis()): Float? {
        if (id.startsWith("box_")) return SensorBox.readings.value[id.removePrefix("box_")]?.value
        // another output: on 1, off 0, as the last round of the rules left it
        if (id.startsWith("out_")) return id.removePrefix("out_").toIntOrNull()?.let { Relays.status.value[it] }?.let { if (it.want) 1f else 0f }
        val e = Repo.live.value
        val fresh = now - e.timeMs < 2000 || Repo.demoOn.value
        if (!fresh) return null
        return when (id) {
            "coolant" -> e.coolantC?.toFloat()
            "oil" -> Repo.guard.value.oilC
            "intake" -> e.intakeC?.toFloat()
            "atf" -> Repo.tcu.value?.atfC?.toFloat()
            "rpm" -> e.rpm?.toFloat()
            "speed" -> e.speedKmh?.toFloat()
            "boost" -> e.gaugeKpa?.let { it / 100f }
            "afr" -> e.afr
            "throttle" -> e.throttlePct
            "battery" -> e.batteryV
            else -> null
        }
    }

    private fun tick(now: Long) {
        val chans = Relays.channels.value
        if (runEdge.engineOn(Repo.live.value.rpm, Repo.link.value == kg.forester.sg.data.LinkState.CONNECTED)) runner.fire(OutEvent.ENGINE_ON, now, chans)
        val allOff = Relays.allOff.value
        val box = Relays.boxStates.value
        Relays.status.value = chans.associate { c ->
            c.ch to runner.evaluate(c, { value(it, now) }, now, allOff).copy(box = box.getOrNull(c.ch - 1))
        }
        if (!SensorBoxLink.canDrive()) { session = -1; return }
        val hash = chans.map { it.setup() }.hashCode()
        if (SensorBoxLink.session != session || hash != configHash) {
            session = SensorBoxLink.session
            configHash = hash
            (configured - chans.map { it.ch }.toSet()).forEach { SensorBoxLink.send("C $it -") }
            chans.forEach { SensorBoxLink.send(it.setup()) }
            configured = chans.map { it.ch }.toSet()
            SensorBoxLink.send("H 3000")
            sent.clear()
        }
        val resend = now - lastResend > 3000
        if (resend) lastResend = now
        for (c in chans) {
            val s = Relays.status.value[c.ch] ?: continue
            val board = SensorBox.board.value
            val cmd = when {
                !s.want -> "O ${c.ch} 0"
                s.blink && c.load == kg.forester.sg.data.LoadKind.LED -> "B ${c.ch} 120 120"
                c.pwm(board) && c.levelSrc.isNotEmpty() -> {
                    // a real potentiometer on the box sets the level
                    val lv = (value(c.levelSrc, now) ?: c.level.toFloat()).coerceIn(0f, 100f).toInt()
                    if (lv != c.level) Relays.put(c.copy(level = lv))
                    "W ${c.ch} ${lv * 255 / 100}"
                }
                c.pwm(board) && c.level < 100 -> "W ${c.ch} ${c.level * 255 / 100}"
                else -> "O ${c.ch} 1"
            }
            if (sent[c.ch] != cmd || (resend && !cmd.startsWith("B"))) { SensorBoxLink.send(cmd); sent[c.ch] = cmd }
        }
        DisplayEngine.tick(now, session, ::value)
        if (now - lastBeat > 500) {
            // a box in failsafe while the cable stays in (the app stalled for a moment) stays there until armed again
            SensorBoxLink.send(if (Relays.boxFailsafe.value) "H 3000" else "H")
            lastBeat = now
        }
    }

    /** «Проверить 1 с»: one short pulse, the box's guards still apply. */
    fun test(ch: Int) = SensorBoxLink.send("P $ch 1000")

    fun panic(on: Boolean) {
        Relays.allOff.value = on
        if (on) SensorBoxLink.send("X")
        sent.clear()
    }
}

/**
 * The engine-start edge for the relays' ENGINE_ON event. A link that drops with the engine running leaves no rpm after
 * the wipe (see EcuService.linkLossWatch): that is no stop, and the reconnect is no start, or a K-line glitch in the
 * middle of a drive would switch the hardware on. The edge is forgotten only when the link is up and says the engine
 * is not running.
 */
class RunEdge {
    private var was = false

    fun engineOn(rpm: Int?, linked: Boolean): Boolean {
        if (!linked || rpm == null) return false
        val running = rpm > 400
        val edge = running && !was
        was = running
        return edge
    }
}
