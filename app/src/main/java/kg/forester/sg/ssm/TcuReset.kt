package kg.forester.sg.ssm

/**
 * The transmission unit's «Clear Memory 2» (its learned shift values), run the way FreeSSM runs it
 * (ClearMemoryDlg::run, SSMprotocol::clearMemory, SSMP2communication_core writeDatabyte):
 *
 *  1. the adjustment values are read first (FreeSSM's «Adjustments»: the clear puts them back to their defaults);
 *  2. one SSM2 write, B8 00 00 60 20; the unit answers F8 with the byte it took, and only that same byte counts;
 *  3. the ignition off until the unit stops answering, [IGNITION_OFF_MS] more, on again (waitForIgnitionOff, reconnect);
 *  4. the unit back with the SSM and ROM ids it had, else FreeSSM leaves it alone;
 *  5. when an adjustment had been set away from its default, the old values are offered back.
 *
 * Level 1 ([CLEAR_MEMORY], the codes screen's «Сбросить ошибки») clears the codes and the adjustments; on the
 * transmission only level 2 also clears the learned values. FreeSSM's dialog says so (ClearMemoryDlg::confirmClearMemory),
 * and so does Subaru's 4AT(diag) manual: «If Clear Memory 2 is selected and performed, DTC and learned control memory
 * are cleared». FreeSSM offers level 2 on transmission units only, and only to one whose flag bits list it.
 */
object TcuReset {
    const val ADDR = 0x000060
    /** FreeSSM's «Clear Memory»: the codes and the adjustments (and the engine unit's learned values). */
    const val CLEAR_MEMORY = 0x40
    /**
     * «Clear Memory 2»: 0x20 in FreeSSM 1.2.5, its last release (SSMprotocol2::ClearMemory). The master branch since the
     * 2023 refactor (commit 32a80fe, SSMFlagbyteDefinitionsInterface::clearMemory2Data) writes 0x40 for both levels;
     * a slip in the copy, it seems: the dialog there still promises level 2 more than level 1.
     */
    const val CLEAR_MEMORY_2 = 0x20
    /** FreeSSM's wait after the unit has fallen silent, before it asks for the ignition again. */
    const val IGNITION_OFF_MS = 5000L

    /** FreeSSM hasClearMemory2: flag byte 39 bit 1 (`_flagbytes[39] & 0x02`), [EcuInit.supports] numbering 47, 1. */
    fun supported(init: EcuInit?): Boolean = init?.supports(47, 1) == true

    /** What goes out to the transmission unit (0x18) for level 2. */
    fun body(): ByteArray = SsmClient.writeBody(ADDR, CLEAR_MEMORY_2)

    /** FreeSSM's success: the byte the unit says it wrote is the one sent (`bytewritten == val`). */
    fun accepted(echo: Int?): Boolean = echo == CLEAR_MEMORY_2

    // ---- when it is offered ----

    /** Why the reset is not offered right now. [text] is shown under the button. */
    enum class Block(val text: String) {
        DEMO("В демо-режиме блока АКПП нет"),
        OBD("В режиме OBD-II блок АКПП недоступен"),
        ELM("Через ELM327 сброс не делается: нужен KKL-кабель или OpenPort"),
        SHARED("Включено «На линии другой прибор»: приложение ничего не шлёт на K-линию"),
        OTHER_TESTER("На K-линии работает другой прибор: дождитесь, пока он замолчит"),
        NO_LINK("Нет связи с машиной"),
        NO_TCU("Блок АКПП не ответил по SSM"),
        NO_CM2("Этот блок АКПП не сообщает о «Clear Memory 2»: FreeSSM для него этот сброс тоже не предлагает"),
        MOVING("Остановите машину"),
        ENGINE("Заглушите двигатель, зажигание оставьте включённым"),
        RANGE("Поставьте селектор в P"),
    }

    /** What the screen knows about the line and the car at the moment. */
    data class Line(
        val demo: Boolean = false,
        val obd: Boolean = false,
        val elm: Boolean = false,
        /** Настройки, «На линии другой прибор». */
        val shared: Boolean = false,
        /** The transport heard another tester's frames lately ([SsmTransport.passive]). */
        val otherTester: Boolean = false,
        val connected: Boolean = true,
        val tcu: EcuInit? = null,
        val rpm: Int? = null,
        val speedKmh: Int? = null,
        /** [TcuSnapshot.range]: "P/N", "R", "D"… */
        val range: String? = null,
    )

