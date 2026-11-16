package kg.forester.sg.ssm

import kg.forester.sg.data.CanBits
import kg.forester.sg.data.CanChannel
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10

/**
 * A signal of a CAN description (a DBC, a RealDash XML, a built-in profile) on its way to a [CanChannel]: where its
 * bits are, how the raw number becomes a value, what it is called.
 */
data class CanSig(
    val name: String,
    /** DBC start bit: Intel the lowest bit, Motorola the highest (see [CanBits]). */
    val startBit: Int,
    val bitLen: Int,
    val motorola: Boolean,
    val signed: Boolean,
    /** Over x, the raw number: "x*0.1-40". */
    val formula: String = "x",
    val units: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val comment: String = "",
    /** DBC mN: the signal is in the frame only when the message's multiplexor is N. */
    val muxValue: Int? = null,
    /** DBC M: this one is the multiplexor. */
    val isMuxer: Boolean = false,
    /** VAL_ / RealDash enum: what a raw number means. */
    val values: Map<Long, String> = emptyMap(),
    /** A RealDash value of whole bytes our byte model has (offset, 1/2/4 bytes): the channel keeps that form. */
    val byte: Int = -1,
    val len: Int = 0,
    /** Why it can't be read here (a float, a formula of another app…); null = it can. */
    val problem: String? = null,
)

data class CanMsg(
    val id: Int,
    val ext: Boolean,
    val name: String,
    val size: Int,
    val signals: List<CanSig>,
    val cycleMs: Int? = null,
    val comment: String = "",
) {
    val muxer: CanSig? get() = signals.firstOrNull { it.isMuxer }

    fun idText() = (if (ext) "%08X" else "%03X").format(id)
}

/** What a file describes: messages with their signals, the bus speed when it says, what was skipped and why. */
data class CanDb(val messages: List<CanMsg>, val kbit: Int? = null, val warnings: List<String> = emptyList()) {
    val signalCount get() = messages.sumOf { it.signals.count { s -> !s.isMuxer } }

    fun signal(msgId: Int, name: String): CanSig? = messages.firstOrNull { it.id == msgId }?.signals?.firstOrNull { it.name == name }
}

object CanImport {
    /** A number as a formula writes it: no exponent, no trailing zeros. */
    fun num(d: Double): String = BigDecimal(d.toString()).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    /** DBC's (factor, offset) over x. */
    fun linear(factor: Double, offset: Double): String {
        var f = "x"
        if (factor != 1.0) f += "*" + num(factor)
        if (offset > 0) f += "+" + num(offset) else if (offset < 0) f += "-" + num(-offset)
        return f
    }

    private val CELSIUS = setOf("c", "°c", "degc", "deg c", "deg_c", "cel", "celsius")
    private val KELVIN = setOf("k", "kelvin")
    private val FAHRENHEIT = setOf("f", "°f", "degf", "deg f", "deg_f", "fahrenheit")

    /**
     * The units as the app shows them and the formula that gives them: kelvins and Fahrenheit become °C (the
     * roles and the guard read Celsius), the rest keeps its number under a Russian name.
     */
    fun normalize(units: String, formula: String): Pair<String, String> {
        val u = units.trim()
        val l = u.lowercase()
        return when {
            l in CELSIUS -> "°C" to formula
            l in KELVIN -> "°C" to "($formula)-273.15"
            l in FAHRENHEIT -> "°C" to "(($formula)-32)/1.8"
            l == "kpa" -> "кПа" to formula
            l == "bar" -> "бар" to formula
            l == "psi" -> "psi" to formula
            l == "v" || l == "volt" || l == "volts" -> "В" to formula
            l == "km/h" || l == "kmh" || l == "kph" -> "км/ч" to formula
            l == "rpm" -> "об/мин" to formula
            l == "deg" || l == "degree" || l == "degrees" -> "°" to formula
            l == "ms" -> "мс" to formula
            l == "lambda" || l == "lam" -> "λ" to formula
            l == "bit" || l == "on/off" -> "" to formula
            else -> u to formula
        }
    }

