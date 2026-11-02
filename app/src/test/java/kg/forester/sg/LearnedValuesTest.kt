package kg.forester.sg

import kg.forester.sg.service.FuelCalc
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.LearnedValues
import kg.forester.sg.ssm.RrExpr
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LearnedValuesTest {
    /** An ECU that answers A8 reads from a memory map (unset bytes read 0), in chunks like a K-line cable. */
    private class FakeEcu(val mem: Map<Int, Int>, val limit: Int = Int.MAX_VALUE, val silent: (Int) -> Boolean = { false },
                          override val maxReadAddrs: Int = 64, val tick: () -> Unit = {}) : SsmTransport {
        override val name = "test"
        override val isCan = false
        var reads = 0
        override fun exchange(dest: Int, body: ByteArray): ByteArray? {
            if (body[0] != 0xA8.toByte()) return null
            reads++
            tick()
            val n = (body.size - 2) / 3
            if (n > limit) return null
            for (i in 0 until n) {
                val o = 2 + i * 3
                if (silent(((body[o].toInt() and 0xFF) shl 16) or ((body[o + 1].toInt() and 0xFF) shl 8) or (body[o + 2].toInt() and 0xFF))) return null
            }
            return ByteArray(1 + n) { i ->
                if (i == 0) 0xE8.toByte() else {
                    val o = 2 + (i - 1) * 3
                    val a = ((body[o].toInt() and 0xFF) shl 16) or ((body[o + 1].toInt() and 0xFF) shl 8) or (body[o + 2].toInt() and 0xFF)
                    (mem[a] ?: 0).toByte()
                }
            }
        }
        override fun close() {}
    }

    private fun MutableMap<Int, Int>.float(addr: Int, v: Float) {
        val b = java.lang.Float.floatToIntBits(v)
        for (k in 0 until 4) this[addr + k] = (b shr (24 - 8 * k)) and 0xFF
    }

    private fun param(id: String, addr: Int, len: Int, storage: String, expr: String) = RrParam(
        id = id, kind = RrParam.Kind.EXT, target = 1, name = id, nameEn = id, desc = "", addr = addr, len = len, bit = -1,
        units = "", format = "0.00", storage = storage, expr = RrExpr.parse(expr), deps = emptyList(), conversions = emptyMap(),
    )

    private val init = EcuInit("A00000", "4B04505207", ByteArray(0))

    /** A2UI000A (32-bit): addresses from the RomRaider logger and SubaruDefs, the FLKC table 0x14 after IAM. */
    @Test fun reads32bitTable() {
        val iam = 0xFF2538
        val mem = HashMap<Int, Int>()
        mem.float(iam, 0.875f)
        val trims = listOf(-5.0f, -4.5f, -1.2f, -2.9f)
        val trimAddrs = listOf(0xFF24B4, 0xFF24BC, 0xFF24C4, 0xFF24CC)
        trimAddrs.forEachIndexed { i, a -> mem.float(a, trims[i] / 100f) }
        listOf(5.6f, 9.0f, 40.0f).forEachIndexed { i, v -> mem.float(806548 + i * 4, v) }
        listOf(1.15f, 1.5f, 1.9f, 4.0f).forEachIndexed { i, v -> mem.float(821924 + i * 4, v) }
        listOf(2000f, 2800f, 3600f, 4400f, 5200f, 6000f).forEachIndexed { i, v -> mem.float(821940 + i * 4, v) }
        // row 3 (3600 rpm), column 3 (1.90 g/rev): cell 18; each float is followed by its check copy
        mem.float(iam + 0x14 + 18 * 8, -1.05f)
        mem.float(iam + 0x14 + 18 * 8 + 4, 1.05f) // the copy must not be taken for the next cell
        val params = mapOf(
            "E31" to param("E31", iam, 4, "float", "x"),
            "E44" to param("E44", trimAddrs[0], 4, "float", "x*100"),
            "E45" to param("E45", trimAddrs[1], 4, "float", "x*100"),
            "E46" to param("E46", trimAddrs[2], 4, "float", "x*100"),
            "E47" to param("E47", trimAddrs[3], 4, "float", "x*100"),
        )
        val axes = LearnedValues.Axes(
            "A2UI000A", 32,
            LearnedValues.Axis(806548, 3, "float", 1.0), LearnedValues.Axis(821940, 6, "float", 1.0),
            LearnedValues.Axis(821924, 4, "float", 1.0),
        )
        val r = LearnedValues.read(SsmClient(FakeEcu(mem)), init, params, axes)
        assertEquals(0.875, r.iam!!, 1e-6)
        assertEquals(listOf(0.0, 5.6, 9.0, 40.0), r.afStarts!!.map { Math.round(it * 100) / 100.0 })
        assertEquals(1, r.afBanks.size)
        r.afBanks[0].forEachIndexed { i, v -> assertEquals(trims[i].toDouble(), v!!, 1e-4) }
        assertEquals(listOf(0.0, 2000.0, 2800.0, 3600.0, 4400.0, 5200.0, 6000.0), r.rpmStarts)
        val t = assertNotNull(r.flkc).let { r.flkc!! }
        assertEquals(7, t.size)
        assertEquals(5, t[0].size)
        assertEquals(-1.05, t[3][3], 1e-6)
        assertEquals(0.0, t[3][4], 0.0)
        assertEquals(1, t.flatten().count { it != 0.0 })
    }

    /** A 16-bit ECU: IAM is a raw byte (16 = full), the table a byte per cell 2 bytes after it. */
    @Test fun reads16bitTable() {
        val iam = 0x2010
        val mem = HashMap<Int, Int>()
        mem[iam] = 16
        for (i in 0 until 7 * 8) mem[iam + 2 + i] = 128 // 128 × 0.3515625 − 45 = 0
        mem[iam + 2 + 9] = 125 // row 1, column 1: −1.05°
        for (i in 0 until 7) { val v = ((i + 1) * 1000 / 0.1953125).toInt(); mem[0x3000 + i * 2] = v shr 8; mem[0x3001 + i * 2] = v and 0xFF }
        for (i in 0 until 7) { val v = ((i + 1) * 0.5 / 6.103515625e-05).toInt(); mem[0x4000 + i * 2] = v shr 8; mem[0x4001 + i * 2] = v and 0xFF }
        val params = mapOf(
            "E1" to param("E1", iam, 1, "", "x"),
            "E12" to param("E12", 0x2100, 1, "", "(x*.3515625)-45"),
        )
        val axes = LearnedValues.Axes("TEST16", 16, null,
            LearnedValues.Axis(0x3000, 7, "uint16", 0.1953125), LearnedValues.Axis(0x4000, 7, "uint16", 6.103515625e-05))
        val r = LearnedValues.read(SsmClient(FakeEcu(mem)), init, params, axes)
        assertEquals(1.0, r.iam!!, 1e-9)
        val t = r.flkc!!
        assertEquals(8, t.size)
        assertEquals(8, t[0].size)
        assertEquals(-1.0546875, t[1][1], 1e-9)
        assertEquals(0.0, t[0][0], 1e-9)
        assertEquals(1000.0, r.rpmStarts!![1], 0.2)
    }

    /** No MAF (an early ECU answering 0xFFFF there): the fuel comes from the injectors. */
    @Test fun fuelWithoutMaf() {
        val lh = FuelCalc.litersPerHour(EcuSnapshot(rpm = 700, injPwMs = 2.6f), 1.0)
        assertEquals(0.73, lh, 0.05) // NA flow by default: 700 rpm × 2.0 ms × 260 cc/min
        assertEquals("inj", FuelCalc.source)
        assertEquals(0.0, FuelCalc.litersPerHour(EcuSnapshot(rpm = 700), 1.0), 0.0)
        assertEquals(0.0, FuelCalc.litersPerHour(EcuSnapshot(rpm = 700, injPwMs = 65.28f), 1.0), 0.0) // 0xFF
    }

    /** A ROM that lists E173: the FLKC table starts there, not 0x14 after IAM. */
    @Test fun flkcStartFromE173WhenPresent() {
        val iam = 0xFF2538
        val flkcAt = iam - 512
        val mem = HashMap<Int, Int>()
        mem.float(iam, 0.875f)
        listOf(2000f, 2800f, 3600f, 4400f, 5200f, 6000f).forEachIndexed { i, v -> mem.float(821940 + i * 4, v) }
        listOf(1.15f, 1.5f, 1.9f, 4.0f).forEachIndexed { i, v -> mem.float(821924 + i * 4, v) }
        mem.float(flkcAt + 18 * 8, -2.0f)
        mem.float(iam + 0x14 + 18 * 8, -9.0f) // the old guess must not be read
        val params = mapOf(
            "E31" to param("E31", iam, 4, "float", "x"),
            "E173" to param("E173", flkcAt, 4, "float", "x"),
        )
        val axes = LearnedValues.Axes(
            "X", 32, null, LearnedValues.Axis(821940, 6, "float", 1.0), LearnedValues.Axis(821924, 4, "float", 1.0),
        )
        val t = LearnedValues.read(SsmClient(FakeEcu(mem)), init, params, axes).flkc!!
        assertEquals(-2.0, t[3][3], 1e-6)
        assertEquals(1, t.flatten().count { it != 0.0 })
    }

    private fun table16(): Triple<Map<Int, Int>, Map<String, RrParam>, LearnedValues.Axes> {
        val iam = 0x2010
        val mem = HashMap<Int, Int>()
        mem[iam] = 16
        for (i in 0 until 8 * 8) mem[iam + 2 + i] = 128
        mem[iam + 2 + 9] = 125
        for (i in 0 until 7) { val v = ((i + 1) * 1000 / 0.1953125).toInt(); mem[0x3000 + i * 2] = v shr 8; mem[0x3001 + i * 2] = v and 0xFF }
        for (i in 0 until 7) { val v = ((i + 1) * 0.5 / 6.103515625e-05).toInt(); mem[0x4000 + i * 2] = v shr 8; mem[0x4001 + i * 2] = v and 0xFF }
        val params = mapOf("E1" to param("E1", iam, 1, "", "x"), "E12" to param("E12", 0x2100, 1, "", "(x*.3515625)-45"))
        val axes = LearnedValues.Axes("TEST16", 16, null,
            LearnedValues.Axis(0x3000, 7, "uint16", 0.1953125), LearnedValues.Axis(0x4000, 7, "uint16", 6.103515625e-05))
        return Triple(mem, params, axes)
    }

    @Test fun flkcReadFallsBackToSmallChunks() {
        val (mem, params, axes) = table16()
        for (limit in listOf(16, 4)) {
            val r = LearnedValues.read(SsmClient(FakeEcu(mem, limit)), init, params, axes)
            val t = assertNotNull(r.flkc).let { r.flkc!! }
            assertEquals("limit $limit", 8, t.size)
            assertEquals(-1.0546875, t[1][1], 1e-9)
            assertEquals(1.0, r.iam!!, 1e-9)
        }
    }

    /** One address that doesn't answer (a second bank, a parameter from a neighbouring ROM) costs that cell, not the whole reading. */
    @Test fun oneSilentAddressCostsOnlyThatValue() {
        val (mem, params, axes) = table16()
        val withAf = params + mapOf(
            "E13" to param("E13", 0x2200, 1, "", "x"), "E14" to param("E14", 0x2201, 1, "", "x"),
            "E15" to param("E15", 0x2202, 1, "", "x"), "E16" to param("E16", 0x2203, 1, "", "x"),
        )
        val m = HashMap(mem); for (k in 0 until 4) m[0x2200 + k] = 10 + k
        val r = LearnedValues.read(SsmClient(FakeEcu(m, silent = { it == 0x2201 })), init, withAf, axes)
        assertEquals(1.0, r.iam!!, 1e-9)
        assertEquals(listOf(10.0, null, 12.0, 13.0), r.afBanks[0])
        assertEquals(1, r.missed)
        assertEquals(-1.0546875, r.flkc!![1][1], 1e-9)
    }

    @Test fun silentIamKeepsTheRest() {
        val (mem, params, axes) = table16()
        val iamAddr = params.getValue("E1").addr
        val withAf = params + mapOf(
            "E13" to param("E13", 0x2200, 1, "", "x"), "E14" to param("E14", 0x2201, 1, "", "x"),
            "E15" to param("E15", 0x2202, 1, "", "x"), "E16" to param("E16", 0x2203, 1, "", "x"),
        )
        val m = HashMap(mem); for (k in 0 until 4) m[0x2200 + k] = 10 + k
        val r = LearnedValues.read(SsmClient(FakeEcu(m, silent = { it == iamAddr })), init, withAf, axes)
        assertEquals(null, r.iam)
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0), r.afBanks[0])
        assertEquals(1, r.missed)
    }

    @Test fun nothingAnswersIsStillSaid() {
        val (mem, params, axes) = table16()
        val e = runCatching { LearnedValues.read(SsmClient(FakeEcu(mem, silent = { true })), init, params, axes) }.exceptionOrNull()
        assertEquals("ЭБУ не ответил", e?.message)
    }

    @Test fun flkcFailureKeepsIamAndAf() {
        val (mem, params, axes) = table16()
        val withAf = params + mapOf(
            "E13" to param("E13", 0x2200, 1, "", "x"), "E14" to param("E14", 0x2201, 1, "", "x"),
            "E15" to param("E15", 0x2202, 1, "", "x"), "E16" to param("E16", 0x2203, 1, "", "x"),
        )
        val m = HashMap(mem); for (k in 0 until 4) m[0x2200 + k] = 10 + k
        val ecu = FakeEcu(m, silent = { it in 0x2012..0x2051 }) // the table's cells never answer
        val r = LearnedValues.read(SsmClient(ecu), init, withAf, axes)
        assertEquals(1.0, r.iam!!, 1e-9)
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0), r.afBanks[0])
        assertEquals(null, r.flkc)
        assertEquals(true, r.flkcError!!.contains("FLKC"))
    }

    @Test fun flkc32bitReadFallsBackToSmallChunks() {
        val iam = 0xFF2538
        val mem = HashMap<Int, Int>()
        mem.float(iam, 0.875f)
        listOf(2000f, 2800f, 3600f, 4400f, 5200f, 6000f).forEachIndexed { i, v -> mem.float(821940 + i * 4, v) }
        listOf(1.15f, 1.5f, 1.9f, 4.0f).forEachIndexed { i, v -> mem.float(821924 + i * 4, v) }
        mem.float(iam + 0x14 + 18 * 8, -1.05f)
        val params = mapOf("E31" to param("E31", iam, 4, "float", "x"))
        val axes = LearnedValues.Axes("X", 32, null, LearnedValues.Axis(821940, 6, "float", 1.0), LearnedValues.Axis(821924, 4, "float", 1.0))
        for (limit in listOf(16, 4)) {
            val t = LearnedValues.read(SsmClient(FakeEcu(mem, limit)), init, params, axes).flkc!!
            assertEquals("limit $limit", -1.05, t[3][3], 1e-6)
            assertEquals(1, t.flatten().count { it != 0.0 })
        }
    }

    @Test fun fallbackSkipsStepsThatRepeatTheSameRequest() {
        val (mem, params, axes) = table16()
        var hits = 0
        // one address per frame (an ELM327): the smaller steps would send the very same request again
        val one = FakeEcu(mem, silent = { (it in 0x2012..0x2051).also { dead -> if (dead) hits++ } }, maxReadAddrs = 1)
        assertEquals(null, LearnedValues.read(SsmClient(one), init, params, axes).flkc)
        assertEquals(1, hits)
    }

    @Test fun readStopsAfterItsDeadline() {
        val (mem, params, axes) = table16()
        var now = 0L
        val ecu = FakeEcu(mem, limit = 4, maxReadAddrs = 4, tick = { now += 3000 })
        val r = LearnedValues.read(SsmClient(ecu), init, params, axes) { now }
        assertEquals(1.0, r.iam!!, 1e-9)
        assertEquals(null, r.flkc)
        assertEquals(true, r.flkcError!!.contains("FLKC"))
        assertEquals(true, ecu.reads <= 6) // IAM and the first axis, then nothing more
    }

    @Test fun timeoutMidTableIsReportedAsTimeoutNotAsSilence() {
        val (mem, params, axes) = table16()
        var now = 0L
        val ecu = FakeEcu(mem, limit = 4, tick = { now += 500 }) // the axes fit, the table runs out of time in its small chunks
        val r = LearnedValues.read(SsmClient(ecu), init, params, axes) { now }
        assertEquals(null, r.flkc)
        assertEquals(true, r.flkcError!!.contains("времени"))
        assertEquals(false, r.flkcError!!.contains("не ответил"))
    }
}