    /**
     * Null when the reset can be offered. FreeSSM itself only needs a transmission unit that answered with the ignition
     * on; the engine off, the car standing and the lever in P are Subaru's own conditions for the Select Monitor work
     * (4AT(diag): «ignition switch to ON (engine OFF)», «select lever to P range, and apply the parking brake»). Readings
     * the app does not have do not block.
     */
    fun block(l: Line): Block? = when {
        l.demo -> Block.DEMO
        l.obd -> Block.OBD
        l.elm -> Block.ELM
        l.shared -> Block.SHARED
        l.otherTester -> Block.OTHER_TESTER
        !l.connected -> Block.NO_LINK
        l.tcu == null -> Block.NO_TCU
        !supported(l.tcu) -> Block.NO_CM2
        (l.speedKmh ?: 0) > 0 -> Block.MOVING
        (l.rpm ?: 0) > 0 -> Block.ENGINE
        l.range != null && l.range !in PARKED -> Block.RANGE
        else -> null
    }

    private val PARKED = setOf("P/N", "P", "N")

    /** ELM327s carry SSM in their own frames: the reset is left to a cable that sends our bytes as they are. */
    fun viaElm(t: SsmTransport?): Boolean = t is ElmKLineTransport || t is ElmCanTransport

    // ---- FreeSSM's adjustments ----

    /**
     * One of FreeSSM's transmission adjustments (SSMFlagbyteDefinitions_en.cpp, `_adjustment_defs_en`, unit type 1).
     * Listed by a flag bit ([flagByte], [flagBit] in [EcuInit.supports] numbering: FreeSSM's "26-1" is byte 25 bit 0
     * of the flag bytes, 33/0 here) or by one SSM id ([sysId]). A two-byte one has the high byte at [addrHigh].
     */
    data class Adjustment(
        val title: String, val addrLow: Int, val addrHigh: Int = 0,
        val rawMin: Int, val rawMax: Int, val rawDefault: Int,
        val flagByte: Int = 0, val flagBit: Int = 0, val sysId: String = "",
    ) {
        fun listedBy(init: EcuInit): Boolean = if (sysId.isNotEmpty()) init.ssmId.equals(sysId, ignoreCase = true) else init.supports(flagByte, flagBit)

        /** FreeSSM's range check: a minimum above the maximum is a range that wraps (a signed value). */
        fun inRange(raw: Int): Boolean = if (rawMin <= rawMax) raw in rawMin..rawMax else raw >= rawMin || raw <= rawMax
    }

    private fun gearShift(addr: Int, shift: String) =
        Adjustment("Gear Shift Line Pressure Correction - $shift", addr, rawMin = 90, rawMax = 110, rawDefault = 100, sysId = "A11020")

    private fun brake2(addr: Int, shift: String) =
        Adjustment("2-4-Brake Pressure 2 Correction - $shift", addr, rawMin = 90, rawMax = 110, rawDefault = 100, flagByte = 34, flagBit = 6)

    /** FreeSSM's list in its order (the engine's three left out). */
    val ADJUSTMENTS: List<Adjustment> = listOf(
        Adjustment("Line Pressure Correction", 0x72, rawMin = 90, rawMax = 110, rawDefault = 100, flagByte = 33, flagBit = 0),
        gearShift(0x73, "1st to 2nd"), gearShift(0x74, "2nd to 3rd"), gearShift(0x75, "3rd to 4th"),
        gearShift(0x76, "4th to 3rd"), gearShift(0x77, "4th to 2nd"), gearShift(0x78, "3rd to 2nd"),
        Adjustment("2-4-Brake Pressure 1 Correction", 0x79, rawMin = 90, rawMax = 110, rawDefault = 100, flagByte = 34, flagBit = 7),
        brake2(0x7A, "1st to 2nd"), brake2(0x7B, "2nd to 3rd"), brake2(0x7C, "3rd to 4th"),
        brake2(0x7D, "4th to 3rd"), brake2(0x7E, "4th to 2nd"), brake2(0x7F, "3rd to 2nd"),
        Adjustment("Correction of Transfer Duty Ratio", 0x80, rawMin = 90, rawMax = 110, rawDefault = 100, flagByte = 34, flagBit = 5),
        Adjustment("Slope Control", 0x81, rawMin = 0, rawMax = 1, rawDefault = 0, flagByte = 34, flagBit = 4),
        Adjustment("Center Differential", 0x82, rawMin = 50, rawMax = 150, rawDefault = 100, flagByte = 34, flagBit = 3),
        Adjustment("Correction of AWD Clutch Torque", 0x171, 0x170, rawMin = 63535, rawMax = 2000, rawDefault = 0, flagByte = 34, flagBit = 2),
    )

    fun adjustments(init: EcuInit): List<Adjustment> = ADJUSTMENTS.filter { it.listedBy(init) }

    /** The addresses one read asks, low byte then high byte of each (SSMprotocol::getAllAdjustmentValues). */
    fun addresses(adj: List<Adjustment>): IntArray =
        adj.flatMap { if (it.addrHigh > 0) listOf(it.addrLow, it.addrHigh) else listOf(it.addrLow) }.toIntArray()

