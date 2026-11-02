package kg.forester.sg.ssm

/**
 * Standard OBD-II (SAE J1979), the fallback when the ECU does not answer Subaru's SSM: any car since the
 * 2000s speaks it, but it only has the basic parameters (no knock, no boost control, no transmission).
 */
interface ObdLink {
    val name: String
    /** CAN: up to 6 PIDs per mode 01 request, and DTC answers carry a count byte. */
    val isCan: Boolean
    /** Sends [req] (e.g. 01 0C) and returns the answer frames, each starting with the response code (41 0C …). */
    fun request(req: ByteArray): List<ByteArray>?
    /**
     * CAN 11-bit only: [req] to the unit at [tx], its answer from [rx] (manufacturer PIDs, mode 21/22, of another
     * unit than the engine). Null where the adapter can't or nobody answered.
     */
    fun requestTo(tx: Int, rx: Int, req: ByteArray): ByteArray? = null
    fun close()
}

/** OBD through an ELM327 with automatic protocol search: CAN, KWP2000 or ISO 9141, whatever the car has. */
class ElmObd(private val elm: Elm327) : ObdLink {
    private var protocol = ""
    override val name get() = "ELM327, OBD-II" + (PROTOCOLS[protocol]?.let { " ($it)" } ?: "")
    override val isCan get() = protocol in setOf("6", "7", "8", "9")

    /** Resets the SSM settings and lets the ELM find the car's protocol (up to ~10 s on the first request). */
    fun open(): Boolean {
        elm.cmd("ATD"); elm.cmd("ATE0"); elm.cmd("ATL0"); elm.cmd("ATS0"); elm.cmd("ATH0")
        elm.cmd("ATAT1"); elm.cmd("ATSP0")
        val r = runCatching { elm.cmd("0100", 15000) }.getOrNull() ?: return false
        if (Elm327.isError(r) || !r.filter { it.isLetterOrDigit() }.contains("4100")) return false
        protocol = runCatching { elm.cmd("ATDPN") }.getOrDefault("").trim().removePrefix("A")
        SsmTrace.log("OBD protocol $protocol")
        return true
    }

    /** The header [requestTo] set: the next plain request goes back to the functional 7DF. */
    private var header = -1

    override fun request(req: ByteArray): List<ByteArray>? {
        if (header >= 0) {
            runCatching { elm.cmd("ATSH7DF"); elm.cmd("ATAR") }
            header = -1
        }
        val r = runCatching { elm.cmd(Elm327.hex(req), 4000) }.getOrNull() ?: return null
        if (Elm327.isError(r)) return null
        return frames(r)?.filter { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }?.ifEmpty { null }
    }

    override fun requestTo(tx: Int, rx: Int, req: ByteArray): ByteArray? {
        if (protocol != "6" && protocol != "8") return null
        if (header != tx) {
            runCatching { elm.cmd("ATSH%03X".format(tx)); elm.cmd("ATCRA%03X".format(rx)) }.getOrElse { return null }
            header = tx
        }
        val r = runCatching { elm.cmd(Elm327.hex(req), 3000) }.getOrNull() ?: return null
        if (Elm327.isError(r)) return null
        return frames(r)?.firstOrNull { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }
    }

    /** The answer lines as frames; CAN multi-frame (ATCAF1) is a length line, then "0:…", "1:…". */
    private fun frames(r: String): List<ByteArray>? {
        val lines = r.split('\r', '\n').map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) }
        if (lines.any { it.length > 2 && it[1] == ':' }) {
            val len = lines.first().takeIf { !it.contains(':') }?.toIntOrNull(16) ?: return null
            val b = Elm327.bytes(lines.filter { it.contains(':') }.joinToString("") { it.substringAfter(':') })
            if (b.size < len) return null
            return listOf(b.copyOf(len))
        }
        return lines.map { Elm327.bytes(it) }
    }

    override fun close() {}

    companion object {
        private val PROTOCOLS = mapOf(
            "1" to "J1850 PWM", "2" to "J1850 VPW", "3" to "ISO 9141", "4" to "KWP 5 бод", "5" to "KWP быстрый",
            "6" to "CAN 11/500", "7" to "CAN 29/500", "8" to "CAN 11/250", "9" to "CAN 29/250",
        )
    }
}