    /** Digits after the point the step of [formula] needs (0.1 → 1, 0.0078 → 3). */
    fun decimals(formula: String): Int {
        val e = RrExpr.parse(formula) ?: return 1
        val a = e.eval(0.0) { _, _ -> null } ?: return 1
        val b = e.eval(1.0) { _, _ -> null } ?: return 1
        val step = abs(b - a)
        if (step == 0.0 || step >= 1.0 && abs(step - Math.rint(step)) < 1e-9 && abs(a - Math.rint(a)) < 1e-9) return 0
        return ceil(-log10(step) - 1e-9).toInt().coerceIn(0, 3)
    }

    private fun has(n: String, vararg w: String) = w.any { it in n }

    private val PRESSURE = setOf("кпа", "бар", "psi", "kpa", "bar")

    /**
     * The SensorBox role a signal plainly is, by its name, comment and units; "" when it is not plain (a target, a
     * limit, a warning light, lambda that the AFR role would read as 14.7-based).
     */
    fun guessRole(name: String, units: String, comment: String = ""): String {
        val n = (name + " " + comment).lowercase().replace('_', ' ')
        val u = units.lowercase().trim()
        if (has(n, "target", "limit", "warn", "light", "switch", "status", "error", "fault", "diff", "setpoint", "aim", "valid")) return ""
        val temp = u == "°c"
        val press = u in PRESSURE
        return when {
            temp && has(n, "egt", "exhaust gas", "exhaustgas", "exhaust temp", "exhausttemp") -> "egt"
            temp && has(n, "oil") && has(n, "temp", "oilt") && !has(n, "trans", "gearbox", "diff") -> "oil"
            press && has(n, "oil") && has(n, "press", "oilp") -> "oilP"
            press && has(n, "fuel") && has(n, "press", "fuelp") -> "fuelP"
            press && has(n, "exhaust", "back press", "emap") -> "emap"
            u == "%" && has(n, "ethanol", "flex", "fuel composition", "e85") -> "eth"
            u == "afr" && !has(n, "target") -> "afr"
            else -> ""
        }
    }

    /**
     * [s] of [m] as an own channel [id]. Whole bytes stay in the byte model the form edits (first byte, 1/2/4 bytes);
     * the rest becomes a bit-level channel. A multiplexed signal takes its multiplexor's place and value.
     */
    fun channel(
        m: CanMsg, s: CanSig, id: String, name: String = s.name, role: String? = null,
        formula: String? = null, units: String? = null, decimals: Int? = null,
    ): CanChannel {
        val (u, f) = if (formula != null || units != null) (units ?: s.units) to (formula ?: s.formula) else normalize(s.units, s.formula)
        val muxer = m.muxer.takeIf { s.muxValue != null }
        val byteMux = muxer != null && muxer.bitLen == 8 && muxer.startBit == if (muxer.motorola) 7 else 0
        var byte: Int
        var len: Int
        var be = s.motorola
        var bits = false
        when {
            s.byte >= 0 && s.len in setOf(1, 2, 4) -> { byte = s.byte; len = s.len }
            !s.motorola && s.startBit % 8 == 0 && s.bitLen in setOf(8, 16, 32) -> { byte = s.startBit / 8; len = s.bitLen / 8 }
            s.motorola && s.startBit % 8 == 7 && s.bitLen in setOf(8, 16, 32) -> { byte = s.startBit / 8; len = s.bitLen / 8 }
            else -> {
                bits = true
                val span = CanBits.byteSpan(s.startBit, s.bitLen, s.motorola) ?: 0..0
                byte = span.first
                len = when (span.last - span.first + 1) { 1 -> 1; 2 -> 2; else -> 4 }
            }
        }
        // 8 bits have no byte order: the form shows them as one byte
        if (!bits && len == 1) be = false
        val r = role ?: guessRole(name, u, s.comment)
        return CanChannel(
            id, name.trim(), m.id, m.ext, s.muxValue ?: -1, byte, len, be, s.signed, f, u, decimals ?: decimals(f), r,
            startBit = if (bits) s.startBit else -1, bitLen = if (bits) s.bitLen else 0,
            muxStart = if (muxer != null && !byteMux) muxer.startBit else -1,
            muxLen = if (muxer != null && !byteMux) muxer.bitLen else 8,
            muxBe = muxer != null && !byteMux && muxer.motorola,
        )
    }

    /** A file's text as a description: RealDash XML by its root, else DBC. */
    fun parse(text: String): CanDb =
        if (text.trimStart().startsWith("<")) RealDashXml.parse(text) else Dbc.parse(text)
}