    /** The raw values from the bytes of [addresses]. */
    fun values(adj: List<Adjustment>, raw: ByteArray): IntArray {
        var i = 0
        return IntArray(adj.size) { k ->
            var v = raw[i++].toInt() and 0xFF
            if (adj[k].addrHigh > 0) v += 256 * (raw[i++].toInt() and 0xFF)
            v
        }
    }

    /**
     * FreeSSM asks to put the old values back when every one of them was in its range and at least one was not the
     * default; the clear has made them all default.
     */
    fun offerRestore(adj: List<Adjustment>, old: IntArray): Boolean {
        if (adj.size != old.size) return false
        var changed = false
        for (k in adj.indices) {
            if (!adj[k].inRange(old[k])) return false
            if (old[k] != adj[k].rawDefault) changed = true
        }
        return changed
    }

    /** The single writes that put [old] back: (address, byte), low byte first (FreeSSM's setAdjustmentValue). */
    fun restoreWrites(adj: List<Adjustment>, old: IntArray): List<Pair<Int, Int>> = adj.indices.flatMap { k ->
        val a = adj[k]
        if (a.addrHigh > 0) listOf(a.addrLow to (old[k] and 0xFF), a.addrHigh to ((old[k] shr 8) and 0xFF))
        else listOf(a.addrLow to (old[k] and 0xFF))
    }

    // ---- the exchange ----

    /** The adjustments' raw values, null when the unit did not answer (before the clear FreeSSM stops then). */
    fun readAdjustments(c: SsmClient, adj: List<Adjustment>, label: String = "before"): IntArray? {
        if (adj.isEmpty()) return IntArray(0)
        val raw = c.read(addresses(adj), SsmClient.TCU)
        if (raw == null || raw.size < addresses(adj).size) {
            SsmTrace.log("TCU adjustments $label: no answer", keep = true)
            return null
        }
        val v = values(adj, raw)
        SsmTrace.log("TCU adjustments $label: " + adj.indices.joinToString(" ") { "%02X=%d".format(adj[it].addrLow, v[it]) }, keep = true)
        return v
    }

    /** The write: the byte the unit echoed, null with no answer. The polling's ring may drop the TX/RX lines: logged here too. */
    fun clear(c: SsmClient): Int? {
        val echo = c.write(ADDR, CLEAR_MEMORY_2, SsmClient.TCU)
        SsmTrace.log("TCU clear memory 2: sent ${body().toHex(" ")}, answer " + (echo?.let { "F8 %02X, %s".format(it, if (accepted(it)) "taken" else "not taken") } ?: "none"), keep = true)
        return echo
    }

    /** Puts [old] back with single writes, each echo checked, then reads them all again (ClearMemoryDlg::restoreAdjustmentValues). */
    fun restore(c: SsmClient, adj: List<Adjustment>, old: IntArray): Boolean {
        for ((addr, v) in restoreWrites(adj, old)) {
            if (c.write(addr, v, SsmClient.TCU) != v) {
                SsmTrace.log("TCU adjustments: %02X=%d not taken".format(addr, v), keep = true)
                return false
            }
        }
        val now = readAdjustments(c, adj, "after restoring") ?: return false
        return now.contentEquals(old).also { SsmTrace.log("TCU adjustments restored: " + if (it) "all as before" else "some differ", keep = true) }
    }

    // ---- the ignition cycle after the write ----

    enum class Stage { IGNITION_OFF, WAIT, IGNITION_ON, SAME_UNIT, OTHER_UNIT }

    /**
     * Where the ignition cycle after the write stands. FreeSSM waits until the unit stops answering (waitForIgnitionOff),
     * [IGNITION_OFF_MS] more, then asks for the ignition and sets the unit up again, comparing its SSM and ROM ids.
     * Here the line going silent is the link lost, and the unit back is a new init of it after that.
     */
    class Cycle(private val before: EcuInit) {
        private var silentAtMs = 0L
        private var initAtSilence: EcuInit? = null

        fun stage(connected: Boolean, tcu: EcuInit?, nowMs: Long): Stage {
            if (silentAtMs == 0L) {
                if (connected) return Stage.IGNITION_OFF
                silentAtMs = nowMs
                initAtSilence = tcu
            }
            if (nowMs - silentAtMs < IGNITION_OFF_MS) return Stage.WAIT
            // the init the service keeps from before the silence is no answer yet
            if (!connected || tcu == null || tcu === before || tcu === initAtSilence) return Stage.IGNITION_ON
            return if (tcu.ssmId == before.ssmId && tcu.romId == before.romId) Stage.SAME_UNIT else Stage.OTHER_UNIT
        }

        /** Seconds left of the wait, for the countdown. */
        fun waitLeftS(nowMs: Long): Int = if (silentAtMs == 0L) 0 else ((IGNITION_OFF_MS - (nowMs - silentAtMs) + 999) / 1000).toInt().coerceAtLeast(0)
    }
}
