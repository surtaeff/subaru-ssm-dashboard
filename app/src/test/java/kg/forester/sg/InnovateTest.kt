package kg.forester.sg

import kg.forester.sg.devices.innovate.*
import kg.forester.sg.data.*
import kg.forester.sg.service.GuardState
import kg.forester.sg.service.LogColumns
import kg.forester.sg.service.LogRow
import kg.forester.sg.service.UsbOwnership
import kg.forester.sg.ssm.EcuSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InnovateTest {
    private fun frame(status: Int = 0, raw: Int = 500, factor: Int = 147) = byteArrayOf(
        0xB2.toByte(), 0x82.toByte(), (0x42 or (status shl 2) or (factor shr 7)).toByte(),
        (factor and 127).toByte(), (raw shr 7).toByte(), (raw and 127).toByte())

    @Test fun everySplitAndBytewise() {
        for (split in 0..6) {
            val decoder = InnovateDecoder()
            val bytes = frame()
            val results = decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(1, results.size)
            assertEquals(14.7f, results.single().afr!!, 0.0001f)
        }
        val d = InnovateDecoder()
        val result = frame(raw = 300).flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(11.76f, result.single().afr!!, 0.0001f)
    }

    @Test fun concatenatedFramesAndNoise() {
        val d = InnovateDecoder()
        val result = d.feed(byteArrayOf(0, 12, 127, 0x82.toByte()) + frame() + frame(raw = 300))
        assertEquals(2, result.size)
        assertEquals(0.8f, result.last().lambda!!, 0.0001f)
    }

    @Test fun allStatusesNeverManufactureReadings() {
        for (status in 0..7) {
            val r = InnovateDecoder().feed(frame(status)).single()
            assertEquals(InnovateStatus.entries[status], r.status)
            if (status != 0) { assertNull(r.afr); assertNull(r.lambda) }
        }
    }

    @Test fun lengthAndBitErrorsRecoverAtNextHeader() {
        for (size in listOf(0, 1, 3, 127)) {
            val bad = frame().also { it[1] = (128 or size).toByte() }
            assertEquals(1, InnovateDecoder().feed(bad + frame()).size)
        }
        // 0xA2: bit 4 of the header clear, no header at all (RomRaider's "1x11xx1x")
        for ((index, value) in listOf(0 to 0xA2, 1 to 2, 2 to 0xC3, 3 to 0x93, 4 to 0x43, 5 to 0xF4)) {
            val bad = frame().also { it[index] = value.toByte() }
            assertEquals("index $index", 1, InnovateDecoder().feed(bad + frame()).size)
        }
    }

    @Test fun recordingBitIsStillAPacket() {
        // 0xF2: the R bit (the MTS chain being recorded) set, as RomRaider takes it: the reading stands
        val recording = frame(raw = 300).also { it[0] = 0xF2.toByte() }
        assertEquals(11.76f, InnovateDecoder().feed(recording).single().afr!!, 0.0001f)
        assertTrue(InnovateProbe.sees(recording + frame().also { it[0] = 0xF2.toByte() }))
        // a stream of 0xFF now matches the header bits but no packet that long: still not an Innovate
        assertFalse(InnovateProbe.sees(ByteArray(600) { 0xFF.toByte() }))
    }

    @Test fun truncatedFrameAndLongGarbageCannotPoisonNextFrame() {
        val d = InnovateDecoder()
        assertTrue(d.feed(frame().copyOf(4)).isEmpty())
        assertEquals(1, d.feed(frame()).size)
        assertTrue(d.feed(ByteArray(20000) { 0xFF.toByte() }).isEmpty())
        assertEquals(1, d.feed(frame()).size)
    }

    @Test fun instancesAndResetAreIndependent() {
        val a = InnovateDecoder(); val b = InnovateDecoder()
        a.feed(frame().copyOf(3))
        assertTrue(b.feed(frame().copyOfRange(3, 6)).isEmpty())
        assertEquals(1, a.feed(frame().copyOfRange(3, 6)).size)
        b.feed(frame().copyOf(3)); b.reset()
        assertTrue(b.feed(frame().copyOfRange(3, 6)).isEmpty())
    }

    @Test fun trueLambdaWhateverTheFuelFactor() {
        // (L + 500) / 1000: the factor (stoichiometric AFR × 10) changes the AFR, never lambda
        val e85 = InnovateDecoder().feed(frame(raw = 500, factor = 98)).single()
        assertEquals(1.0f, e85.lambda!!, 0.0001f)
        assertEquals(9.8f, e85.afr!!, 0.0001f)
        assertEquals(1.2f, InnovateDecoder().feed(frame(raw = 700)).single().lambda!!, 0.0001f)
        val zero = InnovateDecoder().feed(frame(factor = 0)).single()
        assertNull(zero.afr)
        assertEquals(1.0f, zero.lambda!!, 0.0001f)
    }

    @Test fun statusesSayWhatTheValueMeans() {
        fun reading(status: Int, raw: Int) = InnovateDecoder().feed(frame(status = status, raw = raw)).single()
        assertEquals("Кислород в воздухе: 20.9 %", reading(1, 209).text())
        assertEquals("Прогрев: 55 %", reading(4, 550).text())
        assertEquals("Ошибка датчика, код 5", reading(6, 5).text())
        assertEquals("Измерение", reading(0, 500).text())
        val error = WidebandState(WidebandLinkState.RECEIVING, sample = WidebandSample("usb", 0, 1000, reading(6, 5)))
        assertEquals("ERROR:5", error.status(1000))
        assertNull(error.afr(1000)); assertNull(error.lambda(1000))
    }

    @Test fun probeHearsMtsPacketsOnly() {
        assertTrue(InnovateProbe.sees(frame() + frame(status = 4)))
        assertTrue(InnovateProbe.sees(byteArrayOf(0, 0x7F, 0x13) + frame() + frame()))
        // a packet of two channels (4 words) followed by the next header
        val two = byteArrayOf(0xB2.toByte(), 0x84.toByte(), 0x43, 0x13, 3, 116, 0x43, 0x13, 3, 116)
        assertTrue(InnovateProbe.sees(two + two.copyOf(2)))
        assertFalse(InnovateProbe.sees(frame()))
        assertFalse(InnovateProbe.sees(ByteArray(0)))
        // SSM over a KKL cable (request and echo), an Arduino's text, a stream of 0xFF
        val ssm = byteArrayOf(0x80.toByte(), 0x10, 0xF0.toByte(), 0x01, 0xBF.toByte(), 0x40)
        assertFalse(InnovateProbe.sees(ssm + ssm + ssm))
        assertFalse(InnovateProbe.sees("I SENSORBOX 5 NANO\r\nD 512 300\r\n".toByteArray()))
        assertFalse(InnovateProbe.sees(ByteArray(600) { 0xFF.toByte() }))
        // the header bytes at the wrong distance
        assertFalse(InnovateProbe.sees(frame().copyOf(4) + frame()))
    }

    @Test fun freshnessAndLinkStateGateReadings() {
        val sample = WidebandSample("usb", 50000, 1000, InnovateReading(InnovateStatus.VALID, 500, 147))
        val live = WidebandState(WidebandLinkState.RECEIVING, sample = sample)
        assertNotNull(live.afr(1000))
        assertNotNull(live.afr(2500))
        assertNull(live.afr(2501)); assertEquals("STALE", live.status(2501))
        assertNull(live.afr(999))
        for (state in WidebandLinkState.entries.filter { it != WidebandLinkState.RECEIVING && it != WidebandLinkState.DEMO }) {
            assertNull(live.copy(link = state).afr(1000))
        }
        assertNull(live.copy(sample = sample.copy(reading = sample.reading.copy(status = InnovateStatus.WARMING))).afr(1000))
    }

    @Test fun reservationsRevokeButDoNotStealOpenDevices() {
        val r = UsbOwnership()
        val ecu = r.claim("usb", "ecu")!!
        var closed = false
        ecu.interrupt = { closed = true }
        r.reserve("usb", "innovate")
        assertTrue(closed); assertFalse(ecu.permitted)
        assertNull(r.claim("usb", "innovate"))
        ecu.close()
        assertNull(r.claim("usb", "ecu"))
        val innovate = r.claim("usb", "innovate")!!
        ecu.close() // stale token must not release the new owner
        assertNull(r.claim("usb", "innovate"))
        innovate.close()
        r.reserve("usb", null)
        assertNotNull(r.claim("usb", "ecu"))
    }

    @Test fun concurrentClaimsHaveOneWinner() {
        val r = UsbOwnership()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { i -> pool.submit<UsbOwnership.Lease?> { start.await(); r.claim("usb", "owner$i") } }
            start.countDown()
            val leases = futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            leases.single().close()
            assertTrue(r.available("usb", "next"))
        } finally { pool.shutdownNow() }
    }

    @Test fun flashReservationLetsTheHolderGoByItself() {
        val r = UsbOwnership()
        val screen = r.claim("usb", "screen")!!
        var closed = false
        screen.interrupt = { closed = true }
        r.reserve("usb", "flash", interrupt = false)
        assertFalse(closed); assertFalse(screen.permitted)
        screen.close()
        assertNull(r.claim("usb", "innovate"))
        r.unreserve("usb", "innovate") // somebody else's reservation stays
        assertNull(r.claim("usb", "ecu"))
        r.unreserve("usb", "flash")
        assertNotNull(r.claim("usb", "ecu"))
    }

    private val chosen = WidebandConfig(true, 0x1A86, 0x7523)
    private fun cand(name: String, serial: String? = "", permitted: Boolean = true, probe: Boolean? = null, free: Boolean = true) =
        WidebandCandidate(name, serial, permitted, probe, free)

    @Test fun nothingIsTakenForJustHavingTheSameIds() {
        // one CH340 without a serial number: listened to first, not taken
        assertEquals(WidebandPick.Probe("a"), WidebandPick.of(chosen, listOf(cand("a"))))
        assertEquals(WidebandPick.Silent, WidebandPick.of(chosen, listOf(cand("a", probe = false))))
        assertEquals(WidebandPick.Found("a"), WidebandPick.of(chosen, listOf(cand("a", probe = true))))
        // switched off or nothing chosen: nothing at all
        assertEquals(WidebandPick.Missing, WidebandPick.of(chosen.copy(enabled = false), listOf(cand("a", probe = true))))
        assertEquals(WidebandPick.Missing, WidebandPick.of(WidebandConfig(enabled = true), listOf(cand("a", probe = true))))
    }

    @Test fun twoIdenticalAdaptersAreToldApartByListening() {
        // the remembered place is listened to first
        assertEquals(WidebandPick.Probe("b"), WidebandPick.of(chosen.copy(path = "b"), listOf(cand("a"), cand("b"))))
        // the ECU answers through one (probe = false), the other one sends Innovate packets
        assertEquals(WidebandPick.Found("b"), WidebandPick.of(chosen, listOf(cand("a", probe = false, free = false), cand("b", probe = true))))
        // the other one is still open by somebody and the free one is silent: say so, take nothing
        assertEquals(WidebandPick.Ambiguous, WidebandPick.of(chosen, listOf(cand("a", free = false), cand("b", probe = false))))
        assertEquals(WidebandPick.Ambiguous, WidebandPick.of(chosen, listOf(cand("a", probe = true), cand("b", probe = true))))
        // both silent: the controller is off
        assertEquals(WidebandPick.Silent, WidebandPick.of(chosen, listOf(cand("a", probe = false), cand("b", probe = false))))
        // one device, open by the SensorBox asking it: wait
        assertEquals(WidebandPick.Busy, WidebandPick.of(chosen, listOf(cand("a", free = false))))
    }

    @Test fun serialNumberPicksTheAdapterAndPermissionDoesNotBlockIt() {
        val ftdi = chosen.copy(vendor = 0x0403, product = 0x6001, serial = "LC2")
        // the found adapter wins over another same-id device still without permission
        assertEquals(WidebandPick.Found("a"), WidebandPick.of(ftdi, listOf(cand("a", "LC2"), cand("b", null, permitted = false))))
        assertEquals(WidebandPick.Permission(listOf("b")), WidebandPick.of(ftdi, listOf(cand("a", "KKL"), cand("b", null, permitted = false))))
        // another serial number is another adapter (the KKL): left alone
        assertEquals(WidebandPick.Missing, WidebandPick.of(ftdi, listOf(cand("a", "KKL"))))
        // the ECU answers through the one with that serial: picked by mistake, not taken from the ECU
        assertEquals(WidebandPick.Silent, WidebandPick.of(ftdi, listOf(cand("a", "LC2", probe = false))))
        // clones sharing one serial number: listened to
        assertEquals(WidebandPick.Probe("a"), WidebandPick.of(ftdi, listOf(cand("a", "LC2"), cand("b", "LC2"))))
        // without a serial number remembered, one with a serial is not it; the one without permission is asked for
        assertEquals(WidebandPick.Permission(listOf("b")), WidebandPick.of(chosen, listOf(cand("a", "KKL"), cand("b", null, permitted = false))))
    }

    @Test fun csvSeparatesEcuAndWidebandAndBlanksStaleValues() {
        val ecu = EcuSnapshot(lambda = 1f)
        val sample = WidebandSample("usb", 50000, 1000, InnovateReading(InnovateStatus.VALID, 300, 147))
        val state = WidebandState(WidebandLinkState.RECEIVING, sample = sample)
        fun row(at: Long) = LogRow(ecu, null, 0.0, null,
            GuardState(), state, at)
        fun column(key: String, at: Long) = LogColumns.all.single { it.key == key }.get(row(at))
        assertEquals("14.70", column("afr", 1000))
        assertEquals("11.76", column("wideband_afr", 1000))
        assertEquals("0.800", column("wideband_lambda", 1000))
        assertEquals("", column("wideband_afr", 3000))
        assertEquals("STALE", column("wideband_status", 3000))
        assertEquals("2000", column("wideband_age_ms", 3000))
    }

    @Test fun csvWithoutTheWidebandKeepsTheOldColumns() {
        val keys = LogColumns.offered(widebandOn = false).map { it.key }
        assertTrue(keys.none { it.startsWith("wideband_") })
        // the columns of 0.9-beta20 first, in their order: whatever was added later comes after them
        val beta20 = listOf("rpm", "speed_kmh", "coolant_c", "intake_c", "battery_v", "throttle_pct", "pedal_pct", "load_grev",
            "avcs_deg", "alt_duty_pct", "maf_gs", "inj_pw_ms", "inj_duty_pct", "af_corr_pct", "af_learn_pct", "afr", "fuel_lh",
            "timing_deg", "knock_deg", "iam", "fbkc_deg", "flkc_deg", "atm_kpa", "map_kpa", "boost_kpa", "target_boost_kpa",
            "wgdc_pct", "gps_speed_kmh", "gps_alt_m", "gps_lat", "gps_lon", "gear", "atf_c", "turbine_rpm", "lockup_pct",
            "converter_slip_pct", "lockup_state", "fwd_fuse", "transfer_pct", "front_wheel_kmh", "rear_wheel_kmh",
            "fuel_sensor_v", "fuel_sensor_ohm")
        assertEquals(beta20, keys.take(beta20.size))
        assertTrue("kca_deg" in keys && keys.indexOf("kca_deg") > keys.indexOf("ve_pct"))
        val on = LogColumns.offered(widebandOn = true).map { it.key }
        // switched on: the same columns in the same order, the wideband's five at the end
        assertEquals(keys, on.dropLast(5))
        assertEquals(listOf("wideband_afr", "wideband_lambda", "wideband_status", "wideband_age_ms", "wideband_demo"), on.takeLast(5))
    }
}