/** OBD over a CAN transport that already does ISO-TP to the engine unit (7E0/7E8), e.g. the OpenPort 2.0. */
class CanObd(private val t: SsmTransport, adapter: String) : ObdLink {
    override val name = "$adapter, OBD-II (CAN)"
    override val isCan = true
    override fun request(req: ByteArray): List<ByteArray>? =
        t.exchange(SsmClient.ECU, req)?.takeIf { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }?.let { listOf(it) }

    override fun requestTo(tx: Int, rx: Int, req: ByteArray): ByteArray? =
        (t as? Op2CanTransport)?.exchangeId(tx, rx, req)?.takeIf { it.isNotEmpty() && it[0] == (req[0] + 0x40).toByte() }

    override fun close() = t.close()
}

class Obd2(val link: ObdLink) {
    /** Mode 01 PIDs the ECU says it has. */
    var supported: Set<Int> = emptySet()
        private set
    private val values = HashMap<Int, ByteArray>()
    private var multi = link.isCan
    private var slowIdx = 0

    /** Ids of the extra values (OBD_xx) ticked on «Датчики»: read every cycle, the rest not at all. */
    @Volatile var watched: Set<String> = emptySet()

    /** Reads the "PIDs supported" bitmaps (01 00, 01 20 … 01 C0). */
    fun init(): Boolean {
        val out = HashSet<Int>()
        var base = 0
        while (base <= 0xC0) {
            val a = link.request(byteArrayOf(0x01, base.toByte()))?.firstOrNull { it.size >= 6 && it[1] == base.toByte() } ?: break
            out += bitmap(a, 2, base)
            if (base + 0x20 !in out) break
            base += 0x20
        }
        supported = out
        SsmTrace.log("OBD PIDs: ${out.sorted().joinToString(" ") { "%02X".format(it) }}")
        return out.isNotEmpty()
    }

    /** The PIDs a 4-byte "supported" bitmap at [at] marks, counted from [base] + 1. */
    private fun bitmap(a: ByteArray, at: Int, base: Int): Set<Int> {
        if (a.size < at + 4) return emptySet()
        val bits = ((a[at].toLong() and 0xFF) shl 24) or ((a[at + 1].toLong() and 0xFF) shl 16) or
            ((a[at + 2].toLong() and 0xFF) shl 8) or (a[at + 3].toLong() and 0xFF)
        return (0 until 32).filter { bits shr (31 - it) and 1L == 1L }.map { base + it + 1 }.toSet()
    }

    /** How many of the parameters this app shows the car has. */
    val polledCount get() = (FAST + SLOW).count { it in supported } + extraParams().size

    /** The standard values beyond the gauges this ECU has, for «Датчики» and the log. */
    fun extraParams(): List<RrParam> = ObdPids.EXTRA.filter { it.pid in supported }.map { it.param() }

