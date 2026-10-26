package kg.forester.sg.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kg.forester.sg.MainActivity
import kg.forester.sg.R
import kg.forester.sg.data.GpsFix
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.EcuParams
import kg.forester.sg.ssm.Elm327
import kg.forester.sg.ssm.ElmCanTransport
import kg.forester.sg.ssm.ElmKLineTransport
import kg.forester.sg.ssm.CanObd
import kg.forester.sg.ssm.ElmObd
import kg.forester.sg.ssm.KklTransport
import kg.forester.sg.ssm.Obd2
import kg.forester.sg.ssm.ObdLink
import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RrParam
import kg.forester.sg.ssm.UsbStreams
import kg.forester.sg.ssm.OpenPort13
import kg.forester.sg.ssm.Op2CanTransport
import kg.forester.sg.ssm.Op2KLineTransport
import kg.forester.sg.ssm.OpenPort2
import kg.forester.sg.ssm.SsmClient
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ssm.SsmTransport
import kg.forester.sg.ssm.toHex
import kg.forester.sg.ssm.TcuParams
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kg.forester.sg.ssm.LockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stock KKL chips plus the Tactrix cables: OpenPort 2.0 (CDC-ACM) and OpenPort 1.3 (FTDI), both with their own ids. */
private val adapterProber = UsbSerialProber(UsbSerialProber.getDefaultProbeTable().apply {
    OpenPort2.PIDS.forEach { addProduct(OpenPort2.VID, it, CdcAcmSerialDriver::class.java) }
    OpenPort13.PIDS.forEach { addProduct(OpenPort13.VID, it, FtdiSerialDriver::class.java) }
    // CANable / CANtact with the slcan firmware: a sports ECU's dash CAN
    addProduct(0xAD50, 0x60C4, CdcAcmSerialDriver::class.java)
    addProduct(0x16D0, 0x117E, CdcAcmSerialDriver::class.java)
})

/** Shared access to the ECU for one-off requests (DTC read/clear) between poll cycles. */
object EcuLink {
    val mutex = Mutex()
    @Volatile var client: SsmClient? = null
    /** Set instead of [client] while the car is read over standard OBD-II. */
    @Volatile var obd: Obd2? = null

    suspend fun <T> use(block: (SsmClient) -> T): T? = mutex.withLock {
        val c = client ?: return@withLock null
        runCatching { block(c) }.getOrNull()
    }
}

class EcuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val knock by lazy { KnockWatch(this) }
    private val guard = EngineGuard()
    private val tracker by lazy { TripTracker(this) { knock.reset(it); guard.newTrip(it) } }
    private val battery by lazy { BatteryMonitor(this) }
    private val history = ArrayDeque<EcuSnapshot>()
    private var mafDropLogged = false
    private val tcuHistory = ArrayDeque<TcuSnapshot>()
    private var tcuAddrs: IntArray? = null
    private var tcuNextTryMs = 0L
    private var tcuAtfOnly = false
    private var logger: CsvLogger? = null
    private var triedHostFix = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** After ACC sleep: GPS again (it often stalls) and the dashboard on screen if the driver wants it. */
    private val wakeWatch by lazy {
        WakeWatch(this) {
            scope.launch(Dispatchers.Main) {
                runCatching { startGps() }
                // the head unit's launcher comes up on the same wake: let it settle before covering it
                delay(3000)
                WakeWatch.openApp(this@EcuService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        SsmTrace.init(this)
        // the open folder for own RomRaider files, there from the first start for a file manager to find
        Thread { kg.forester.sg.data.EcuDefs.prepareFolder(this) }.start()
        Alerts.init(this)
        Motion.start(this)
        Tpms.start(this)
        startInForeground()
        startGps()
        battery // restore the last battery readings for the UI right away
        guard.analyzer = RulesHost.create(this)
        guard.ctx = applicationContext
        Voice.start(this)
        Fyt.bind(this)
        pollJob = scope.launch { pollLoop() }
        (getSystemService(Context.USB_SERVICE) as UsbManager).let { usb ->
            RelayEngine.start(scope)
            SensorBoxLink.start(scope, usb, { SensorBoxLink.drivers(usb).let { it + unknownSerial(usb, it) } },
                { isOp2(it) || isOp13(it) || isCanable(it) }) { requestUsbPermission(usb, it) }
            ScreenLink.start(scope, this, usb) { requestUsbPermission(usb, it) }
            InnovateLink.start(scope, usb) { requestUsbPermission(usb, it) }
        }
        wakeWatch.start(scope)
        scope.launch { headUnitWatch() }
        scope.launch(Dispatchers.Main) {
            while (isActive) { delay(30_000); runCatching { gpsWatchdog() }; runCatching { Voice.ensure(this@EcuService) } }
        }
        scope.launch {
            while (isActive) {
                Housekeeping.run(this@EcuService, Repo.logFile.value)
                // a head unit keeps the screen alive for days: look for a new version from here too
                runCatching { kg.forester.sg.data.Updater.checkDaily(this@EcuService) }
                delay(3600_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_TRIP -> scope.launch { tracker.resetTrip() }
            ACTION_START_GPS -> { startGps(); Voice.start(this) }
            ACTION_RELEASE -> releaseAdapter()
            ACTION_RECONNECT -> reconnectAdapter()
            ACTION_EXIT -> {
                exitRequested.tryEmit(Unit)
                stopService(Intent(this, PlayerService::class.java))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeWatch.stop()
        scope.launch { tracker.shutdown() }
        logger?.close()
        scope.cancel()
        super.onDestroy()
    }

    /** Lets another app have the adapter: polling stops and the session's finally closes the cable or socket. */
    private fun releaseAdapter() {
        if (Repo.adapterReleased.value || Repo.settings.demo) return
        Repo.adapterReleased.value = true
        val job = pollJob
        scope.launch {
            job?.cancelAndJoin()
            if (!Repo.adapterReleased.value) return@launch
            SsmTrace.log("adapter released for another app")
            Repo.link.value = LinkState.NO_CABLE
            Repo.adapterFound.value = ""
            Repo.linkMessage.value = "Адаптер свободен для другой программы (BTSSM и т. п.). Подключиться снова: нажмите значок связи вверху"
            // the last values must not stay on the screen as if they were live
            Repo.live.value = EcuSnapshot()
            Repo.tcu.value = null
            refreshNotification()
        }
    }

    private fun reconnectAdapter() {
        if (!Repo.adapterReleased.value) return
        Repo.adapterReleased.value = false
        val old = pollJob
        scope.launch {
            old?.join()
            pollJob = scope.launch { pollLoop() }
            refreshNotification()
        }
    }

    private suspend fun pollLoop() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        var noCableSince = System.currentTimeMillis()
        while (scope.isActive) {
            val s = Repo.settings
            if (s.demo) { demoLoop(); continue }
            if (Repo.linkDemo.value) {
                Repo.adapterFound.value = "Демо Link: CANable не нужен"
                kg.forester.sg.ssm.LinkDemo().use { canSession(it, 500) { Repo.linkDemo.value } }
                continue
            }
            SsmTrace.enabled = s.traceEnabled
            SsmTrace.polling = false
            tracker.tick(System.currentTimeMillis())
            val kind = s.adapter
            when (kind) {
                "elm_bt" -> { bluetoothSession(s.btAddress, s.btName); delay(3000); continue }
                "mt_bt" -> { bluetoothSession(s.btAddress, s.btName, multitronics = true); delay(3000); continue }
                "elm_wifi" -> { wifiSession(explicit = true); delay(3000); continue }
            }
            val all = adapterProber.findAllDrivers(usb) + unknownSerial(usb, adapterProber.findAllDrivers(usb))
            logUsbDevices(usb, all)
            // the SensorBox (an Arduino next to the cable) and devices still being asked whether they are it
            val drivers = SensorBoxLink.forEcu(all)
            val driver = pickDriver(kind, drivers)
            if (driver == null) {
                if (!triedHostFix && System.currentTimeMillis() - noCableSince > 8000) {
                    triedHostFix = true
                    forceUsbHostMode()
                }
                if (kind == "auto") {
                    // nothing on USB: a Wi-Fi ELM327 we are connected to, then a paired Bluetooth one
                    if (wifiSession(explicit = false) || autoBluetooth()) { delay(1000); continue }
                }
                Repo.link.value = LinkState.NO_CABLE
                Repo.adapterFound.value = ""
                val other = drivers.firstOrNull()
                Repo.linkMessage.value = when {
                    kind == "auto" -> "Адаптер не найден: подключите USB-кабель или Wi-Fi/Bluetooth-адаптер " +
                        "(Bluetooth сначала сопрягите в настройках Android)"
                    other != null && kind == "op2" -> "Найден KKL-кабель, а в настройках выбран OpenPort 2.0"
                    other != null -> "Найден OpenPort 2.0: выберите его в Настройках → Адаптер (или «Авто»)"
                    kind == "op2" -> "OpenPort 2.0 не найден"
                    kind == "canable" && usb.deviceList.values.any { kg.forester.sg.ssm.Slcan.isCandleLight(it.vendorId, it.productId) } ->
                        "CANable с прошивкой candleLight: приложение работает с прошивкой slcan (как RealDash). Перепрошейте адаптер на slcan"
                    kind == "canable" -> "CANable не найден: подключите его к USB магнитолы"
                    kind == "elm_usb" -> "USB-адаптер ELM327 не найден"
                    else -> "Кабель KKL не найден (если он подключён — «Отправить журнал»: в журнале коды всех USB-устройств)"
                }
                delay(2000)
                continue
            }
            noCableSince = System.currentTimeMillis()
            if (!usb.hasPermission(driver.device)) {
                Repo.link.value = LinkState.NO_PERMISSION
                Repo.linkMessage.value = "Нет разрешения на USB: нажмите «OK» в окне Android (лучше с галочкой «по умолчанию»). " +
                    "Окна нет — отключите и снова подключите кабель"
                requestUsbPermission(usb, driver.device)
                delay(3000)
                continue
            }
            if (!SensorBoxLink.claimForEcu(driver)) { delay(500); continue }
            Repo.link.value = LinkState.CONNECTING
            val conn = runCatching { usb.openDevice(driver.device) }.getOrNull()
            if (conn == null) {
                SensorBoxLink.releaseEcu()
                delay(2000); continue
            }
            val port = driver.ports[0]
            SensorBoxLink.ecuLease?.interrupt = { runCatching { conn.close() }; Unit }
            if (SensorBoxLink.ecuLease?.permitted != true) {
                conn.close(); SensorBoxLink.releaseEcu(); continue
            }
            SsmTrace.log("=== ${driver.javaClass.simpleName} %04X:%04X, адаптер $kind, шина ${s.bus}"
                .format(driver.device.vendorId, driver.device.productId))
            var op2: OpenPort2? = null
            var streams: UsbStreams? = null
            try {
                port.open(conn)
                val id = "%04X:%04X".format(driver.device.vendorId, driver.device.productId)
                when {
                    isCanable(driver) -> {
                        Repo.adapterFound.value = "USB $id → CANable (slcan)"
                        canableSession(port)
                    }
                    isOp2(driver) -> {
                        Repo.adapterFound.value = "USB $id → OpenPort 2.0"
                        op2 = OpenPort2(port).also { it.open() }
                        SsmTrace.log("OpenPort firmware ${op2.firmware}")
                        session(obd = { CanObd(openOp2(op2, false), "OpenPort 2.0") }) { kline -> openOp2(op2, kline) }
                    }
                    isOp13(driver) -> {
                        Repo.adapterFound.value = "USB $id → OpenPort 1.3"
                        session(klineOnly = true, obd = { kklObd(port) }) { _ -> KklTransport(port, openPort13 = true).also { it.open() } }
                    }
                    else -> {
                        // a bare KKL cable or an ELM327 behind the same USB chip: ask, and remember the answer
                        val baud = when (kind) {
                            "kkl" -> 0
                            "elm_usb" -> UsbStreams.probeElm(port) ?: 0
                            else -> when (val known = s.usbKind(id)) {
                                null -> (UsbStreams.probeElm(port) ?: 0).also { s.setUsbKind(id, it) }
                                0 -> UsbStreams.probeElm(port, quick = true)?.also { s.setUsbKind(id, it) } ?: 0
                                else -> known
                            }
                        }
                        if (baud > 0) {
                            Repo.adapterFound.value = "USB $id → ELM327 ($baud бод)"
                            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                            streams = UsbStreams(port)
                            // remembered as an ELM but silent now: another cable on the same chip, ask again next time
                            if (!elmSession(Elm327(streams.input, streams.output, streams), "USB") && kind == "auto") s.setUsbKind(id, 0)
                        } else if (kind == "elm_usb") {
                            Repo.link.value = LinkState.ERROR
                            Repo.linkMessage.value = "На USB не ELM327 (не отвечает на AT-команды). Это KKL-кабель? Выберите «Авто» или «KKL»"
                            delay(3000)
                        } else {
                            Repo.adapterFound.value = "USB $id → KKL"
                            session(klineOnly = true, obd = { kklObd(port) }) { _ -> KklTransport(port).also { it.open() } }
                        }
                    }
                }
            } catch (e: Exception) {
                SsmTrace.log("error: $e")
                // the wideband took this device (its connection was closed under us): not a cable fault
                if (SensorBoxLink.ecuLease?.permitted != false) {
                    Repo.link.value = LinkState.ERROR
                    Repo.linkMessage.value = e.message ?: "Ошибка связи"
                }
            } finally {
                runCatching { EcuLink.client?.close() }
                EcuLink.client = null
                runCatching { streams?.close() }
                runCatching { if (op2 != null) op2.close() else port.close() }
                runCatching { conn.close() }
                SensorBoxLink.releaseEcu()
                logger?.close(); logger = null
            }
            delay(1000)
        }
    }

    /**
     * USB devices the driver table does not list but whose chip vendor we know (FTDI, WCH CH340, Prolific,
     * Silicon Labs): KKL clones and rebadged cables (e.g. an OpenPort 1.3 with its own product id) come with ids
     * nobody listed.
     */
    private fun unknownSerial(usb: UsbManager, known: List<UsbSerialDriver>): List<UsbSerialDriver> {
        val names = known.map { it.device.deviceName }.toSet()
        return usb.deviceList.values.filter { it.deviceName !in names }.mapNotNull { d ->
            runCatching<UsbSerialDriver?> {
                when (d.vendorId) {
                    0x0403 -> FtdiSerialDriver(d)
                    0x1A86 -> com.hoho.android.usbserial.driver.Ch34xSerialDriver(d)
                    0x067B -> com.hoho.android.usbserial.driver.ProlificSerialDriver(d)
                    0x10C4 -> com.hoho.android.usbserial.driver.Cp21xxSerialDriver(d)
                    // not a generic CDC-ACM fallback: a head unit's internal 4G modem looks like one; a CANable by its name only
                    else -> if (kg.forester.sg.ssm.Slcan.matches(d.vendorId, d.productId, runCatching { d.productName }.getOrNull())) CdcAcmSerialDriver(d) else null
                }
            }.getOrNull()?.takeIf { it.ports.isNotEmpty() }
        }
    }

    private fun isOp2(d: UsbSerialDriver) = OpenPort2.matches(d.device.vendorId, d.device.productId)
    /**
     * By its ids, or by its name: an FTDI chip whose product id got wiped to 0000 (seen on an OpenPort 1.3,
     * typical of the FTDI driver "bricking" clone chips) still says "Tactrix OpenPort 1.3" and wants its RTS cleared.
     */
    private fun isOp13(d: UsbSerialDriver) = OpenPort13.matches(d.device.vendorId, d.device.productId) ||
        (d.device.vendorId == OpenPort13.VID && runCatching { d.device.productName }.getOrNull()?.contains("OpenPort 1.3", true) == true)

    /** A USB-CAN stick speaking slcan (CANable): the dash CAN of a sports ECU, not a diagnostic cable. */
    private fun isCanable(d: UsbSerialDriver) = kg.forester.sg.ssm.Slcan.matches(
        d.device.vendorId, d.device.productId, runCatching { d.device.productName }.getOrNull())

    private fun pickDriver(kind: String, drivers: List<UsbSerialDriver>): UsbSerialDriver? = when (kind) {
        // "OpenPort" picked for an OpenPort 1.3: it is a K-line cable like a KKL, just use it as one
        "op2" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) }
        "canable" -> drivers.firstOrNull { isCanable(it) }
        "auto" -> drivers.firstOrNull { isOp2(it) } ?: drivers.firstOrNull { isOp13(it) } ?: drivers.firstOrNull { isCanable(it) } ?: drivers.firstOrNull()
        else -> drivers.firstOrNull { !isOp2(it) && !isCanable(it) }
    }

    private var lastUsbList = ""

    /** Every USB device, recognised or not, once per change: shows in a sent log which cable someone has. */
    private fun logUsbDevices(usb: UsbManager, drivers: List<UsbSerialDriver>) {
        val known = drivers.map { it.device.deviceName }.toSet()
        val list = usb.deviceList.values.joinToString("; ") { d ->
            "%04X:%04X %s %s%s".format(
                d.vendorId, d.productId, d.manufacturerName ?: "", d.productName ?: "",
                if (d.deviceName in known) " [serial]" else "",
            ).trim()
        }
        if (list != lastUsbList) {
            lastUsbList = list
            SsmTrace.log("USB: ${list.ifEmpty { "нет устройств" }}")
        }
    }

    /** Demo data instead of a cable; trips, fuel and logs are left alone. */
    private suspend fun demoLoop() {
        // nothing learned from the car carries into the demo, nothing from the demo into the car (see the end)
        guard.reset()
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Демо-данные (без машины)"
        knock.reset(System.currentTimeMillis(), record = false)
        // a trip counted in memory only (never saved), so the fuel screen shows something in the demo
        val demoStart = System.currentTimeMillis()
        var demoTrip = kg.forester.sg.data.Trip(startMs = demoStart, endMs = demoStart, distanceKm = 0.0, fuelL = 0.0)
        var lastDemo = demoStart
        while (scope.isActive && Repo.settings.demo) {
            val now = System.currentTimeMillis()
            val (snap, tcu) = Demo.ecu(now)
            Repo.live.value = snap
            addHistory(snap)
            knock.process(snap, record = false)
            extras(snap, now)
            if (Repo.settings.gearbox != "mt") {
                Repo.tcu.value = lockUp(tcu, snap.rpm, now)
                Repo.tcuStatus.value = "АКПП: демо"
                tcuHistory.addLast(tcu)
                while (tcuHistory.size > 120) tcuHistory.removeFirst()
                Repo.tcuHistory.value = tcuHistory.toList()
            }
            val bearing = if ((snap.speedKmh ?: 0) > 3) Demo.bearing(now) else null
            val (lat, lon) = Demo.position(now, (snap.speedKmh ?: 0).toFloat(), bearing)
            Repo.gps.value = GpsFix(
                speedKmh = (snap.speedKmh ?: 0).toFloat(), altitudeM = 195.0, accuracyM = 5f, satellites = 9,
                timeMs = now, bearing = bearing, lat = lat, lon = lon,
            )
            val lh = Demo.litersPerHour(snap)
            val v = snap.speedKmh ?: 0
            val tank = Repo.settings.tankL.takeIf { it > 0 } ?: 60.0
            val dtH = (now - lastDemo) / 3_600_000.0
            lastDemo = now
            val km = v * dtH; val l = lh * dtH
            demoTrip = demoTrip.copy(
                endMs = now, distanceKm = demoTrip.distanceKm + km, fuelL = demoTrip.fuelL + l,
                cityKm = demoTrip.cityKm + if (v in 3..59) km else 0.0, cityL = demoTrip.cityL + if (v in 3..59) l else 0.0,
                hwyKm = demoTrip.hwyKm + if (v >= 60) km else 0.0, hwyL = demoTrip.hwyL + if (v >= 60) l else 0.0,
                idleS = demoTrip.idleS + if (v < 3) dtH * 3600 else 0.0, idleL = demoTrip.idleL + if (v < 3) l else 0.0,
                maxRpm = maxOf(demoTrip.maxRpm, snap.rpm ?: 0),
            )
            val left = tank * 0.62
            Repo.trip.value = Repo.trip.value.copy(
                trip = demoTrip, instLh = lh, instPer100 = if (v >= 5) lh / v * 100 else null, fuelLeftL = left,
                // as with the car: the trip's first minutes (a jam, a hard pull) must not swing the range
                rangeKm = left / FuelCalc.rangePer100(demoTrip, emptyList()) * 100,
            )
            delay(250)
        }
        // demo off: none of its made-up numbers may stay on the screen as if they were the car's
        Repo.live.value = EcuSnapshot()
        Repo.tcu.value = null
        history.clear(); Repo.history.value = emptyList()
        tcuHistory.clear(); Repo.tcuHistory.value = emptyList()
        Repo.gps.value = null
        Repo.trip.value = kg.forester.sg.data.TripState()
        Repo.guard.value = GuardState()
        guard.reset()
        RulesHost.restore(this) // this car's own idle samples, not the demo's
        Repo.link.value = LinkState.NO_CABLE
    }

    /**
     * A Bluetooth adapter over the serial port profile: an ELM327 (and clones), or a Multitronics trip computer
     * ([multitronics], or tried when the device does not answer as an ELM327). Returns false if it did not connect.
     */
    @SuppressLint("MissingPermission")
    private suspend fun bluetoothSession(addr: String, label: String, quiet: Boolean = false, multitronics: Boolean = false): Boolean {
        val name = label.ifEmpty { addr }
        if (multitronics) {
            val sock = btSocket(addr, label, quiet) ?: return false
            if (!multitronicsSession(sock, name)) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "$name не отвечает как Multitronics"
            }
            return true
        }
        val sock = btSocket(addr, label, quiet) ?: return false
        Repo.adapterFound.value = "Bluetooth $name → ELM327"
        if (elmSession(Elm327(sock.inputStream, sock.outputStream, sock), "Bluetooth")) return true
        // not an ELM327: perhaps a Multitronics box (on a new connection: the ELM's reset went to it as noise)
        SsmTrace.log("$name: not an ELM327, trying Multitronics")
        val again = btSocket(addr, label, quiet = true) ?: return true
        multitronicsSession(again, name)
        return true
    }

    /** A serial port profile connection to the paired device [addr]; null (with the reason on screen) if none. */
    @SuppressLint("MissingPermission")
    private fun btSocket(addr: String, label: String, quiet: Boolean): android.bluetooth.BluetoothSocket? {
        if (addr.isEmpty()) {
            Repo.link.value = LinkState.NO_CABLE
            Repo.linkMessage.value = "Выберите Bluetooth-адаптер: Настройки → «Авто и адаптер»"
            return null
        }
        if (!btAllowed()) {
            Repo.link.value = LinkState.NO_PERMISSION
            Repo.linkMessage.value = "Нет разрешения на Bluetooth (Настройки → «Авто и адаптер»)"
            return null
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            if (!quiet) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Bluetooth выключен"
            }
            return null
        }
        val name = label.ifEmpty { addr }
        if (!quiet) Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "Подключение к $name…"
        SsmTrace.log("=== Bluetooth $name $addr, шина ${Repo.settings.bus}")
        val spp = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val dev = bt.getRemoteDevice(addr)
        runCatching { bt.cancelDiscovery() }
        val sock = runCatching { dev.createRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .recoverCatching { dev.createInsecureRfcommSocketToServiceRecord(spp).also { it.connect() } }
            .getOrElse { e ->
                SsmTrace.log("bt connect: $e")
                if (!quiet) {
                    Repo.link.value = LinkState.NO_CABLE
                    Repo.linkMessage.value = "Адаптер $name не отвечает (включено зажигание?)"
                }
                return null
            }
        return sock
    }

    /**
     * A Multitronics trip computer (MPC-800) on Bluetooth: it reads the car itself, we ask it for what it read, about
     * five times a second. False if it did not answer as one (the socket is closed either way).
     */
    private suspend fun multitronicsSession(sock: android.bluetooth.BluetoothSocket, name: String): Boolean {
        val box = kg.forester.sg.ssm.MultitronicsBox(sock.inputStream, sock.outputStream)
        val mt = kg.forester.sg.ssm.Multitronics
        try {
            Repo.link.value = LinkState.CONNECTING
            Repo.linkMessage.value = "Подключение к $name (Multitronics)…"
            // its app asks once a second; a box still busy with the noise of an ELM's reset gets a few tries
            val info = (0 until 4).firstNotNullOfOrNull { runCatching { box.request(mt.IDENTIFY, 1200) }.getOrNull()?.let { mt.info(it) } }
                ?: return false
            SsmTrace.log("Multitronics: ${info.model}, «${info.version}», build ${info.build}")
            // the build its app 02.10.2026 is made for: another may lay the values out otherwise
            if (info.build != 83) SsmTrace.log("Multitronics: build ${info.build}, the values are read as for 83")
            runCatching { box.request(mt.clock(), 600) }
            Repo.adapterFound.value = "Bluetooth $name → Multitronics ${info.model}"
            ecuWatch.clear(); tcuWatch.clear()
            Repo.obdMode.value = false
            Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
            Repo.tcuStatus.value = "АКПП: нет (данные Multitronics)"
            mt.active = true
            val seen = HashSet<String>()
            var failures = 0
            while (scope.isActive && failures < 5 && !Repo.settings.demo && Repo.settings.adapter.let { it == "mt_bt" || it == "auto" || it == "elm_bt" }) {
                val now = System.currentTimeMillis()
                val f = box.request(mt.STATUS, 1000)?.takeIf { mt.isStatus(it) }
                if (f == null) { failures++; tracker.tick(now); continue }
                failures = 0
                val v = mt.values(f, seen)
                mt.publish(v)
                Repo.link.value = LinkState.CONNECTED
                Repo.linkMessage.value = "Multitronics ${info.model} · ${info.version}" + mt.protocol(f).takeIf { it > 0 }?.let { " · протокол $it" }.orEmpty()
                val params = mt.params(v)
                if (Repo.rrAvailable.value != params) Repo.rrAvailable.value = params
                Repo.rrPolled.value = params
                Repo.rrValues.value = mt.extras(v)
                Repo.pollAddresses.value = v.size
                onSnapshot(mt.snapshot(v, now, Repo.settings.tankL), now)
                delay((200 - (System.currentTimeMillis() - now)).coerceAtLeast(0))
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Multitronics не отвечает"
            return true
        } catch (e: Exception) {
            SsmTrace.log("Multitronics: $e")
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = "Связь с Multitronics потеряна"
            return true
        } finally {
            mt.reset()
            Repo.rrAvailable.value = emptyList(); Repo.rrPolled.value = emptyList(); Repo.rrValues.value = emptyMap()
            logger?.close(); logger = null
            runCatching { sock.close() }
        }
    }

    private fun btAllowed() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * "Auto" without anything on USB: the chosen Bluetooth adapter, or else any paired device named like an
     * OBD adapter (the one that answers is remembered).
     */
    @SuppressLint("MissingPermission")
    private suspend fun autoBluetooth(): Boolean {
        if (!btAllowed()) return false
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return false
        val s = Repo.settings
        if (s.btAddress.isNotEmpty()) return bluetoothSession(s.btAddress, s.btName, quiet = true)
        val candidates = runCatching { bt.bondedDevices.toList() }.getOrDefault(emptyList())
            .filter { d -> OBD_NAMES.any { (d.name ?: "").uppercase().contains(it) } }
        for (d in candidates) {
            if (bluetoothSession(d.address, d.name ?: "", quiet = true)) {
                s.btAddress = d.address; s.btName = d.name ?: d.address
                return true
            }
        }
        return false
    }

    /**
     * Wi-Fi ELM327: the head unit joins the adapter's own network, the adapter listens on TCP (192.168.0.10:35000
     * on nearly all of them). The socket is bound to that Wi-Fi, which has no internet and is otherwise skipped
     * by Android. With [explicit] false ("Auto") it is only tried when we are on the adapter's subnet.
     */
    private suspend fun wifiSession(explicit: Boolean): Boolean {
        val (host, port) = Repo.settings.wifiHost.split(':').let { it[0].trim() to (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 35000) }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi == null) {
            if (explicit) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Нет Wi-Fi: подключитесь к сети адаптера (обычно WiFi_OBDII, V-LINK…)"
            }
            return false
        }
        if (!explicit) {
            val prefix = host.substringBeforeLast('.') + "."
            val sameNet = cm.getLinkProperties(wifi)?.linkAddresses?.any { it.address.hostAddress?.startsWith(prefix) == true } == true
            if (!sameNet) return false
        }
        Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "Подключение к Wi-Fi адаптеру $host:$port…"
        SsmTrace.log("=== Wi-Fi $host:$port, шина ${Repo.settings.bus}")
        val sock = runCatching {
            wifi.socketFactory.createSocket().also {
                it.connect(java.net.InetSocketAddress(host, port), 2000)
                it.tcpNoDelay = true
            }
        }.getOrElse { e ->
            SsmTrace.log("wifi connect: $e")
            if (explicit) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Wi-Fi адаптер $host:$port не отвечает"
            }
            return false
        }
        Repo.adapterFound.value = "Wi-Fi $host:$port → ELM327"
        elmSession(Elm327(sock.getInputStream(), sock.getOutputStream(), sock), "Wi-Fi")
        return true
    }

    /**
     * An ELM327 on any link: SSM on the K-line or CAN first, standard OBD-II when the car does not answer SSM.
     * Returns false if the adapter did not answer as an ELM327 at all.
     */
    private suspend fun elmSession(elm: Elm327, via: String): Boolean {
        try {
            try {
                elm.open()
            } catch (e: Exception) {
                SsmTrace.log("ELM ($via) does not answer: $e")
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "Адаптер не отвечает как ELM327"
                return false
            }
            SsmTrace.log("ELM ($via): ${elm.version}")
            session(obd = { ElmObd(elm).takeIf { it.open() } }) { kline ->
                if (kline) ElmKLineTransport(elm).also { it.open() } else ElmCanTransport(elm).also { it.open() }
            }
            return true
        } catch (e: Exception) {
            SsmTrace.log("error: $e")
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = e.message ?: "Ошибка связи"
            return true
        } finally {
            EcuLink.client?.close()
            EcuLink.client = null
            elm.close()
            logger?.close(); logger = null
        }
    }

    private fun openOp2(op2: OpenPort2, kline: Boolean): SsmTransport =
        if (kline) Op2KLineTransport(op2).also { it.open() } else Op2CanTransport(op2).also { it.open(Repo.settings.canListen) }

    /**
     * One adapter session: find the bus the ECU answers on, init it, then poll until the cable or ECU goes away.
     * [open] makes a transport for the K-line (true) or CAN (false). [obd], when the adapter can do it, makes a
     * standard OBD-II link that is tried after a full round of silence on SSM.
     */
    private suspend fun session(
        klineOnly: Boolean = false,
        obd: (() -> ObdLink?)? = null,
        open: (Boolean) -> SsmTransport,
    ) {
        val buses = when {
            klineOnly -> listOf(true) // a KKL cable has no CAN
            Repo.settings.bus == "kline" -> listOf(true)
            Repo.settings.bus == "can" -> listOf(false)
            else -> listOf(true, false)
        }
        var busIdx = 0
        var lineMode = 0
        var silentRounds = 0
        // the K-line OBD try (its init pulses the line with breaks): when it last ran, and whether this round heard an echo
        var obdTriedMs = 0L
        var echo = false
        // the ECU answered in this session, and the current silent round heard our echo: see the reopen below
        var ecuSpoke = false
        var roundEcho = false
        var transport: SsmTransport? = null
        var transportKline = true
        // demo switched on: leave the car (the demo loop takes over)
        while (scope.isActive && !Repo.settings.demo && SensorBoxLink.ecuLease?.permitted != false) {
            val kline = buses[busIdx % buses.size]
            if (transport == null || transportKline != kline) {
                transport?.close()
                transport = null
                transport = open(kline)
                transportKline = kline
            }
            val client = SsmClient(transport)
            EcuLink.client = client
            // a laptop already on the line: listen to it before we even say hello
            if (transport.passive) { passiveRound(transport, EcuParams.pollAddresses, emptySet()); continue }
            val answer = EcuLink.mutex.withLock { client.init() }
            if (answer == null && transport.passive) continue
            // no answer to the diagnostics, but the bus talks by itself: show what it says
            if (answer == null && !kline && Repo.settings.canListen && kg.forester.sg.ssm.CanBroadcast.fresh()) {
                canOnlyRound()
                continue
            }
            if (answer == null) {
                Repo.link.value = LinkState.NO_ECU
                // other makes (Nissan Consult, Toyota…) never answer SSM: say so instead of letting them wait
                Repo.linkMessage.value = "ЭБУ не отвечает (${client.transport.name}). Зажигание включено? " +
                    "Приложение работает только с Subaru (протокол SSM)"
                tracker.tick(System.currentTimeMillis())
                // next: other DTR/RTS levels for a cable with a chip inside, else the other bus
                val modes = transport.lineModes
                val roundDone = if (modes > 1) {
                    transport.setLineMode(++lineMode)
                    lineMode % modes == 0
                } else {
                    busIdx++
                    busIdx % buses.size == 0
                }
                if (roundDone) silentRounds++
                val kl = transport as? kg.forester.sg.ssm.KLineTransport
                if (kl?.heardEcho == true) { echo = true; roundEcho = true }
                // a KKL cable echoing on a live line, the ECU that spoke a minute ago silent to every request: on a
                // Legacy BP with an FTDI cable it stayed so for minutes and answered at once after the cable was
                // plugged in again. Do the same once: close the USB device, the caller opens it afresh.
                if (roundDone && klineOnly && ecuSpoke && roundEcho) {
                    SsmTrace.log("ECU silent while the cable echoes: reopening the cable")
                    Repo.linkMessage.value = "ЭБУ молчит, переподключаю кабель…"
                    return
                }
                if (roundDone) roundEcho = false
                // the K-line OBD init (fast and 5-baud, the line pulsed with breaks) only on a live K-line: the cable heard its
                // own SSM requests back (no echo: the ignition off, or not a K-line at all but a head unit's own USB-serial
                // chip, which pulses hung the USB of some units), never for a Subaru that has spoken SSM through this app
                // (its silence is the ignition off), and at most every two minutes
                val obdOk = kl == null || (echo && Repo.settings.lastRomId.isEmpty() && System.currentTimeMillis() - obdTriedMs > 120_000)
                if (roundDone) { kl?.heardEcho = false; if (kl != null && !obdOk) SsmTrace.log("K-line OBD try skipped: echo $echo, SSM car ${Repo.settings.lastRomId.isNotEmpty()}") }
                if (roundDone && obd != null && obdOk && (silentRounds == 1 || silentRounds % 3 == 0)) {
                    if (kl != null) obdTriedMs = System.currentTimeMillis()
                    echo = false
                    // the OBD link sets the adapter up its own way; SSM starts afresh after it
                    transport.close()
                    transport = null
                    EcuLink.client = null
                    Repo.linkMessage.value = "SSM молчит, пробую стандартный OBD-II…"
                    val link = runCatching { obd() }.onFailure { SsmTrace.log("obd: $it") }.getOrNull()
                    if (link != null && obdSession(link)) silentRounds = 0
                    continue
                }
                delay(if (roundDone) 3000 else 300)
                continue
            }
            silentRounds = 0
            ecuSpoke = true
            Repo.obdMode.value = false
            // with the sensors the driver says this ECU has although its list leaves them out (old JECS)
            val capsSeen = Repo.capsVersion.value
            val init = kg.forester.sg.data.EcuCaps.apply(answer)
            if (init !== answer) SsmTrace.log("capability bits set by hand: ${kg.forester.sg.data.EcuCaps.forced(init.romId).joinToString(" ") { "${it.first}.${it.second}" }}")
            Repo.ecuInitRaw.value = answer
            Repo.ecuInit.value = init
            runCatching { carFromEcu(init.romId) }
            Repo.link.value = LinkState.CONNECTED
            val car = kg.forester.sg.ssm.EcuIds.car(this, init.romId)
            Repo.linkMessage.value = "ROM ${init.romId}" + (car?.let { " ($it)" } ?: "") + " · ${client.transport.name}"
            RomRaider.load(this)
            RomRaider.siblingOf(init.romId)?.let { SsmTrace.log("no extended parameters for ROM ${init.romId}, using sibling $it") }
            SsmTrace.log("ECU init: SSM ${init.ssmId} ROM ${init.romId} caps ${init.capabilities.toHex(" ")}")
            val addrs = choosePollAddresses(client, init)
            if (addrs == null) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "ЭБУ ${init.romId} ответил на init, но не отдаёт параметры"
                delay(3000)
                continue
            }
            Repo.pollAddresses.value = addrs.size
            runCatching { connectChecks(client, init) }.onFailure { SsmTrace.log("connect checks: $it") }
            ecuWatch.setup(init)
            tcuWatch.clear()
            // an engine without a turbo (no wastegate in the init) on the untouched factory screen: no empty boost cards
            if (Repo.settings.homeTiles.isEmpty() && !EcuParams.reported(init, EcuParams.WGDC)) {
                Repo.settings.homeTiles = kg.forester.sg.data.HomeLayout.atmoDefault()
            }
            val afSensor = EcuParams.reported(init, EcuParams.AF_SENSOR)
            val skip = EcuParams.unsupported(init, addrs)
            if (skip.isNotEmpty()) SsmTrace.log("not supported by this ECU, ignored: ${skip.joinToString(" ") { "%02X".format(it) }}")
            var failures = 0
            var cycle = 0
            tcuAddrs = null
            tcuNextTryMs = 0
            SsmTrace.polling = true
            // «fast poll» (K-line): the ECU sends the values by itself, three times as often; off for this connection
            // when it doesn't
            var fast = Repo.settings.fastPoll && transport is kg.forester.sg.ssm.KLineTransport
            val fastFrames = Channel<ByteArray>(Channel.CONFLATED)
            var fastAddrs: IntArray = addrs
            var fastFrameSeen = false
            var fastRun = 0
            var fastTcuMs = 0L
            var fastWatchMs = 0L
            /** Plain requests: the ticked parameters go in the regular read until that is refused once. */
            var joinWatch = true
            try { while (scope.isActive && failures < 5 && !Repo.settings.demo && capsSeen == Repo.capsVersion.value &&
                SensorBoxLink.ecuLease?.permitted != false) {
                if (transport.passive) {
                    // the other tester's request has stopped the stream already
                    if (client.streaming) client.stopStream()
                    passiveRound(transport, addrs, skip, afSensor)
                    continue
                }
                if (wasPassive) {
                    wasPassive = false
                    SsmTrace.log("the other tester went quiet: polling again")
                    Repo.linkMessage.value = "ROM ${init.romId} · ${client.transport.name}"
                }
                if (fast && !Repo.settings.fastPoll) { fast = false; EcuLink.mutex.withLock { client.stopStream() } }
                if (fast) {
                    // the ticked parameters ride along in the stream, but each new list is read on its own first:
                    // an address this ECU doesn't know would silence the whole stream, not just them
                    if (ecuWatch.refresh() && client.streaming) EcuLink.mutex.withLock { client.stopStream() }
                    // ticked ones that didn't answer get another try now and then (a manual gearbox never stops the stream)
                    if (client.streaming && !ecuWatch.answers && ecuWatch.addresses.isNotEmpty() &&
                        System.currentTimeMillis() - fastWatchMs > 10_000) EcuLink.mutex.withLock { client.stopStream() }
                    if (!client.streaming) {
                        fastWatchMs = System.currentTimeMillis()
                        // a list already read on its own needs no second read after each transmission request: that
                        // would leave the stream silent for one more request every N × 250 ms
                        if (!ecuWatch.answers) ecuWatch.poll(client)
                        val extra = if (ecuWatch.answers) ecuWatch.addresses.filter { it !in addrs } else emptyList()
                        val room = client.transport.maxReadAddrs - addrs.size
                        if (extra.size > room) SsmTrace.log("fast poll: ${extra.size - room} watched addresses left out")
                        fastAddrs = addrs + extra.take(room.coerceAtLeast(0))
                        while (fastFrames.tryReceive().isSuccess) Unit
                        val read = fastAddrs
                        fastRun = 0
                        // refused (another tester took the line, a list too long): plain requests for this connection,
                        // or the wait below would go round forever with nothing coming
                        if (!EcuLink.mutex.withLock { client.startStream(read) { fastFrames.trySend(it) } }) {
                            fast = false
                            SsmTrace.log("fast poll: stream not started, plain requests")
                            continue
                        }
                    }
                    val raw = withTimeoutOrNull(1500) { fastFrames.receive() }
                    val now = System.currentTimeMillis()
                    if (raw == null) {
                        // the cable was taken from us (its connection closed): nothing more will come
                        if (SensorBoxLink.ecuLease?.permitted == false) break
                        // stopped by another request in between (trouble codes, a write): start it again
                        if (!client.streaming) continue
                        EcuLink.mutex.withLock { client.stopStream() }
                        // no answers, or one or two as to a plain request: this ECU doesn't stream
                        if (fastRun < 3) {
                            fast = false
                            SsmTrace.log("fast poll: no stream from this ECU ($fastRun answers), plain requests")
                        } else {
                            failures++
                            tracker.tick(now)
                        }
                        continue
                    }
                    failures = 0
                    fastRun++
                    if (!fastFrameSeen) { fastFrameSeen = true; SsmTrace.log("fast poll: ${fastAddrs.size} addresses") }
                    if (cycle == 0) logFirstRead(addrs, raw)
                    var snap = EcuParams.decode(fastAddrs, raw, now, skip)
                    if (!afSensor) snap = snap.copy(lambda = null)
                    ecuWatch.take(fastAddrs, raw)
                    onSnapshot(snap, now)
                    cycle++
                    // the transmission in between, about as often as with plain requests (N engine reads of ~250 ms
                    // between two of its own); its request stops the stream, the next round starts it again. The gap
                    // counts from the end of the last one, so the stream always runs N × 250 ms in between
                    if (Repo.settings.gearbox != "mt" && now - fastTcuMs >= Repo.settings.tcuEvery.coerceAtLeast(1) * 250L) {
                        pollTcu(client, now)
                        fastTcuMs = System.currentTimeMillis()
                    }
                    continue
                }
                // the ticked parameters that answered on their own ride in the same request, as in the stream: read
                // apart, they were a request later than the regular values (load_grev and P200 differed in the log)
                ecuWatch.refresh()
                val extra = if (joinWatch && ecuWatch.answers) ecuWatch.addresses.filter { it !in addrs }.toIntArray() else IntArray(0)
                var read = addrs + extra
                var raw = EcuLink.mutex.withLock { client.read(read) }
                if (raw == null && extra.isNotEmpty() && !transport.passive) {
                    joinWatch = false
                    SsmTrace.log("watched addresses refused together with the regular ones: read apart")
                    read = addrs
                    raw = EcuLink.mutex.withLock { client.read(read) }
                }
                val now = System.currentTimeMillis()
                if (raw == null && transport.passive) continue
                if (raw == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                if (cycle == 0) logFirstRead(addrs, raw)
                var snap = EcuParams.decode(read, raw, now, skip)
                // SSM answers any address; an A/F byte from an ECU without the wideband sensor would fake
                // "enrichment" in the fuel calculation
                if (!afSensor) snap = snap.copy(lambda = null)
                if (read.size > addrs.size) ecuWatch.take(read, raw) else ecuWatch.poll(client)
                onSnapshot(snap, now)
                if (++cycle % Repo.settings.tcuEvery.coerceAtLeast(1) == 0 && Repo.settings.gearbox != "mt") pollTcu(client, now)
            } } finally {
                // the ECU would go on sending to nobody; whoever comes next starts from a quiet line (a cable taken
                // from us has no line left to quiet)
                if (client.streaming && SensorBoxLink.ecuLease?.permitted != false) runCatching { client.stopStream() }
            }
            SsmTrace.polling = false
            if (capsSeen != Repo.capsVersion.value) {
                SsmTrace.log("capability bits changed: connecting again")
                continue
            }
            if (SensorBoxLink.ecuLease?.permitted == false) {
                SsmTrace.log("the USB device went to the wideband: polling stopped")
                logger?.close(); logger = null
                continue
            }
            SsmTrace.log("ECU silent, polling stopped")
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            logger?.close(); logger = null
        }
    }

    private var wasPassive = false

    /** What the ECU really sends for the values some early ECUs lack (MAF, throttle, injection), skipped or not. */
    private fun logFirstRead(addrs: IntArray, raw: ByteArray) {
        val probe = intArrayOf(EcuParams.MAF_H, EcuParams.MAF_L, EcuParams.THROTTLE, EcuParams.INJ_PW)
        SsmTrace.log("first read, raw: " + probe.joinToString(" ") { a ->
            val i = addrs.indexOf(a)
            "%02X=%s".format(a, if (i in raw.indices) "%02X".format(raw[i].toInt() and 0xFF) else "--")
        })
    }

    /** Only listening to CAN: no unit answers the diagnostics, the values come from what is broadcast. */
    private suspend fun canOnlyRound() {
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Слушаю CAN без диагностики: видно кадров ${kg.forester.sg.ssm.CanBroadcast.idsSeen}"
        val now = System.currentTimeMillis()
        onSnapshot(kg.forester.sg.ssm.CanBroadcast.snapshot(now), now)
        delay(250)
    }

    /**
     * «Тихий режим»: another tester (a laptop on a second cable) polls the ECU, so we keep quiet and take the values
     * it gets. Only what it asks for comes through; the rest stays empty until the line is ours again.
     */
    private suspend fun passiveRound(transport: kg.forester.sg.ssm.SsmTransport, addrs: IntArray, skip: Set<Int>, afSensor: Boolean = true) {
        if (!wasPassive) {
            wasPassive = true
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "Тихий режим: на K-линии второй диагност (ноутбук). Не мешаю ему, читаю то, что он спрашивает"
            SsmTrace.log("passive: another tester polls the ECU")
        }
        val got = transport.sniffed(addrs, 1500)
        val now = System.currentTimeMillis()
        if (got != null) {
            var snap = EcuParams.decode(got.first, got.second, now, skip)
            if (!afSensor) snap = snap.copy(lambda = null)
            onSnapshot(snap, now)
        } else tracker.tick(now)
        delay(250)
    }

    /** Everything a fresh sample feeds: live values, charts, battery, trip and fuel, knock, CSV log. */
    private var historyMs = 0L

    /**
     * The charts' last minute: a point per ~250 ms, as the plain polling gives, kept by time. The fast poll's 12
     * samples a second would shrink the minute to 20 s and triple what every chart draws on each new sample.
     */
    private fun addHistory(snap: EcuSnapshot) {
        val t = snap.timeMs
        if (history.isNotEmpty() && t - historyMs in 0 until HISTORY_STEP_MS) return
        historyMs = t
        history.addLast(snap)
        while (history.size > 300 || (history.isNotEmpty() && t - history.first().timeMs > 60_000L)) history.removeFirst()
        Repo.history.value = history.toList()
    }

    private fun onSnapshot(raw0: EcuSnapshot, now: Long) {
        // what the CAN bus broadcasts fills what the diagnostics did not give
        val raw = if (Repo.settings.canListen) kg.forester.sg.ssm.CanBroadcast.fill(raw0, now) else raw0
        // the own CAN channels heard on the same bus: read like the ticked parameters (a CANable session has them already)
        if (Repo.settings.canListen && !kg.forester.sg.data.CanEcu.active) Repo.rrValues.value = kg.forester.sg.data.CanChannels.withValues(Repo.rrValues.value, now)
        val read = guard.withDirectMap(raw)
        val snap = read.saneMaf(kg.forester.sg.data.Scales.maf.top.value)
        if (snap !== read && !mafDropLogged) {
            mafDropLogged = true
            SsmTrace.log("MAF %.2f g/s at %s rpm is impossible, ignored".format(read.mafGs, read.rpm))
        }
        Repo.live.value = snap
        addHistory(snap)
        battery.process(snap)
        // the Link demo's made-up drive is no trip of this car (as the regular demo's isn't)
        if (!Repo.linkDemo.value) tracker.process(snap, Repo.gps.value) // first: a new trip clears the knock events
        kg.forester.sg.data.Scales.process(snap)
        knock.process(snap)
        extras(snap, now)
        if (Repo.settings.logEnabled && (snap.rpm ?: 0) > 0 && !Repo.linkDemo.value) {
            // the ticked parameters are extra columns: a new file when the choice changes
            val extras = LogColumns.extras(Repo.rrPolled.value, guard.roleIds())
            if (logger != null && (logger?.extras != extras || logger?.columns?.map { it.key } != LogColumns.enabled().map { it.key })) {
                logger?.close(); logger = null
            }
            val l = logger ?: CsvLogger(this, extras).also { logger = it; Repo.logFile.value = it.file.name }
            l.write(snap, Repo.gps.value, Repo.trip.value.instLh, Repo.tcu.value, Repo.rrValues.value)
        } else if (logger != null && (snap.rpm ?: 0) == 0) {
            logger?.close(); logger = null
        }
        updateNotification()
    }

    /**
     * The car is what its ECU says, nothing to set by hand: another ROM than last time is another car (fresh
     * boost, MAF and load scales), and the 3D car on the home screen follows it unless the driver chose one.
     */
    private fun carFromEcu(romId: String) {
        val s = Repo.settings
        if (s.lastRomId.isNotEmpty() && s.lastRomId != romId) {
            // another car: its own scales, IAM, usual boost and idle history
            kg.forester.sg.data.Scales.reset()
            RulesHost.forgetCar(this)
            guard.reset()
        }
        s.lastRomId = romId
        val known = kg.forester.sg.ssm.EcuIds.car(this, romId)
        // the gear guess picks its gearbox table by the car, the fuel count without a MAF its cylinders by the engine
        Gears.car = known
        FuelCalc.cylinders = (known?.let { kg.forester.sg.ssm.EngineId.fromCar(it) } ?: kg.forester.sg.ssm.EngineId.fromRomId(romId))?.cylinders ?: 4
        val car = known ?: return
        if (!s.car3dCustom) kg.forester.sg.ui.car3dFor(car)?.let { if (it != s.car3d) s.car3d = it }
        Repo.carVersion.value++
    }

    /**
     * The head unit still on long after the ignition went off (the ECU silent after a drive, the screen awake):
     * an ACC wire on constant 12 V or a unit that never sleeps empties the battery overnight. Said at 10 and 60 min.
     */
    private suspend fun headUnitWatch() {
        var ranMs = 0L
        var silentSince = 0L
        var said = 0
        val pm = getSystemService(android.os.PowerManager::class.java)
        while (scope.isActive) {
            delay(30_000)
            val now = System.currentTimeMillis()
            if (Repo.settings.demo) { ranMs = 0L; silentSince = 0L; said = 0; continue } // the demo's engine is no drive
            if (Repo.link.value == LinkState.CONNECTED) {
                if ((Repo.live.value.rpm ?: 0) > 400) ranMs = now
                silentSince = 0L; said = 0
                continue
            }
            if (ranMs == 0L || Repo.settings.demo || !Repo.settings.feature("battAlert")) continue
            if (silentSince == 0L) silentSince = now
            val off = (now - silentSince) / 60_000L
            if (pm?.isInteractive != true) continue
            if ((said == 0 && off >= 10) || (said == 1 && off >= 60)) {
                said++
                Alerts.raise("headUnitOn", "Связи с ЭБУ нет уже $off мин, а магнитола работает. Если зажигание выключено, она разряжает аккумулятор: " +
                    "за ночь он может сесть так, что машина не заведётся. Проверьте, что провод ACC отключается вместе с ключом", Alerts.Level.WARN, 30 * 60_000L)
            }
        }
    }

    /** Engine guard, G-meter, acceleration timer: everything that only reads the samples. */
    private fun extras(snap: EcuSnapshot, now: Long) {
        runCatching { guard.process(snap, Repo.tcu.value, now) }.onFailure { SsmTrace.log("guard: $it") }
        val s = Repo.settings
        if (s.feature("motion")) runCatching { Motion.process(snap, Repo.gps.value, now) }
        if (s.feature("perf")) runCatching { Perf.process(snap, Repo.gps.value, now) }.onFailure { SsmTrace.log("perf: $it") }
    }

    /**
     * A CANable on the dash CAN of a sports ECU (Link G4X…): nothing is asked, the ECU streams by itself. The Link's
     * Dash2Pro or Generic Dash is found on its own; anything else is read with the driver's own CAN channels.
     */
    private suspend fun canableSession(port: UsbSerialPort) {
        val kbit = Repo.settings.canKbit
        val bus = kg.forester.sg.ssm.SlcanPort(port)
        bus.open(kbit)
        SsmTrace.log("CANable: bus open at $kbit kbit/s")
        canSession(bus, kbit) { Repo.settings.canKbit == kbit }
    }

    /** The frames of [bus] (a CANable or the Link demo) through the Link's decoding, while [on]. */
    private suspend fun canSession(bus: kg.forester.sg.ssm.CanSource, kbit: Int, on: () -> Boolean) {
        val link = kg.forester.sg.ssm.LinkDash
        link.reset()
        kg.forester.sg.ssm.CanMonitor.clear()
        kg.forester.sg.data.CanChannels.clearValues()
        kg.forester.sg.data.CanEcu.reset()
        kg.forester.sg.data.CanEcu.active = true
        // the Link car is a car of its own: switching to it (or back to the stock ECU) starts its scales, usual boost
        // and idle history afresh, as another ROM does
        runCatching { carFromEcu("LINK") }
        // the untouched factory screen: the cards a Link fills, not the stock ECU's it leaves empty
        if (Repo.settings.homeTiles.isEmpty()) Repo.settings.homeTiles = kg.forester.sg.data.HomeLayout.linkDefault()
        ecuWatch.clear(); tcuWatch.clear()
        Repo.obdMode.value = false
        Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
        Repo.tcuStatus.value = "АКПП: нет (спортивный ЭБУ по CAN)"
        Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "CANable: шина $kbit кбит открыта, жду кадры"
        var lastFrame = System.currentTimeMillis()
        var lastSample = 0L
        var linkLogged = false
        var limitsLogged = emptyList<String>()
        try {
            while (scope.isActive && !Repo.settings.demo && on() && SensorBoxLink.ecuLease?.permitted != false) {
                val frames = bus.read(100)
                val now = System.currentTimeMillis()
                for (f in frames) {
                    val mux = link.feed(f.id, f.ext, f.data, now)
                    kg.forester.sg.ssm.CanMonitor.feed(f.id, f.ext, f.data, now, mux)
                    kg.forester.sg.data.CanChannels.feed(f.id, f.ext, f.data, now)
                }
                if (frames.isNotEmpty()) lastFrame = now
                if (now - lastSample < 200) continue
                lastSample = now
                if (now - lastFrame > 3000) {
                    Repo.link.value = LinkState.NO_ECU
                    Repo.linkMessage.value = "CANable на связи, но шина молчит. Зажигание включено? Скорость $kbit кбит та же, что в настройках CAN у ЭБУ?" +
                        if (bus.errors > 0) " Адаптер отвечает ошибкой (${bus.errors})" else ""
                    tracker.tick(now)
                    continue
                }
                val id = link.id
                if (id != null && !linkLogged) { linkLogged = true; SsmTrace.log("Link ${link.format?.title} on CAN id %03X (%d)".format(id, id)) }
                link.limits(now).takeIf { it != limitsLogged }?.let { limitsLogged = it; if (it.isNotEmpty()) SsmTrace.log("Link limits: ${it.joinToString()}") }
                Repo.link.value = LinkState.CONNECTED
                Repo.linkMessage.value = if (id != null) "Link (${link.format?.title}, ID %d) · CANable %d кбит".format(id, kbit)
                    else "CANable: на шине ${kg.forester.sg.ssm.CanMonitor.idsSeen(now)} ID, поток Link (Dash2Pro или Generic Dash) не найден. Что идёт по CAN: Настройки, Авто и адаптер"
                Repo.rrAvailable.value = kg.forester.sg.data.CanEcu.params()
                Repo.rrPolled.value = Repo.rrAvailable.value
                Repo.rrValues.value = kg.forester.sg.data.CanEcu.values(now)
                Repo.pollAddresses.value = Repo.rrAvailable.value.size
                val snap = if (link.fresh(now)) link.snapshot(now) else EcuSnapshot(timeMs = now)
                onSnapshot(snap, now)
            }
        } finally {
            kg.forester.sg.data.CanEcu.active = false
            bus.close()
            Repo.rrAvailable.value = emptyList(); Repo.rrPolled.value = emptyList(); Repo.rrValues.value = emptyMap()
            Repo.link.value = LinkState.NO_CABLE
        }
    }

    /** Standard OBD-II polling until the ECU goes silent. Returns false if the ECU did not answer at all. */
    private suspend fun obdSession(link: ObdLink): Boolean {
        val obd = Obd2(link)
        try {
            if (EcuLink.mutex.withLock { runCatching { obd.init() }.getOrDefault(false) } != true) return false
            EcuLink.obd = obd
            ecuWatch.clear(); tcuWatch.clear()
            Repo.obdMode.value = true
            Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
            Repo.tcuStatus.value = "АКПП: недоступна в режиме OBD-II"
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "OBD-II · ${link.name}: основные параметры (SSM не ответил)"
            // the VIN once: the make for the trouble codes and OBDb, the car for the header
            val vin = EcuLink.mutex.withLock { runCatching { obd.vin() }.getOrNull() }
            SsmTrace.log("VIN (OBD 09 02): ${vin ?: "—"}")
            // a Subaru that has spoken SSM on this app before, caught by the K-line OBD try just as the ignition came on:
            // SSM gives it all, so back to it (one older than SSM2 has never answered it and stays here)
            if (link is kg.forester.sg.ssm.KLineObd && vin != null && Repo.settings.lastRomId.isNotEmpty() &&
                runCatching { kg.forester.sg.data.Brands.get(this).isSubaru(vin) }.getOrDefault(false)) {
                SsmTrace.log("K-line OBD: a Subaru that answers SSM, back to SSM")
                return true
            }
            if (vin != null) Repo.vin.value = vin
            // the unit's own name and calibration (mode 09), for Настройки → Основные instead of the SSM ids
            val ecuIds = EcuLink.mutex.withLock { runCatching { obd.ecuIds() }.getOrNull() }
            SsmTrace.log("ECU (OBD 09 0A/04): ${ecuIds ?: "—"}")
            Repo.obdEcu.value = ecuIds.orEmpty()
            var obdb = obdbPoller(vin)
            var obdbSeen = kg.forester.sg.ssm.Obdb.changed.value
            Repo.pollAddresses.value = obd.polledCount
            var failures = 0
            SsmTrace.polling = true
            while (scope.isActive && failures < 5 && !Repo.settings.demo && SensorBoxLink.ecuLease?.permitted != false) {
                val now = System.currentTimeMillis()
                // a set downloaded or switched on while connected
                if (obdbSeen != kg.forester.sg.ssm.Obdb.changed.value) {
                    obdbSeen = kg.forester.sg.ssm.Obdb.changed.value
                    obdb = obdbPoller(vin ?: Repo.vin.value)
                }
                val ticked = Repo.settings.watchIds
                // and what the guard reads by itself (the oil's PID 5C), ticked or not
                obd.watched = ticked + Repo.internalWatch.value
                obdb?.watched = ticked + Repo.internalWatch.value
                val avail = obd.extraParams() + (obdb?.params ?: emptyList())
                if (Repo.rrAvailable.value != avail) Repo.rrAvailable.value = avail
                Repo.rrPolled.value = avail.filter { it.id in ticked }
                val snap = EcuLink.mutex.withLock { runCatching { obd.poll(now) }.getOrNull() }
                if (snap == null) {
                    failures++
                    tracker.tick(now)
                    continue
                }
                failures = 0
                if (obdb != null) EcuLink.mutex.withLock { runCatching { obdb?.poll(link, now) }.onFailure { SsmTrace.log("OBDb: $it") } }
                Repo.rrValues.value = obd.extraValues() + (obdb?.values() ?: emptyMap())
                onSnapshot(snap, now)
                // a fast CAN adapter would go round 100 times a second: 5 a second is plenty
                delay((200 - (System.currentTimeMillis() - now)).coerceAtLeast(0))
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            return true
        } finally {
            SsmTrace.polling = false
            EcuLink.obd = null
            Repo.obdMode.value = false
            Repo.rrAvailable.value = emptyList(); Repo.rrPolled.value = emptyList(); Repo.rrValues.value = emptyMap()
            logger?.close(); logger = null
            runCatching { link.close() }
        }
    }

    /**
     * Standard OBD-II on a bare KKL cable (no ELM327), for a car that does not answer SSM: KWP2000 fast init, then the
     * 5-baud init (KWP2000 or ISO 9141-2 by the key bytes). The port goes back to the SSM transport afterwards.
     */
    private fun kklObd(port: UsbSerialPort): ObdLink? {
        Repo.linkMessage.value = "SSM молчит, пробую OBD-II по K-line (KWP2000, ISO 9141-2)…"
        val p = kg.forester.sg.ssm.UsbKLinePort(port)
        return runCatching { p.open(); kg.forester.sg.ssm.KLineObd.open(p) }.onFailure { SsmTrace.log("K-line OBD: $it") }.getOrNull()
            ?: run { p.close(); null }
    }

    /**
     * The OBDb set downloaded for this car (Настройки на «Датчиках»), when switched on: the model the driver picked,
     * else the only set of the VIN's make there is. CAN only (see [kg.forester.sg.ssm.Obdb]).
     */
    private fun obdbPoller(vin: String?): kg.forester.sg.ssm.Obdb.Poller? {
        val s = Repo.settings
        if (!s.obdbOn) return null
        val repo = s.obdbRepo.ifEmpty {
            val make = vin?.let { runCatching { kg.forester.sg.data.Brands.get(this).makeFromVin(it) }.getOrNull() } ?: return null
            val prefixes = kg.forester.sg.ssm.Obdb.makePrefixes(make.slug).map { it.lowercase() + "-" }
            kg.forester.sg.ssm.Obdb.dir(filesDir).listFiles()?.map { it.name.removeSuffix(".json") }
                ?.filter { n -> prefixes.any { n.lowercase().startsWith(it) } }?.singleOrNull() ?: return null
        }
        val f = kg.forester.sg.ssm.Obdb.file(filesDir, repo) ?: return null
        val year = vin?.let { kg.forester.sg.ssm.Obdb.modelYear(it) }
        val cmds = runCatching { kg.forester.sg.ssm.Obdb.parse(f.readText(), year) }.onFailure { SsmTrace.log("OBDb $repo: $it") }.getOrNull() ?: return null
        SsmTrace.log("OBDb $repo (${year ?: "год ?"}): ${cmds.size} requests, ${cmds.sumOf { it.signals.size }} signals")
        return kg.forester.sg.ssm.Obdb.Poller(cmds).takeIf { cmds.isNotEmpty() }
    }

    private var ecuAvail: List<RrParam> = emptyList()
    private var tcuAvail: List<RrParam> = emptyList()
    private val ecuWatch = Watch(SsmClient.ECU, tcu = false)
    private val tcuWatch = Watch(SsmClient.TCU, tcu = true)

    private fun publishWatch() {
        // the own CAN channels while an OpenPort listens to the bus: on «Датчики», cards and in the log as with a CANable
        val can = if (Repo.settings.canListen) kg.forester.sg.data.CanChannels.list().map { it.toParam() } else emptyList()
        Repo.rrAvailable.value = ecuAvail + tcuAvail + can
        Repo.rrPolled.value = ecuWatch.params + tcuWatch.params + can
    }

    /**
     * The parameters ticked on the «Датчики» screen that one unit has, read in a request of their own after the
     * regular one: an address this ECU does not know can then only cost these, not the gauges.
     */
    private inner class Watch(private val dest: Int, private val tcu: Boolean) {
        var params: List<RrParam> = emptyList()
            private set
        private var avail: List<RrParam> = emptyList()
        private var all: List<RrParam> = emptyList()
        private var addrs = IntArray(0)
        private var version = -1
        private var fails = 0

        /** The base and, for the engine unit, the driver's own values: a formula may use any of them. */
        private fun defs() = RomRaider.load(this@EcuService) +
            if (tcu) emptyList() else kg.forester.sg.data.CustomParams.list().map { it.toParam() }

        fun setup(init: EcuInit) {
            all = defs()
            profile = Repo.settings.loggerProfile
            avail = RomRaider.available(this@EcuService, init, tcu, profile)
            this.init = init
            custom = Repo.customVersion.value
            defsSeen = Repo.defsVersion.value
            if (tcu) tcuAvail = avail else ecuAvail = avail
            SsmTrace.log("RomRaider: ${avail.size} parameters for ${if (tcu) "TCU" else "ECU"} ROM ${init.romId}")
            version = -1
            params = emptyList()
            publishWatch()
        }

        fun clear() {
            avail = emptyList(); params = emptyList(); addrs = IntArray(0)
            if (tcu) tcuAvail = emptyList() else ecuAvail = emptyList()
            publishWatch()
        }

        private var internal: Set<String> = emptySet()
        private var init: EcuInit? = null
        private var custom = 0
        /** [Repo.defsVersion] the list was made with: the driver's own RomRaider files and their roles. */
        private var defsSeen = 0
        /** The SSM logger profile the list was made with (CarBerry 4.2 adds its channels for one ROM). */
        private var profile = "stock"

        /** What to read for the ticked parameters. */
        val addresses: IntArray get() = addrs
        /** The last read of [addresses] on their own was answered: they can join the engine unit's stream. */
        var answers = false
            private set

        /** Brings the list up to date with the ticks and the own addresses; true when it changed. */
        fun refresh(): Boolean {
            // own addresses or definition files added or edited while connected, or another logger profile: the list of what this unit has changes
            // (a file put into the open folder is noticed here, every few seconds)
            kg.forester.sg.data.EcuDefs.stamp(this@EcuService)
            val newProfile = !tcu && profile != Repo.settings.loggerProfile
            if (custom != Repo.customVersion.value || defsSeen != Repo.defsVersion.value || newProfile) {
                if (newProfile) {
                    profile = Repo.settings.loggerProfile
                    // the channels the old list had must not stay on the screens with their last values
                    Repo.rrValues.value = Repo.rrValues.value - avail.map { it.id }.toSet()
                }
                custom = Repo.customVersion.value
                defsSeen = Repo.defsVersion.value
                all = defs()
                init?.let { avail = RomRaider.available(this@EcuService, it, tcu, profile); if (tcu) tcuAvail = avail else ecuAvail = avail }
                version = -1
            }
            if (version == Repo.watchVersion.value && internal == Repo.internalWatch.value) return false
            version = Repo.watchVersion.value
            internal = Repo.internalWatch.value
            val ids = Repo.settings.watchIds + internal
            params = avail.filter { it.id in ids }
            addrs = RomRaider.readList(params, all)
            fails = 0
            answers = false
            publishWatch()
            return true
        }

        suspend fun poll(client: SsmClient) {
            refresh()
            if (addrs.isEmpty()) return
            val raw = EcuLink.mutex.withLock { client.read(addrs, dest) }
            if (raw == null) {
                answers = false
                if (++fails >= 3) prune(client)
                return
            }
            fails = 0
            answers = true
            take(addrs, raw)
        }

        /** The ticked parameters' values out of a read of [read] ([raw] in its order) that has their addresses. */
        fun take(read: IntArray, raw: ByteArray) {
            if (params.isEmpty()) return
            Repo.rrValues.value = Repo.rrValues.value + RomRaider.decode(params, read, raw, all)
        }

        /** The unit keeps refusing the request: keep only the parameters that answer on their own. */
        private suspend fun prune(client: SsmClient) {
            val ok = params.filter { p ->
                val a = RomRaider.readList(listOf(p), all)
                a.isNotEmpty() && EcuLink.mutex.withLock { client.read(a, dest) } != null
            }
            SsmTrace.log("RomRaider: dropped ${(params - ok.toSet()).joinToString(" ") { it.id }}")
            params = ok
            addrs = RomRaider.readList(params, all)
            fails = 0
            publishWatch()
        }
    }

    /**
     * The SG answers the whole list at once. Other ECUs stay silent when one address in the request is
     * unknown to them, so fall back to the addresses the init marks as supported, then probe one by one.
     */
    private suspend fun choosePollAddresses(client: SsmClient, init: EcuInit): IntArray? {
        suspend fun works(a: IntArray) = a.isNotEmpty() &&
            (1..2).any { EcuLink.mutex.withLock { client.read(a) } != null }

        val base = chooseBaseAddresses(client, init, ::works) ?: return null
        val extra = EcuParams.optionalSupported(init)
        if (extra.isNotEmpty() && works(base + extra)) return base + extra
        return base
    }

    private suspend fun chooseBaseAddresses(
        client: SsmClient, init: EcuInit, works: suspend (IntArray) -> Boolean,
    ): IntArray? {
        val full = EcuParams.pollAddresses
        if (works(full)) return full
        val caps = EcuParams.supported(init)
        SsmTrace.log("full list failed, capability list: ${caps.joinToString(" ") { "%02X".format(it) }}")
        if (caps.size < full.size && works(caps)) return caps
        Repo.linkMessage.value = "Подбираю параметры ЭБУ ${init.romId}…"
        val ok = full.filter { a -> EcuLink.mutex.withLock { client.read(intArrayOf(a)) } != null }.toIntArray()
        SsmTrace.log("probed: ${ok.joinToString(" ") { "%02X".format(it) }}")
        return ok.takeIf { it.isNotEmpty() && works(it) }
    }

    /**
     * Once per connection, only where the init says the ECU has it (FreeSSM's flag bytes, + 8 in our numbering):
     * the VIN (byte 36 bit 0: a 3-byte pointer at 0xDA, 17 ASCII bytes there) and the test mode connectors
     * (byte 11 bit 5: 0x61 bit 5). Joined green connectors make the check engine lamp blink and store false codes.
     */
    private suspend fun connectChecks(client: SsmClient, init: EcuInit) {
        Repo.vin.value = ""
        if (init.supports(44, 0)) {
            val p = EcuLink.mutex.withLock { client.read(intArrayOf(0xDA, 0xDB, 0xDC)) }
            if (p != null && p.size == 3) {
                val at = ((p[0].toInt() and 0xFF) shl 16) or ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
                val raw = EcuLink.mutex.withLock { client.read(IntArray(17) { at + it }) }
                val vin = raw?.map { (it.toInt() and 0xFF).toChar() }?.joinToString("")?.trim()
                if (vin != null && vin.length == 17 && vin.all { it.isLetterOrDigit() }) Repo.vin.value = vin
                SsmTrace.log("VIN: ${vin ?: "—"}")
            }
        }
        // on CAN the same unit also answers standard OBD-II: one request (mode 09 PID 02) for a VIN SSM did not give
        if (Repo.vin.value.isNullOrEmpty() && client.transport.isCan) {
            val r = EcuLink.mutex.withLock { runCatching { client.transport.exchange(SsmClient.ECU, byteArrayOf(0x09, 0x02)) }.getOrNull() }
            val vin = r?.takeIf { it.size > 3 && it[0] == 0x49.toByte() && it[1] == 0x02.toByte() }?.let { Obd2.vinOf(it.copyOfRange(3, it.size)) }
            SsmTrace.log("VIN (OBD 09 02): ${vin ?: "—"}")
            if (vin != null) Repo.vin.value = vin
        }
        if (init.supports(19, 5)) {
            val t = EcuLink.mutex.withLock { client.read(intArrayOf(0x61)) }
            if (t != null && t.isNotEmpty() && (t[0].toInt() shr 5) and 1 == 1) {
                Alerts.raise(
                    "testMode", "Замкнуты разъёмы тест-режима (зелёные, под рулём): «чек» мигает и ЭБУ может записать " +
                        "ложные ошибки. Разъедините их, если не проводите диагностику", Alerts.Level.WARN, 60 * 60_000L,
                )
            }
        }
    }

    /** The transmission unit answers at 0x18 on the same K-line; poll it every few ECU cycles. */
    private suspend fun pollTcu(client: SsmClient, now: Long) {
        var addrs = tcuAddrs
        if (addrs == null) {
            if (now < tcuNextTryMs) return
            val init = EcuLink.mutex.withLock { client.init(SsmClient.TCU) }
            if (init == null) {
                // MY1999–2000 (Impreza GC/GF, Forester SF, Legacy BE/BH early): the engine already speaks SSM2 on the
                // K-line, the TCU still the old SSM1 on its own line; FreeSSM can't read those either
                Repo.tcuStatus.value = "Блок АКПП не отвечает по SSM. На Subaru 1999–2000 годов (Impreza GC/GF, Forester SF, " +
                    "ранние Legacy BE/BH) он работает по старому протоколу SSM1 на другой линии, через K-line его не прочитать " +
                    "(FreeSSM тоже не видит). У механики блока нет, а через часть Bluetooth-адаптеров он недоступен (надёжнее KKL-кабель)"
                tcuNextTryMs = now + 60_000
                return
            }
            Repo.tcuInit.value = init
            tcuWatch.setup(init)
            addrs = TcuParams.supported(init)
            tcuAddrs = addrs
            tcuAtfOnly = false
            Repo.tcuStatus.value = "АКПП: ROM ${init.romId}"
        }
        if (addrs.isEmpty()) return
        // "only ATF": the temperature and the selector, a much shorter request
        if (Repo.settings.tcuAtfOnly != tcuAtfOnly) {
            tcuAtfOnly = Repo.settings.tcuAtfOnly
            addrs = TcuParams.supported(Repo.tcuInit.value ?: return).let { all ->
                if (tcuAtfOnly) all.filter { it == TcuParams.ATF_OLD || it == TcuParams.ATF_NEW || it == TcuParams.RANGE || it == TcuParams.GEAR }.toIntArray() else all
            }
            tcuAddrs = addrs
        }
        val raw = EcuLink.mutex.withLock { client.read(addrs, SsmClient.TCU) } ?: return
        tcuWatch.poll(client)
        val t = lockUp(TcuParams.decode(addrs, raw, now), Repo.live.value.rpm, now)
        Repo.tcu.value = t
        tcuHistory.addLast(t)
        while (tcuHistory.size > 120) tcuHistory.removeFirst()
        Repo.tcuHistory.value = tcuHistory.toList()
    }

    private var lockSlipSinceMs = 0L

    /**
     * Converter slip from the engine and turbine speeds, and the lock-up state checked against it: a TCU that
     * keeps commanding lock-up while the clutch still slips for seconds gets a warning (worn clutch, tired ATF).
     */
    private fun lockUp(t: TcuSnapshot, rpm: Int?, now: Long): TcuSnapshot {
        val slip = t.turbineRpm?.let { tr -> if (rpm != null && rpm > 800) (rpm - tr) * 100f / rpm else null }
        val slipping = (t.lockUpPct ?: 0f) > 60f && (slip ?: 0f) > 10f && (rpm ?: 0) > 1200
        if (!slipping) lockSlipSinceMs = 0L else if (lockSlipSinceMs == 0L) lockSlipSinceMs = now
        val lock = LockState.of(t.lockUpPct, slip, if (lockSlipSinceMs == 0L) 0L else now - lockSlipSinceMs)
        if (lock == LockState.SLIPPING && Repo.settings.feature("atf")) {
            Alerts.raise(
                "lockSlip", "Блокировка гидротрансформатора сильно буксует: проскальзывание %.0f %% при команде %.0f %%. Коробка греется и изнашивается, не нагружайте машину и проверьте ATF и ошибки АКПП"
                    .format(slip, t.lockUpPct), Alerts.Level.DANGER, 30 * 60_000L,
            )
        }
        return t.copy(slipPct = slip, lock = lock)
    }

    private fun forceUsbHostMode() {
        // This head unit's OTG controller can get stuck in device mode (seen after toggling USB debugging).
        val f = "/sys/devices/platform/soc/soc:aon/5fff0000.usb/mode_switch"
        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "[ \"\$(cat $f)\" = device ] && echo host > $f")).waitFor()
        }
    }

    private fun requestUsbPermission(usb: UsbManager, device: android.hardware.usb.UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usb.requestPermission(device, pi)
    }

    // ---- GPS ----

    @Volatile private var lastGpsFixMs = 0L
    @Volatile private var gpsSats = 0
    @Volatile private var gpsCn0: Float? = null
    @Volatile private var gpsStartedMs = 0L
    private var gpsShownMs = 0L

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val now = System.currentTimeMillis()
            lastGpsFixMs = now
            Repo.gpsStatus.value = ""
            // the time the fix was taken, not when it got here: the main thread hands it over 50–300 ms late, unevenly,
            // and the run timer counts between fixes
            val age = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
            val fix = GpsFix(
                speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f,
                altitudeM = l.altitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy else 99f,
                satellites = (l.extras?.getInt("satellites") ?: 0).takeIf { it > 0 } ?: gpsSats,
                timeMs = now - age.coerceIn(0L, 3000L),
                bearing = if (l.hasBearing()) l.bearing else null,
                lat = l.latitude, lon = l.longitude, cn0 = gpsCn0,
            )
            // every fix to the run timer; the screens a couple of times a second (a 10 Hz receiver would redraw them
            // ten times a second)
            if (Repo.settings.feature("perf")) TraceBuffer.fix(fix)
            if (now - gpsShownMs >= 450) { gpsShownMs = now; Repo.gps.value = fix }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { gpsCheck() }
        override fun onProviderDisabled(provider: String) { gpsCheck() }
    }

    /** Position from the network while GPS has nothing (indoors, just woken up): for the map only, no speed. */
    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (System.currentTimeMillis() - lastGpsFixMs < 30_000) return
            Repo.gps.value = GpsFix(
                speedKmh = 0f, altitudeM = l.altitude, accuracyM = if (l.hasAccuracy()) maxOf(l.accuracy, 50f) else 99f,
                satellites = gpsSats, timeMs = System.currentTimeMillis(), bearing = null, lat = l.latitude, lon = l.longitude,
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            gpsSats = (0 until status.satelliteCount).count { status.usedInFix(it) }
            gpsCn0 = (0 until status.satelliteCount).filter { status.usedInFix(it) }.map { status.getCn0DbHz(it) }
                .sortedDescending().take(4).takeIf { it.size == 4 }?.average()?.toFloat()
            Repo.gnssSats.value = gpsSats to status.satelliteCount
            if (System.currentTimeMillis() - lastGpsFixMs > 5000) {
                Repo.gpsStatus.value = "ищу спутники: видно ${status.satelliteCount}, в решении $gpsSats"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Repo.gpsStatus.value = "нет разрешения на геолокацию: откройте приложение и разрешите"
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(gpsListener) }
        runCatching { lm.removeUpdates(netListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        runCatching {
            // as often as the receiver gives (most 1 Hz, some 5–10 Hz): the run timer goes by GPS from 4 Hz on
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, gpsListener, Looper.getMainLooper())
        }.onFailure { SsmTrace.log("gps: $it") }
        runCatching { lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper())) }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, netListener, Looper.getMainLooper())
        }
        gpsStartedMs = System.currentTimeMillis()
        gpsCheck()
        SsmTrace.log("gps started, providers ${lm.allProviders}, enabled ${lm.getProviders(true)}")
    }

    /** Why there is no position, for the GPS card and the mini-map. */
    private fun gpsCheck() {
        if (System.currentTimeMillis() - lastGpsFixMs < 5000) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Repo.gpsStatus.value = when {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "нет разрешения на геолокацию: откройте приложение и разрешите"
            !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> "геолокация выключена в настройках Android"
            // keep the satellite count from GnssStatus, but never a stale "no permission" / "switched off"
            else -> Repo.gpsStatus.value.takeIf { it.startsWith("ищу") } ?: "ищу спутники…"
        }
    }

    /**
     * Called every half minute: re-subscribes when GPS has been silent for a minute (after the head unit's sleep
     * the GPS module comes back, but the old subscription often stays dead) and refreshes the status text.
     */
    private fun gpsWatchdog() {
        if (Repo.settings.demo) return
        val now = System.currentTimeMillis()
        if (now - lastGpsFixMs > 60_000 && now - gpsStartedMs > 60_000) {
            SsmTrace.log("gps silent for a minute, re-subscribing")
            startGps()
        } else gpsCheck()
    }

    // ---- notification ----

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // Android 7.1 (some head units claim 13 and run 7.1 underneath) has no channels
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "ЭБУ", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Подключение…")
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Android 14+ refuses some types (e.g. microphone when started at boot): keep the USB part at least.
            try {
                startForeground(1, n, type)
            } catch (e: Exception) {
                runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            }
        } else {
            startForeground(1, n)
        }
    }

    private var lastNotifMs = 0L
    private fun refreshNotification() {
        lastNotifMs = 0L
        updateNotification()
    }

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifMs < 5000) return
        lastNotifMs = now
        val t = Repo.trip.value
        val text = if (Repo.adapterReleased.value) "Адаптер отпущен для другой программы"
        else t.instPer100?.let { "%.1f л/100 км".format(it) } ?: "%.1f л/ч".format(t.instLh)
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .apply {
                // another diagnostic app (BTSSM…) wants the same adapter: let it go from here, and take it back
                if (!Repo.settings.demo) {
                    val (label, action) = if (Repo.adapterReleased.value) "Подключиться" to ACTION_RECONNECT else "Отпустить адаптер" to ACTION_RELEASE
                    addAction(Notification.Action.Builder(null, label, PendingIntent.getService(
                        this@EcuService, 2, Intent(this@EcuService, EcuService::class.java).setAction(action),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )).build())
                }
            }
            .addAction(Notification.Action.Builder(null, "Выход", PendingIntent.getService(
                this, 1, Intent(this, EcuService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE
            )).build())
            .build()
    }

    companion object {
        /** The charts' step (see addHistory): every sample of the plain polling, every third of the fast poll. */
        private const val HISTORY_STEP_MS = 200L
        const val CHANNEL = "ecu"
        const val ACTION_USB_PERMISSION = "kg.forester.sg.USB_PERMISSION"
        const val ACTION_RESET_TRIP = "kg.forester.sg.RESET_TRIP"
        const val ACTION_START_GPS = "kg.forester.sg.START_GPS"
        /** From the notification: stop everything and close the app. */
        const val ACTION_EXIT = "kg.forester.sg.EXIT"
        /** Let the adapter go for another app, and take it back (notification, the link icon in the header). */
        const val ACTION_RELEASE = "kg.forester.sg.RELEASE_ADAPTER"
        const val ACTION_RECONNECT = "kg.forester.sg.RECONNECT_ADAPTER"
        val exitRequested = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** Paired Bluetooth devices worth trying in "Auto". */
        private val OBD_NAMES = listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE", "ICAR", "OBDLINK", "CARISTA", "VEEPEAK",
            "MPC", "MULTITRONICS")

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, EcuService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

