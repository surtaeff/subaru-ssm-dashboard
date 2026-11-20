package kg.forester.sg.service

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.BoxFrame
import kg.forester.sg.data.BoxInfo
import kg.forester.sg.data.BoxKind
import kg.forester.sg.data.Repo
import kg.forester.sg.data.SensorBox
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * The SensorBox on USB next to the ECU cable. A Nano's CH340 is the same chip as many KKL cables, so a device is
 * taken for the box only when it answers «?» with «I SENSORBOX». The ECU loop and this one never hold the same
 * device: [claim] under one lock, the ECU loop skips what is [busy], this loop skips what the ECU has open.
 */
object SensorBoxLink {
    private val lock = Any()
    /** Devices being probed or read as the box, by USB device name. */
    private val busy = HashSet<String>()
    /** Devices asked once since they were plugged in: not asked again. */
    private val asked = HashSet<String>()
    /** Which loop asks a device first (see [BoxFirst]); under [lock]. */
    private val first = BoxFirst()
    /** Devices each loop saw on its last look: a new one means something was plugged in (or came back after a sleep). */
    private val boxSeen = HashSet<String>()
    private val ecuSeen = HashSet<String>()
    /** What this loop never asks (the ECU loop's own adapters: OpenPort, CANable); set by [start]. */
    @Volatile private var notBox: ((UsbSerialDriver) -> Boolean)? = null
    /** The ECU loop's device is one this loop would ask too (a bare USB-serial chip). */
    @Volatile private var ecuBoxMayAsk = false
    /** The ECU loop's device while it has it open. */
    @Volatile var ecuDevice: String? = null
    /** The ECU loop's hold on [ecuDevice]: no longer [UsbOwnership.Lease.permitted] when the wideband needs the device. */
    @Volatile var ecuLease: UsbOwnership.Lease? = null
        private set
    /** The device the box last answered on: the one the firmware update goes to. */
    @Volatile var lastBoxDevice: String? = null
    /** The device being flashed (also as its bootloader): nobody else opens it meanwhile. */
    @Volatile var flashDevice: String? = null
        set(value) {
            field?.let { UsbDeviceRegistry.flash(it, false) }
            field = value
            value?.let { UsbDeviceRegistry.flash(it, true) }
        }
    @Volatile private var paused = false
    /** Lines for the box (output commands, heartbeats), written by the session loop. */
    private val outbox = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Counts the times the box answered anew: the relay engine sets the outputs up again on each. */
    @Volatile var session = 0

    fun send(line: String) { if (outbox.size < 200) outbox.add(line) }

    /** A real box with outputs (firmware 3+) is on the cable. */
    fun canDrive() = SensorBox.info.value?.let { it.via.startsWith("USB") && it.fw >= 3 } == true

    /** The box can read a CAN bus (an ESP32-S3 with firmware 6+ and the transceiver), see [kg.forester.sg.data.BoxCan]. */
    fun canCan() = kg.forester.sg.data.BoxCan.supported()

    /** The box can draw on displays (firmware 5+). */
    fun canDisplay() = SensorBox.info.value?.let { it.via.startsWith("USB") && it.fw >= 5 } == true

    /** A firmware update needs the board alone: the loop lets go of it ([on]), and asks again afterwards. */
    fun pauseForFlash(on: Boolean) {
        paused = on
        if (!on) synchronized(lock) { asked.clear() }
    }