    /** The ticked extra values as last read. */
    fun extraValues(): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (v in ObdPids.EXTRA) {
            if (v.id !in watched) continue
            val d = values[v.pid] ?: continue
            v.f(d)?.takeIf { !it.isNaN() }?.let { out[v.id] = it }
        }
        return out
    }

    /** One poll: the fast parameters and the ticked extras every time, one of the slow ones in turn. Null when the ECU went silent. */
    fun poll(now: Long): EcuSnapshot? {
        val fast = fastPids(supported)
        val slow = SLOW.filter { it in supported }
        val extra = ObdPids.EXTRA.filter { it.id in watched && it.pid in supported }.map { it.pid }.distinct()
        val ask = (fast + listOfNotNull(slow.getOrNull(slowIdx++ % slow.size.coerceAtLeast(1))) + extra).distinct()
        var got = 0
        if (multi) {
            for (group in ask.chunked(6)) got += readMany(group)
            if (got == 0 && ask.isNotEmpty()) multi = false // ECU that only takes one PID per request
        }
        if (!multi) for (pid in ask) got += readMany(listOf(pid))
        if (got == 0) return null
        return decode(now)
    }

    private fun readMany(pids: List<Int>): Int {
        val frames = link.request(byteArrayOf(0x01) + pids.map { it.toByte() }) ?: return 0
        var n = 0
        for (f in frames) {
            var i = 1
            while (i < f.size) {
                val pid = f[i].toInt() and 0xFF
                val len = ObdPids.LEN[pid] ?: break
                if (i + 1 + len > f.size) break
                values[pid] = f.copyOfRange(i + 1, i + 1 + len)
                n++
                i += 1 + len
            }
        }
        return n
    }

    private fun decode(now: Long): EcuSnapshot {
        fun a(pid: Int) = values[pid]?.get(0)?.toInt()?.and(0xFF)
        fun ab(pid: Int) = values[pid]?.takeIf { it.size >= 2 }?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        return EcuSnapshot(
            timeMs = now,
            coolantC = a(0x05)?.let { it - 40 },
            rpm = ab(0x0C)?.let { it / 4 },
            speedKmh = a(0x0D),
            timingDeg = a(0x0E)?.let { it / 2f - 64f },
            intakeC = a(0x0F)?.let { it - 40 },
            mafGs = ab(0x10)?.let { it / 100f },
            throttlePct = a(0x11)?.let { it * 100f / 255f },
            batteryV = ab(0x42)?.let { it / 1000f },
            atmKpa = a(0x33)?.toFloat(),
            mapKpa = a(0x0B)?.toFloat(),
            afCorrPct = a(0x06)?.let { (it - 128) * 100f / 128f },
            afLearnPct = a(0x07)?.let { (it - 128) * 100f / 128f },
            lambda = (ab(0x34) ?: ab(0x24))?.takeIf { it != 0 }?.let { it * 2f / 65536f },
            pedalPct = a(0x49)?.let { it * 100f / 255f },
            fuelLevelPct = a(0x2F)?.let { it * 100f / 255f },
            fuelSystem = a(0x03),
            fuelRateLh = ab(0x5E)?.let { it / 20f },
            engineLoadPct = a(0x04)?.let { it * 100f / 255f },
        )
    }

    /** Stored (mode 03) and pending (mode 07) trouble codes; null when the ECU did not answer. */
    fun readDtcs(): Pair<List<String>, List<String>>? {
        val stored = codes(0x03) ?: return null
        return stored to (codes(0x07) ?: emptyList())
    }

    private fun codes(mode: Int): List<String>? {
        val frames = link.request(byteArrayOf(mode.toByte())) ?: return null
        val out = ArrayList<String>()
        for (f in frames) {
            // CAN: 43 <count> <pairs>; K-line/KWP: 43 <3 pairs, padded with 00 00> per frame
            val start = if (link.isCan) 2 else 1
            var i = start
            while (i + 1 < f.size) {
                val hi = f[i].toInt() and 0xFF
                val lo = f[i + 1].toInt() and 0xFF
                if (hi != 0 || lo != 0) out += dtc(hi, lo)
                i += 2
            }
        }
        return out.distinct()
    }

    /** Mode 04: clears the codes and the check engine light (also resets the readiness monitors). */
    fun clearDtcs(): Boolean = link.request(byteArrayOf(0x04)) != null

    /** Mode 01 PID 01: the check engine light, the number of stored codes and the readiness monitors. */
    fun readiness(): ObdPids.Readiness? {
        val f = link.request(byteArrayOf(0x01, 0x01))?.firstOrNull { it.size >= 6 && it[1] == 0x01.toByte() } ?: return null
        return ObdPids.readiness(f.copyOfRange(2, 6))
    }

    /** The values the ECU kept when the first stored code was set (mode 02, frame 0). */
    data class FreezeFrame(val dtc: String?, val values: List<Pair<String, String>>)

    fun freezeFrame(): FreezeFrame? {
        fun ask(pid: Int): ByteArray? = link.request(byteArrayOf(0x02, pid.toByte(), 0x00))
            ?.firstOrNull { it.size >= 3 && it[1] == pid.toByte() }
            // 42 <pid> <frame> <data>; some K-line units leave the frame number out
            ?.let { f -> if (f.size >= 3 + (ObdPids.LEN[pid] ?: 0)) f.copyOfRange(3, f.size) else f.copyOfRange(2, f.size) }
        val code = ask(0x02) ?: return null
        val dtc = if (code.size >= 2 && (code[0].toInt() != 0 || code[1].toInt() != 0)) dtc(code[0].toInt() and 0xFF, code[1].toInt() and 0xFF) else null
        if (dtc == null) return FreezeFrame(null, emptyList())
        val has = HashSet<Int>()
        var base = 0
        while (base <= 0x40) {
            val bm = link.request(byteArrayOf(0x02, base.toByte(), 0x00))?.firstOrNull { it.size >= 7 && it[1] == base.toByte() } ?: break
            has += bitmap(bm, 3, base)
            if (base + 0x20 !in has) break
            base += 0x20
        }
        // no bitmap from the unit: the PIDs it has live are the best guess
        val want = FREEZE.filter { if (has.isEmpty()) it in supported else it in has }
        val out = ArrayList<Pair<String, String>>()
        for (pid in want) {
            val d = ask(pid) ?: continue
            if (pid == 0x03) { out += "Топливная система" to ObdPids.fuelStatus(d[0].toInt() and 0xFF); continue }
            for (v in ObdPids.byPid(pid)) v.text(d)?.let { out += v.name to it }
        }
        return FreezeFrame(dtc, out)
    }

    /** Mode 06: the results of the last on-board tests (catalyst, O2 sensors, EGR, misfire…). Null when the unit has none. */
    fun testResults(): List<ObdPids.TestResult>? {
        val out = ArrayList<ObdPids.TestResult>()
        if (link.isCan) {
            val mids = HashSet<Int>()
            var base = 0
            while (base <= 0xE0) {
                val bm = link.request(byteArrayOf(0x06, base.toByte()))?.firstOrNull { it.size >= 6 && it[1] == base.toByte() } ?: break
                mids += bitmap(bm, 2, base)
                if (base + 0x20 !in mids) break
                base += 0x20
            }
            for (mid in mids.filter { it % 0x20 != 0 }.sorted()) {
                val f = link.request(byteArrayOf(0x06, mid.toByte()))?.firstOrNull() ?: continue
                out += ObdPids.testsCan(f)
            }
        } else {
            val tids = link.request(byteArrayOf(0x06, 0x00))?.firstOrNull { it.size >= 6 }?.let { bitmap(it, 2, 0) } ?: return null
            for (tid in tids.filter { it % 0x20 != 0 }.sorted()) {
                link.request(byteArrayOf(0x06, tid.toByte()))?.forEach { f -> ObdPids.testsKline(f)?.let { out += it } }
            }
        }
        return out.ifEmpty { null }
    }

    /** Mode 09: what the engine unit says about the car and itself. */
    data class VehicleInfo(val vin: String?, val calIds: List<String>, val cvns: List<String>, val ecuName: String?)

    fun vehicleInfo(): VehicleInfo {
        val vin = vinInfo()?.let { vinOf(it) }
        val cal = info(0x04)?.let { d -> d.toList().chunked(16).map { c -> ascii(c.toByteArray()) }.filter { it.isNotEmpty() } } ?: emptyList()
        val cvn = info(0x06)?.let { d -> d.toList().chunked(4).filter { it.size == 4 }.map { it.toByteArray().toHex() } } ?: emptyList()
        val name = info(0x0A)?.let { ascii(it) }?.ifEmpty { null }
        return VehicleInfo(vin, cal, cvn, name)
    }

    /** The unit's name (09 0A) and its first calibration id (09 04) in one line, null when it gives neither. */
    fun ecuIds(): String? {
        val name = info(0x0A)?.let { ascii(it) }?.ifEmpty { null }
        val cal = info(0x04)?.let { d -> d.toList().chunked(16).map { c -> ascii(c.toByteArray()) }.firstOrNull { it.isNotEmpty() } }
        return ecuLine(name, cal)
    }

    /** The VIN alone (mode 09 PID 02), read once at the start of a session. */
    fun vin(): String? = vinInfo()?.let { vinOf(it) }

    /** The raw VIN data: the frames of it are not logged (SsmTrace.muted), they spell the VIN out. */
    private fun vinInfo(): ByteArray? = SsmTrace.muted { info(0x02) }

    /**
     * Mode 09 data of [pid]: on CAN one answer 49 <pid> <count> <data>, before CAN several 49 <pid> <n> <4 bytes>
     * that go in the order of n.
     */
    private fun info(pid: Int): ByteArray? {
        val frames = link.request(byteArrayOf(0x09, pid.toByte()))?.filter { it.size >= 3 && it[1] == pid.toByte() } ?: return null
        if (frames.isEmpty()) return null
        return if (link.isCan) frames.first().copyOfRange(3, frames.first().size)
        else frames.distinctBy { it[2] }.sortedBy { it[2].toInt() and 0xFF }.flatMap { it.copyOfRange(3, it.size).toList() }.toByteArray()
    }

    companion object {
        /** «ECM-EngineControl · CALID 12345678», for Настройки; null with neither. */
        fun ecuLine(name: String?, calId: String?): String? =
            listOfNotNull(name?.trim()?.ifEmpty { null }, calId?.trim()?.ifEmpty { null }?.let { "CALID $it" }).joinToString(" · ").ifEmpty { null }

        /** Every cycle: what the gauges and the fuel calculation need. */
        private val FAST = listOf(0x0C, 0x0D, 0x10, 0x11, 0x0B, 0x0E, 0x06)

        /**
         * What is asked every cycle of a unit that has [supported]: [FAST], plus the fuel: its own fuel rate (PID 5E)
         * when it has one, else the fuel system status (PID 03) that tells the deceleration cut. Never a PID the unit
         * does not list: on K-line every PID is a request of its own.
         */
        fun fastPids(supported: Set<Int>): List<Int> =
            FAST.filter { it in supported } + listOfNotNull(if (0x5E in supported) 0x5E else 0x03.takeIf { it in supported })
        /** One per cycle in turn. */
        private val SLOW = listOf(0x05, 0x0F, 0x42, 0x33, 0x07, 0x2F, 0x49, 0x34, 0x24, 0x04)
        /** What the freeze frame is asked for (when the unit kept it). */
        private val FREEZE = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x1F, 0x2F, 0x33, 0x42, 0x43, 0x44, 0x45, 0x46, 0x49, 0x5C, 0x5E)

        /** Two DTC bytes as text: the top two bits are the letter (P, C, B, U). */
        fun dtc(hi: Int, lo: Int): String = "PCBU"[hi shr 6] + "%X%X%02X".format((hi shr 4) and 3, hi and 0xF, lo)

        /** Printable characters only (the padding zeros and spaces of mode 09 dropped at the ends). */
        fun ascii(b: ByteArray): String = b.map { (it.toInt() and 0xFF).toChar() }
            .joinToString("") { if (it in ' '..'~') it.toString() else " " }.trim().replace(Regex(" {2,}"), " ")

        /** A VIN out of mode 09 PID 02 data: the last 17 letters and digits (the K-line answer starts with three zeros). */
        fun vinOf(d: ByteArray): String? {
            val s = d.map { (it.toInt() and 0xFF).toChar() }.filter { it.isLetterOrDigit() && it.code < 128 }.joinToString("")
            return s.takeLast(17).takeIf { it.length == 17 }?.uppercase()
        }
    }
}