/**
 * Autostart: a real boot, a "quick boot", or ACC on after the FYT/Teyes head unit slept (those units mostly
 * sleep instead of shutting down, so BOOT_COMPLETED alone is not enough). Which ACC broadcast a given firmware
 * sends varies, so several known ones are listed in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val s = Repo.settings
        // after an update the service was running before: it comes back whatever the autostart says
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (updated || s.autostartService || s.autostartApp) EcuService.start(context)
        // allowed from the background only with "display over other apps" (Android 10+)
        if (!updated) WakeWatch.openApp(context)
    }
}

/**
 * One CSV file per engine run in Android/data/kg.forester.sg/files/logs; columns as chosen on the «Лог» screen. «Time»
 * first is ms from the start of the file, as in RomRaider logs (EcuEdit and the like choke on epoch ms); «epoch_ms»
 * last is the wall clock. The start, to the second, is the file name.
 */
class CsvLogger(ctx: Context, val extras: List<RrParam> = emptyList()) {
    val file: File
    private val w: FileWriter
    private var lines = 0
    val columns: List<LogColumn> = LogColumns.enabled()
    /** The start: now, down to the whole second the file name shows. */
    private val t0 = System.currentTimeMillis() / 1000 * 1000

    init {
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(t0)) + ".csv"
        file = File(dir, name)
        w = FileWriter(file)
        w.write(LogColumns.header(columns.map { it.key } + extras.map { p -> "${p.id} ${p.nameEn} ${p.units}".trim().replace(',', ' ') }) + "\n")
    }

    fun write(s: kg.forester.sg.ssm.EcuSnapshot, g: GpsFix?, lh: Double, t: TcuSnapshot?, extra: Map<String, Double> = emptyMap()) {
        val row = LogRow(s, g, lh, t, Repo.guard.value)
        val values = columns.map { it.get(row) } + extras.map { p ->
            extra[p.id]?.let { v ->
                if (kotlin.math.abs(v) >= 1e7) "%.6g".format(Locale.US, v)
                else "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')
            } ?: ""
        }
        w.write(LogColumns.line(s.timeMs, t0, values.joinToString(",")) + "\n")
        if (++lines % 20 == 0) w.flush()
    }

    fun close() {
        runCatching { w.flush(); w.close() }
    }
}