    /** The usual serial chips plus SparkFun's Pro Micro, which the library's table doesn't list. */
    private val prober = com.hoho.android.usbserial.driver.UsbSerialProber(
        com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProbeTable().apply {
            listOf(0x9203, 0x9204, 0x9205, 0x9206).forEach { addProduct(0x1B4F, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
            listOf(0x0043, 0x0042, 0x8036, 0x8037, 0x0036, 0x0037).forEach { addProduct(0x2A03, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
            // the Caterina bootloader (Leonardo, Micro) a Pro Micro restarts into for a firmware update
            listOf(0x0036, 0x0037).forEach { addProduct(0x2341, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
        })

    fun drivers(usb: UsbManager): List<UsbSerialDriver> = prober.findAllDrivers(usb)

    /** Arduino, arduino.org and SparkFun boards with their own USB (Uno R3, Mega, Leonardo, Micro, Pro Micro): never an ECU cable. */
    fun isArduino(vid: Int) = vid == 0x2341 || vid == 0x2A03 || vid == 0x1B4F

    /** What the ECU loop may use: the box and boards still being asked are left out (only with the box switched on). */
    fun forEcu(all: List<UsbSerialDriver>): List<UsbSerialDriver> {
        // the colour screen (an ESP32-S3) is never an ECU cable
        // what is plugged in, and of it what nobody else has open or keeps: a device the box loop (or the wideband's
        // probe) holds for a few seconds is still plugged in, and is no new arrival when it is let go
        val present = all.filter { !ScreenLink.isScreen(it.device.vendorId) }
        val drivers = present.filter { UsbDeviceRegistry.available(it.device, UsbDeviceRegistry.ECU) }
        flashDevice?.let { f -> if (!SensorBox.enabled.value) return drivers.filter { it.device.deviceName != f } }
        if (!SensorBox.enabled.value) return drivers
        val now = System.currentTimeMillis()
        synchronized(lock) {
            arrivals(ecuSeen, present, now)
            // a Nano's CH340 looks like a KKL cable: never the box's device, and a device this loop has yet to ask
            // only after a while (the ECU loop starts first and would keep it as a cable that never echoes)
            return drivers.filter {
                val n = it.device.deviceName
                n !in busy && n != flashDevice && !isArduino(it.device.vendorId) &&
                    (!boxMayAsk(it) || first.ecuMayTake(n, n == lastBoxDevice, n in asked, now))
            }
        }
    }

    /** Why [forEcu] leaves [d] out right now, for the log; null when it does not. */
    fun whyNotForEcu(d: UsbSerialDriver): String? {
        val n = d.device.deviceName
        val own = UsbDeviceRegistry.ownership
        val kept = own.reservedFor(n)
        val holder = own.holder(n)
        return when {
            ScreenLink.isScreen(d.device.vendorId) -> "a screen board"
            kept != null && kept != UsbDeviceRegistry.ECU -> "kept for $kept"
            holder != null -> "open by $holder"
            n == flashDevice -> "being flashed"
            !SensorBox.enabled.value -> null
            isArduino(d.device.vendorId) -> "an Arduino board"
            n == lastBoxDevice -> "it answered as the SensorBox"
            synchronized(lock) { n in busy } -> "the SensorBox loop is asking it"
            synchronized(lock) { boxMayAsk(d) && !first.ecuMayTake(n, false, n in asked, System.currentTimeMillis()) } -> "waiting for the SensorBox loop to ask it first"
            else -> null
        }
    }

    /** The ECU loop takes [d]: the box loop leaves it alone. False when the box loop got there first. */
    fun claimForEcu(d: UsbSerialDriver): Boolean = synchronized(lock) {
        if (d.device.deviceName in busy || d.device.deviceName == flashDevice) false else {
            val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.ECU) ?: return@synchronized false
            ecuLease = lease
            ecuDevice = d.device.deviceName
            ecuBoxMayAsk = boxMayAsk(d)
            true
        }
    }

    fun releaseEcu() { ecuLease?.close(); ecuLease = null; ecuDevice = null }

    /**
     * The ECU loop's K-line session heard no echo for [rounds] rounds in a row: no cable on a live line, maybe the
     * box on the same chip. True: let the device go, this loop asks it first (once per wake, see [BoxFirst.ecuYields]).
     */
    fun ecuNoEcho(rounds: Int): Boolean {
        if (!SensorBox.enabled.value) return false
        val n = ecuDevice ?: return false
        synchronized(lock) {
            if (!first.ecuYields(n, rounds, ecuBoxMayAsk)) return false
            asked -= n
            first.hold(n, System.currentTimeMillis())
        }
        SsmTrace.log("SensorBox: no echo on $n for $rounds rounds, asking it whether it is the box")
        return true
    }

    /** The head unit woke (ACC on): every device is asked again, the ECU loop waits for that a while. */
    fun wake() = synchronized(lock) { asked.clear(); first.holdAll(System.currentTimeMillis()) }

    /** Under [lock]: a device not seen on the last look (plugged in, or back after a sleep) has everything asked again. */
    private fun arrivals(seen: HashSet<String>, drivers: List<UsbSerialDriver>, now: Long) {
        val names = drivers.map { it.device.deviceName }.toSet()
        val fresh = drivers.filter { it.device.deviceName !in seen }
        seen.retainAll(names)
        seen.addAll(names)
        if (fresh.isEmpty()) return
        asked.clear()
        first.holdAll(now)
        val ids = rememberedIds()
        fresh.filter { BoxFirst.remembered(ids, boxId(it.device)) }.forEach { first.hold(it.device.deviceName, now, BoxFirst.REMEMBERED_MS) }
    }

    /** This loop would ask [d] whether it is the box. */
    // with «Доп датчики» off the box loop asks nothing, so the ECU loop must not wait for it
    private fun boxMayAsk(d: UsbSerialDriver) = SensorBox.enabled.value && notBox?.invoke(d) == false && d.device.deviceName !in ScreenLink.knownScreens

    /** "VID:PID", with "/serial" when Android lets us read one. */
    private fun boxId(d: android.hardware.usb.UsbDevice) = BoxFirst.id(d.vendorId, d.productId, UsbDeviceRegistry.serial(d))

    private fun rememberedIds(): List<String> =
        runCatching { Repo.settings.boxUsbIds }.getOrDefault("").split(',').filter { it.isNotEmpty() }

    private fun remember(d: android.hardware.usb.UsbDevice) {
        val id = boxId(d)
        val ids = rememberedIds()
        if (id !in ids) runCatching { Repo.settings.boxUsbIds = (ids + id).takeLast(4).joinToString(",") }
    }

    /** Every few seconds: ask a device nobody holds whether it is the box, read it while it is. */
    suspend fun loop(scope: CoroutineScope, usb: UsbManager, list: () -> List<UsbSerialDriver>, isAdapter: (UsbSerialDriver) -> Boolean,
                     askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        var demoT = 0f
        while (scope.isActive) {
            if (Repo.demoOn.value) {
                demoT += 0.1f
                demoFrame(demoT)
                delay(100)
                continue
            }
            if (SensorBox.info.value?.via == "демо") { SensorBox.info.value = null; SensorBox.readings.value = emptyMap() }
            if (paused) { delay(300); continue }
            if (!SensorBox.enabled.value) { delay(1000); continue }
            val drivers = runCatching { list() }.getOrDefault(emptyList())
            val names = drivers.map { it.device.deviceName }.toSet()
            synchronized(lock) {
                asked.retainAll(names) // unplugged: asked again when it comes back
                first.gone(names)
                arrivals(boxSeen, drivers, System.currentTimeMillis())
            }
            val candidate = synchronized(lock) {
                drivers.firstOrNull { d ->
                    val n = d.device.deviceName
                    // an Espressif board may be the box (an ESP32-S3) as well as a colour screen: asked unless it answered as a screen
                    n !in busy && n !in asked && n != ecuDevice && !isAdapter(d) && n !in ScreenLink.knownScreens &&
                        UsbDeviceRegistry.available(d.device, UsbDeviceRegistry.BOX)
                }?.also { busy += it.device.deviceName; asked += it.device.deviceName }
            }
            if (candidate == null) {
                if (SensorBox.info.value == null) SensorBox.status.value =
                    if (drivers.isEmpty()) "Плата не найдена: подключите её к USB магнитолы" else "Плата не отвечает: залита ли прошивка SensorBox?"
                delay(3000)
                continue
            }
            val n = candidate.device.deviceName
            if (!usb.hasPermission(candidate.device)) {
                askPermission(candidate.device)
                SensorBox.status.value = "Разрешите доступ к USB-устройству в окне Android"
                synchronized(lock) { busy -= n; asked -= n }
                delay(4000)
                continue
            }
            val lease = UsbDeviceRegistry.claim(candidate.device, UsbDeviceRegistry.BOX)
            if (lease == null) {
                synchronized(lock) { busy -= n; asked -= n }
                delay(300); continue
            }
            val before = session
            val wasBox = try { session(usb, candidate, lease) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { SsmTrace.log("SensorBox: $e"); false }
                finally { lease.close(); synchronized(lock) { busy -= n } }
            // answered this time, even if a USB error (after a sleep) ended it: the box all the same
            val answered = wasBox || session != before
            // the box that went quiet is asked again (also one that answered before, here or by its ids); anything else
            // only after it is plugged in again or the unit wakes
            synchronized(lock) {
                busy -= n
                if (first.askAgain(n, answered, n == lastBoxDevice, BoxFirst.remembered(rememberedIds(), boxId(candidate.device)))) asked -= n
            }
            delay(1000)
        }
    }

    /** Open, ask, and read for as long as the box answers; true when it was the box. */
    private suspend fun session(usb: UsbManager, d: UsbSerialDriver, lease: UsbOwnership.Lease): Boolean {
        val conn = usb.openDevice(d.device) ?: return false
        lease.interrupt = { runCatching { conn.close() }; Unit }
        var known = false
        val port = d.ports[0]
        val id = "%04X:%04X".format(d.device.vendorId, d.device.productId)
        try {
            if (!lease.permitted) return false
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (ScreenLink.isScreen(d.device.vendorId)) delay(300) // an ESP32-S3: DTR and RTS are its reset and boot lines, left alone
            else {
                runCatching { port.dtr = true } // a 32U4 sends nothing until the host raises DTR; a Nano restarts on it
                delay(2200) // a Nano's bootloader
            }
            // a busy CAN bus comes as ~90 KB/s of frame lines (firmware 6+ on an ESP32-S3)
            val buf = ByteArray(16384)
            val line = StringBuilder()
            var canSent: kg.forester.sg.data.BoxCanCfg? = null
            var lastData = System.currentTimeMillis()
            var askedAt = 0L
            while (true) {
                if (paused || !lease.permitted || !currentCoroutineContext().isActive) return known
                if (known) while (true) { val l = outbox.poll() ?: break; port.write("$l\n".toByteArray(Charsets.ISO_8859_1), 300) }
                // the CAN settings to a box that has CAN: once per session, and again whenever they change
                val can = kg.forester.sg.data.BoxCan.cfg.value
                if (known && can != canSent && canCan()) {
                    canSent = can
                    kg.forester.sg.data.BoxCan.commands(can).forEach { port.write("$it\n".toByteArray(Charsets.ISO_8859_1), 300) }
                    SsmTrace.log("SensorBox CAN: ${kg.forester.sg.data.BoxCan.commands(can).joinToString(" | ")}")
                }
                val now = System.currentTimeMillis()
                if (now - askedAt > 3000 && now - lastData > 1500) {
                    port.write("?\r\n".toByteArray(), 300)
                    askedAt = now
                }
                // not the box (or it went quiet): let the device go
                if (now - lastData > if (known) 5000 else 3500) {
                    if (known) SensorBox.status.value = "Плата перестала отвечать"
                    return known
                }
                val n = port.read(buf, 200)
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n' || c == '\r') {
                        if (line.isNotEmpty()) {
                            val s = line.toString()
                            line.clear()
                            // a CAN frame (firmware 6+): the commonest line on a busy bus, so the first looked at
                            if (known && (s[0] == 't' || s[0] == 'T')) {
                                kg.forester.sg.data.BoxCan.parseLine(s)?.let { kg.forester.sg.data.BoxCanBus.onFrame(it) }
                                continue
                            }
                            SensorBox.parseInfo(s, "USB $id")?.let {
                                if (!known) { SsmTrace.log("SensorBox: $s"); outbox.clear(); session++; remember(d.device) }
                                known = true
                                lastBoxDevice = d.device.deviceName
                                lastData = System.currentTimeMillis()
                                SensorBox.info.value = it
                                SensorBox.status.value = ""
                            }
                            if (known) SensorBox.parseFrame(s)?.let { f -> lastData = System.currentTimeMillis(); SensorBox.apply(f) }
                            if (known && s == "Y R") DisplayEngine.redraw()
                        }
                    } else if (line.length < 400) line.append(c)
                }
            }
        } finally {
            if (SensorBox.info.value?.via == "USB $id") { SensorBox.info.value = null; SensorBox.readings.value = emptyMap(); kg.forester.sg.data.BoxCan.status.value = null }
            runCatching { port.close() }
            runCatching { conn.close() }
        }
    }

    // ---- the demo: a box that follows the made-up drive, its readings run through the same conversion

    private var oilT = Float.NaN
    private var egt = 300f

    private fun demoFrame(t: Float) {
        val b = SensorBox.board.value
        if (SensorBox.info.value?.via != "демо") SensorBox.info.value = BoxInfo(b.id, "DEMO", 10, b.inputs, "демо", kg.forester.sg.data.BOX_FIRMWARE)
        val e = Repo.live.value
        val rpm = (e.rpm ?: 800).toFloat()
        val maf = e.mafGs ?: 3f
        val c = (e.coolantC ?: 85).toFloat()
        if (oilT.isNaN()) oilT = c + 9f // the demo drive starts warm
        oilT += ((c + 10f + maf * 0.3f).coerceAtMost(c + 35f) - oilT) * 0.0015f
        // a healthy car: well under the 900° warning
        egt += ((280f + maf * 5f + rpm * 0.03f).coerceAtMost(780f) - egt) * 0.03f
        val oilP = ((0.9f + rpm / 1000f * 0.75f) * (1f + (90f - oilT).coerceIn(0f, 60f) / 120f)).coerceAtMost(6.5f)
        val afr = e.afr ?: 14.7f
        val raw = HashMap<String, Float>()
        var tc: Float? = null
        var hz: Float? = null
        for (s in SensorBox.slots.value) {
            val noise = sin(t * 7.3f + s.input.hashCode()) * 0.004f
            val v = when (s.role) {
                "oil" -> oilT
                "oilP" -> oilP
                "afr" -> afr + sin(t * 3f) * 0.15f
                "egt" -> egt
                else -> when (s.p.kind) { BoxKind.PRESSURE -> 2.8f + maf * 0.01f; BoxKind.FREQ -> 10f; else -> s.a + s.b * 2.5f }
            }
            when (s.p.kind) {
                BoxKind.TC -> tc = v
                BoxKind.FREQ -> hz = (v - s.a) / s.b
                else -> raw[s.input] = (SensorBox.rawFor(s, v) * (1f + noise)).coerceIn(0f, 1023f)
            }
        }
        b.analog.forEach { if (it !in raw) raw[it] = 0f }
        // the demo box does what the rules ask, so the output screen comes alive too
        val st = kg.forester.sg.data.Relays.status.value
        val outs = (1..(kg.forester.sg.data.Relays.channels.value.maxOfOrNull { it.ch } ?: 0)).joinToString("") { ch ->
            st[ch]?.let { if (!it.want) "0" else if (it.blink) "b" else "1" } ?: "-"
        }
        SensorBox.apply(BoxFrame(System.currentTimeMillis(), 5000, raw, tc, 0, hz ?: 0f, outs, false))
    }

    /** Starts the box loop next to the ECU one. */
    fun start(scope: CoroutineScope, usb: UsbManager, list: () -> List<UsbSerialDriver>, isAdapter: (UsbSerialDriver) -> Boolean,
              askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        notBox = isAdapter
        // before the ECU loop's first look: it waits for this loop to ask the serial devices first
        synchronized(lock) { first.holdAll(System.currentTimeMillis()) }
        scope.launch(Dispatchers.IO) { loop(scope, usb, list, isAdapter, askPermission) }
    }
}

/**
 * Which loop asks a USB device first, the box loop or the ECU loop (only with the box switched on). A Nano's CH340
 * is a KKL cable's chip too, and the ECU loop keeps a device it opened as a cable: after a start, a wake or a device
 * plugged in it leaves the devices the box loop has yet to ask for a while, and never the box's own device.
 * Pure, under the caller's lock.
 */
class BoxFirst(private val holdMs: Long = HOLD_MS) {
    private var allUntil = Long.MIN_VALUE
    private val until = HashMap<String, Long>()
    private val yielded = HashSet<String>()
    private val misses = HashMap<String, Int>()

    /** A start, a wake or a device plugged in: every device not asked yet waits for the box loop a while. */
    fun holdAll(now: Long) {
        allUntil = now + holdMs
        yielded.clear()
        misses.clear()
    }

    /** [device] waits for the box loop for [ms]: one that answered as the box before (by its ids), or one the ECU let go. */
    fun hold(device: String, now: Long, ms: Long = holdMs) {
        until[device] = maxOf(until[device] ?: Long.MIN_VALUE, now + ms)
    }

    /** Forgets devices no longer plugged in. */
    fun gone(present: Set<String>) {
        until.keys.retainAll(present)
        yielded.retainAll(present)
        misses.keys.retainAll(present)
    }

    /** The ECU loop may open [device]: never the box itself ([isBox]), else once the box loop asked it or the wait is over. */
    fun ecuMayTake(device: String, isBox: Boolean, asked: Boolean, now: Long): Boolean =
        !isBox && (asked || now >= maxOf(allUntil, until[device] ?: Long.MIN_VALUE))

    /** A K-line session without echo for [rounds] rounds lets [device] go for the box loop to ask: once per wake. */
    fun ecuYields(device: String, rounds: Int, boxMayAsk: Boolean): Boolean =
        boxMayAsk && rounds >= NO_ECHO_ROUNDS && yielded.add(device)

    /**
     * After a box session on [device]: asked again, or left alone until it is plugged in again or the unit wakes.
     * Asked again when it answered, when it is the box's device ([lastBox]), or a few times when its ids are those of
     * a box seen before ([remembered]: a KKL cable on the same chip has the same ids).
     */
    fun askAgain(device: String, answered: Boolean, lastBox: Boolean, remembered: Boolean): Boolean {
        if (answered) { misses.remove(device); return true }
        val m = (misses[device] ?: 0) + 1
        misses[device] = m
        return lastBox || (remembered && m <= REMEMBERED_TRIES)
    }

    companion object {
        /** How long the ECU loop leaves a device for the box loop: a Nano's probe takes about 6 s, a pause 3 s between. */
        const val HOLD_MS = 20_000L
        /** A device with a remembered box's ids waits longer: the box loop may be busy asking others first. */
        const val REMEMBERED_MS = 60_000L
        const val NO_ECHO_ROUNDS = 3
        const val REMEMBERED_TRIES = 3

        /** "VID:PID", with "/serial" when there is one. */
        fun id(vid: Int, pid: Int, serial: String?): String =
            "%04X:%04X".format(vid, pid) + (serial?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "")

        /** [id] is one of [ids]; without the serial (no permission yet) by VID:PID alone. */
        fun remembered(ids: List<String>, id: String): Boolean =
            id in ids || ('/' !in id && ids.any { it.substringBefore('/') == id })
    }
}
