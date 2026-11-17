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
import kg.forester.sg.data.afterLinkLoss
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
import org.json.JSONObject
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
    /** Set instead of [client] while a pre-1999 unit is read over SSM1. */
    @Volatile var ssm1: kg.forester.sg.ssm.SsmP1Client? = null

    suspend fun <T> use(block: (SsmClient) -> T): T? = mutex.withLock {
        val c = client ?: return@withLock null
        runCatching { block(c) }.getOrNull()
    }
}

class EcuService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val faultWatch = FaultWatch()
    private val codeWatch = CodeWatch()
    private var pollJob: Job? = null
    private val knock by lazy { KnockWatch(this) }
    private val guard = EngineGuard()
    private val tracker by lazy { TripTracker(this) { knock.reset(it); guard.newTrip(it) } }
    private val battery by lazy { BatteryMonitor(this) }
    private var mafDropLogged = false
    private var tcuAddrs: IntArray? = null
    /** The remembered SSM1 car stayed silent: the next connection is an ordinary SSM2 one (its probe finds the old unit again). */
    private var ssm1Skip = false
    private var tcuNextTryMs = 0L
    private var tcuMisses = 0
    private var tcuAnswered = false
    private var tcuAtfOnly = false
    /** This connection's TCU let requests with 0x6A pass (see [pollTcu]): left out after a new init too. */
    private var tcuNoFwd = false
    private var logger: CsvLogger? = null
    private var triedHostFix = false
    /** elapsedRealtime of the last successful USB claim: used to hint «перевоткните кабель» after the cable was just working. */
    @Volatile private var lastHadCableMs = 0L
    /**
     * Consecutive USB write timeouts across pollLoop iterations: Evgen's Forester 2007 (9DMUWJ8K, sprd ums512,
     * beta3 log of 2026-11-16) had 476 `SerialTimeoutException: Error writing 6 bytes after 1022msec` in a row
     * after an ACC off/on, the FTDI endpoint wedged on this head unit's OTG controller. Each iteration closes
     * and reopens the port but the USB connection itself needs a reset to unstick.
     *
     * From [WRITE_TIMEOUT_STREAK] timeouts in a row the pass ends with [resetUsb], before the port is closed:
     *   1. FTDI: a soft reset by vendor control requests (purge RX, purge TX, reset SIO). The device keeps its
     *      address, so Android does not ask for the USB permission again.
     *   2. From [WRITE_TIMEOUT_HARD_RESET] on, other chips at once, or when the chip refuses the control requests:
     *      `UsbDeviceConnection.resetDevice()`, a bus reset. On some kernels the device comes back under a new
     *      address and Android asks for the permission again, so it is the last resort.
     *
     * Back to 0 when the ECU answers its init through the cable, after a non-timeout error, and after a bus reset.
     */
    private var writeTimeoutStreak = 0
    /**
     * ROMs whose fast-poll start lost the USB device until the service restarts (not saved: one bad moment must not
     * cost the stream for good). Alexey's Xiaomi 21081111RG on Android 14 (2026-11-17): «USB get_status request
     * failed» 1.5 s after the 110-byte A8 01 request and before any frame, the device gone, and every reconnect
     * started the stream and lost the device again. Only an error between the stream start and its first frame
     * counts (see [streamInitAtMs]); a cable pulled or a head unit asleep later is not the stream's fault.
     */
    private val usbGuiltyRoms = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    /** ROM of this connection's ECU, null until its init: the error handler marks this one. */
    @Volatile private var activeRomId: String? = null
    /** elapsedRealtime of the last stream start still waiting for its first frame, 0 otherwise. */
    @Volatile private var streamInitAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    /** After ACC sleep: GPS again (it often stalls) and the dashboard on screen if the driver wants it. */
    private val wakeWatch by lazy {
        WakeWatch(this, onSleptWake = {
            // an adapter that wakes with the car is still booting: tries from the start, not after the last sleep's pause
            Repo.autoBtPause.reset()
            kg.forester.sg.data.DeviceTier.newSession()
            // the SensorBox may have dropped off with the USB in the sleep: every device is asked again
            SensorBoxLink.wake()
        }) {
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
        // first: the system gives a started service a few seconds for it, and a weak unit may spend them on what follows
        startInForeground()
        SsmTrace.init(this)
        // a log that did not go last time (only if the upload is on, the address is known and the limits allow)
        LogUpload.auto(this, "start", onlyPending = true)
        // the open folder for own RomRaider files, there from the first start for a file manager to find
        Thread { kg.forester.sg.data.EcuDefs.prepareFolder(this) }.start()
        Alerts.init(this)
        Motion.start(this)
        // the feature switched in the settings: the sensor follows
        scope.launch { Repo.featureVersion.collect { Motion.start(this@EcuService) } }
        scope.launch { linkLossWatch() }
        Tpms.start(this)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
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
        Motion.stop()
        Voice.stop(wait = false) // the speech model's memory back; the next start of the service loads it again
        stopGps()
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

    /**
     * A failing poll writes no null: it counts failures and, in the end, sets NO_ECU, and the last reading stays in
     * [Repo.live]. So two seconds after the link leaves CONNECTED the values are wiped here (as releasing the adapter does),
     * and the screens, which then have nothing, show dashes and say «нет связи».
     */
    private suspend fun linkLossWatch() {
        val loss = kg.forester.sg.data.LinkLoss()
        while (scope.isActive) {
            if (loss.due(Repo.link.value, System.currentTimeMillis())) {
                SsmTrace.log("link lost for ${kg.forester.sg.data.LINK_LOSS_MS} ms: values cleared")
                Repo.live.value = EcuSnapshot()
                Repo.tcu.value = null
                // what the guard and the trip show of the moment, and the SSM values, go with it; totals and latches stay
                Repo.guard.value = Repo.guard.value.afterLinkLoss()
                Repo.trip.value = Repo.trip.value.afterLinkLoss()
                Repo.rrValues.value = emptyMap()
            }
            delay(250)
        }
    }

    private fun reconnectAdapter() {
        if (!Repo.adapterReleased.value) return
        Repo.adapterReleased.value = false
        Repo.autoBtPause.reset()
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
            // per connection: an error before this one's ECU init is not the previous ROM's or its stream's
            activeRomId = null
            streamInitAtMs = 0L
            val kind = s.adapter
            when (kind) {
                "elm_bt" -> { bluetoothSession(s.btAddress, s.btName); delay(3000); continue }
                "mt_bt" -> { bluetoothSession(s.btAddress, s.btName, multitronics = true); delay(3000); continue }
                "elm_wifi" -> { wifiSession(explicit = true); delay(3000); continue }
                // a CAN adapter on Bluetooth or Wi-Fi (RealDash CAN, GVRET); on USB it is found below
                "canable" -> if (kg.forester.sg.data.CanConfig.transport != "usb") { canRemoteSession(); delay(2000); continue }
            }
            val all = adapterProber.findAllDrivers(usb) + unknownSerial(usb, adapterProber.findAllDrivers(usb))
            logUsbDevices(usb, all)
            // the SensorBox (an Arduino next to the cable) and devices still being asked whether they are it
            val drivers = SensorBoxLink.forEcu(all)
            val driver = pickDriver(kind, drivers)
            // no CANable on USB: a SensorBox with CAN (an ESP32-S3, firmware 6+) takes its place. Its CAN is switched on by
            // hand, so it goes before a USB serial chip that would only be asked what protocol it speaks (CanLinks); a
            // protocol picked by hand keeps that chip
            val unknownChip = driver != null && !isCanable(driver) && kg.forester.sg.data.CanConfig.protocol == "auto"
            if ((driver == null || unknownChip) && kind == "canable" && kg.forester.sg.data.BoxCanBus.available()) {
                Repo.adapterFound.value = "SensorBox → CAN (${kg.forester.sg.data.BoxCan.speedTitle(kg.forester.sg.data.BoxCan.cfg.value.kbit)})"
                boxCanSession()
                delay(500)
                continue
            }
            if (driver == null) {
                if (!triedHostFix && System.currentTimeMillis() - noCableSince > 8000) {
                    triedHostFix = true
                    forceUsbHostMode()
                }
                // nothing on USB: a Wi-Fi ELM327 we are connected to, then a paired Bluetooth one. Not on a line that is
                // another device's: an ELM's init goes out on the K-line, it cannot only listen
                if (kind == "auto" && !s.lineShared) {
                    if (wifiSession(explicit = false) || autoBluetooth()) { delay(1000); continue }
                }
                Repo.link.value = LinkState.NO_CABLE
                Repo.adapterFound.value = ""
                val other = drivers.firstOrNull()
                val base = when {
                    kind == "auto" -> "Адаптер не найден: подключите USB-кабель или Wi-Fi/Bluetooth-адаптер " +
                        "(Bluetooth сначала сопрягите в настройках Android)"
                    other != null && kind == "op2" -> "Найден KKL-кабель, а в настройках выбран OpenPort 2.0"
                    other != null -> "Найден OpenPort 2.0: выберите его в Настройках → Адаптер (или «Авто»)"
                    kind == "op2" -> "OpenPort 2.0 не найден"
                    kind == "canable" && usb.deviceList.values.any { kg.forester.sg.ssm.Slcan.isCandleLight(it.vendorId, it.productId) } ->
                        "CANable с прошивкой candleLight: приложение работает с прошивкой slcan (как RealDash). Перепрошейте адаптер на slcan"
                    kind == "canable" -> "CAN-адаптер не найден: подключите его к USB магнитолы (CANable, RealDash CAN, GVRET; " +
                        "не ответивший ни по одному протоколу спрошу снова через 20 с, или выберите протокол вручную)"
                    kind == "elm_usb" -> "USB-адаптер ELM327 не найден"
                    else -> "Кабель KKL не найден (если он подключён — «Отправить журнал»: в журнале коды всех USB-устройств)"
                }
                // the cable worked a moment ago and now the system doesn't see it: it is often the head unit's OTG that
                // stayed in device mode after the ignition cycle (Evgen, Ego 2026-11-17 in TG). The pollLoop keeps looking
                // every 2 s on its own; the hint is for the person at the screen
                val sinceCable = android.os.SystemClock.elapsedRealtime() - lastHadCableMs
                val recent = lastHadCableMs > 0L && sinceCable in 0..300_000L
                Repo.linkMessage.value = if (recent)
                    "$base. Был подключён и пропал: магнитола могла перезагрузиться с зажиганием. Перевоткните кабель или попробуйте другой USB-порт"
                else base
                delay(2000)
                continue
            }
            noCableSince = System.currentTimeMillis()
            // next no-cable gap gets its own OTG host-mode shove: a user who cycles the ignition hourly needs it each time
            triedHostFix = false
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
            var resetUsbBeforeClose = false
            try {
                port.open(conn)
                lastHadCableMs = android.os.SystemClock.elapsedRealtime()
                val id = "%04X:%04X".format(driver.device.vendorId, driver.device.productId)
                when {
                    isCanable(driver) -> {
                        Repo.adapterFound.value = "USB $id → CANable (slcan)"
                        canableSession(port)
                    }
                    // the CAN adapter picked by hand, on a chip that is not the CANable's: RealDash CAN, GVRET or slcan
                    kind == "canable" && !isOp2(driver) && !isOp13(driver) -> canUsbSession(port, driver, id)
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
                            // another device on the K-line: no AT probe at 38400 (its bytes go out on the line), a KKL
                            // cable that only listens
                            else -> if (s.lineShared) 0 else when (val known = s.usbKind(id)) {
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
                            // a pre-1999 car found on this cable before: straight to SSM1; if it stays silent (another
                            // car, or the ignition off for long), one ordinary SSM2 session, whose probe finds it again
                            if (s.ssm1Rom.isNotEmpty() && !ssm1Skip) {
                                if (!ssm1Session(port)) ssm1Skip = true
                            } else {
                                ssm1Skip = false
                                session(klineOnly = true, obd = { kklObd(port) }) { _ -> KklTransport(port).also { it.open() } }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // the polling stopped on purpose (exit, the adapter released, settings changed): no error
                SsmTrace.log("polling stopped")
                throw e
            } catch (e: Exception) {
                SsmTrace.log("error: $e")
                // the wideband took this device (its connection was closed under us): not a cable fault
                if (SensorBoxLink.ecuLease?.permitted != false) {
                    Repo.link.value = LinkState.ERROR
                    Repo.linkMessage.value = kg.forester.sg.data.Failure.link(e, "Ошибка связи")
                }
                // Some head units' OTG controller wedges after ACC off/on: writeRaw times out on the same port
                // for minutes (see [writeTimeoutStreak]). Count only the hoho usb-serial SerialTimeoutException,
                // not Bluetooth/Wi-Fi socket drops.
                if (e is com.hoho.android.usbserial.driver.SerialTimeoutException) {
                    writeTimeoutStreak++
                    if (writeTimeoutStreak >= WRITE_TIMEOUT_STREAK) {
                        resetUsbBeforeClose = true
                        Repo.linkMessage.value = "Магнитола не отправляет запросы в кабель. Возможно, магнитола " +
                            "перезагрузилась с зажиганием. Попробуйте другой порт USB или перевоткните кабель"
                    }
                } else {
                    writeTimeoutStreak = 0
                    // The USB device went away right after our stream start, before its first frame (Alexey's Xiaomi:
                    // «USB get_status request failed» 1.5 s after the 110-byte A8 01 request, then the device gone).
                    // Only that window counts: a cable pulled or a head unit asleep later in the run is not the stream's.
                    val sinceStream = android.os.SystemClock.elapsedRealtime() - streamInitAtMs
                    if (e is java.io.IOException && streamInitAtMs > 0L && sinceStream < STREAM_INIT_BLAME_MS) {
                        activeRomId?.let { rom ->
                            if (usbGuiltyRoms.add(rom)) SsmTrace.log("USB lost $sinceStream ms after the stream start, fast poll off for ROM $rom in this run")
                        }
                    }
                }
            } finally {
                runCatching { EcuLink.client?.close() }
                EcuLink.client = null
                runCatching { streams?.close() }
                // before the port is closed: usb-serial's close() closes the connection as well, and on a closed one the
                // reset quietly does nothing (controlTransfer gives -1, resetDevice false)
                if (resetUsbBeforeClose) runCatching { resetUsb(conn, driver.device.vendorId == 0x0403) }
                    .onFailure { SsmTrace.log("USB reset: $it") }
                runCatching { if (op2 != null) op2.close() else port.close() }
                runCatching { conn.close() }
                SensorBoxLink.releaseEcu()
                logger?.close(); logger = null
            }
            delay(if (resetUsbBeforeClose) 2500 else 1000)
        }
    }

    /**
     * See [writeTimeoutStreak]. Runs on the still open [conn]. The soft FTDI reset leaves the streak as it is, so a chip
     * that keeps timing out after it still reaches the hard reset; the hard reset clears it.
     */
    private fun resetUsb(conn: android.hardware.usb.UsbDeviceConnection, ftdi: Boolean) {
        val streak = writeTimeoutStreak
        if (ftdi && streak < WRITE_TIMEOUT_HARD_RESET) {
            val rc = intArrayOf(FTDI_RESET_PURGE_RX, FTDI_RESET_PURGE_TX, FTDI_RESET_SIO)
                .map { conn.controlTransfer(FTDI_VENDOR_OUT, FTDI_SIO_RESET, it, 0, null, 0, 1000) }
            SsmTrace.log("USB write timeouts × $streak: FTDI soft reset, rc ${rc.joinToString(" ")}")
            // a chip that refuses even control requests is stuck harder: straight to the bus reset
            if (rc.all { it >= 0 }) return
        }
        // UsbDeviceConnection.resetDevice() exists in AOSP but is @UnsupportedAppUsage, not in the SDK: by reflection.
        // A bus reset: some kernels give the device a new address, and Android asks for the permission again
        val ok = runCatching { conn.javaClass.getMethod("resetDevice").invoke(conn) as? Boolean }
            .onFailure { SsmTrace.log("USB resetDevice: $it") }.getOrNull()
        SsmTrace.log("USB write timeouts × $streak: resetDevice returned $ok")
        if (ok == true) writeTimeoutStreak = 0
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
        // the CANable by its ids, else a USB serial chip to ask what CAN protocol it speaks (see CanLinks)
        "canable" -> drivers.firstOrNull { isCanable(it) }
            ?: drivers.firstOrNull { !isOp2(it) && !isOp13(it) && CanLinks.mayProbe(it.device.deviceName) }
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
        Repo.checkEngine.value = null // the demo has no codes
        Repo.link.value = LinkState.CONNECTED
        Repo.linkMessage.value = "Демо-данные (без машины)"
        knock.reset(System.currentTimeMillis(), record = false)
        // a trip counted in memory only (never saved), so the fuel screen shows something in the demo
        val demoStart = System.currentTimeMillis()
        var demoTrip = kg.forester.sg.data.Trip(startMs = demoStart, endMs = demoStart, distanceKm = 0.0, fuelL = 0.0)
        var lastDemo = demoStart
        val demoDyno = DynoPoll()
        while (scope.isActive && Repo.settings.demo) {
            val now = System.currentTimeMillis()
            val (snap, tcu) = Demo.ecu(now)
            Repo.live.value = snap
            addHistory(snap)
            knock.process(snap, record = false)
            extras(snap, now)
            // a pull on the dyno's screen as fast as the cable's short list gives it
            val dynoFast = demoDyno.wanted(Repo.dynoTab.value, snap, now)
            if (Repo.settings.gearbox != "mt") {
                Repo.tcu.value = lockUp(tcu, snap.rpm, now)
                Repo.tcuStatus.value = "АКПП: демо"
                if (!dynoFast) {
                    Repo.tcuHistory.add(tcu)
                }
            }
            val bearing = if ((snap.speedKmh ?: 0) > 3) Demo.bearing(now) else null
            val (lat, lon) = Demo.position(now, (snap.speedKmh ?: 0).toFloat(), bearing)
            Repo.gps.value = GpsFix(
                speedKmh = (snap.speedKmh ?: 0).toFloat(), altitudeM = 195.0, accuracyM = 5f, satellites = 9,
                timeMs = now, bearing = bearing, lat = lat, lon = lon,
                arrivedMs = android.os.SystemClock.elapsedRealtime(),
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
                trip = demoTrip, instLh = lh, instKnown = true, instPer100 = if (v >= 5) lh / v * 100 else null, fuelLeftL = left,
                // as with the car: the trip's first minutes (a jam, a hard pull) must not swing the range
                rangeKm = left / FuelCalc.rangePer100(demoTrip, emptyList()) * 100,
            )
            delay(if (dynoFast) 33 else 250)
        }
        // demo off: none of its made-up numbers may stay on the screen as if they were the car's
        Repo.live.value = EcuSnapshot()
        Repo.tcu.value = null
        Repo.history.clear()
        Repo.tcuHistory.clear()
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
            Repo.linkMessage.value = "Выберите Bluetooth-адаптер: Настройки → «Машина и адаптер»"
            return null
        }
        if (!btAllowed()) {
            Repo.link.value = LinkState.NO_PERMISSION
            Repo.linkMessage.value = "Нет разрешения на Bluetooth (Настройки → «Машина и адаптер»)"
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
        // each try pages the adapter, and the radio is shared with what plays music or runs wireless CarPlay
        val now = android.os.SystemClock.elapsedRealtime()
        val pause = Repo.autoBtPause
        if (!pause.due(now)) return false
        val s = Repo.settings
        val connected = if (s.btAddress.isNotEmpty()) bluetoothSession(s.btAddress, s.btName, quiet = true) else {
            val candidates = runCatching { bt.bondedDevices.toList() }.getOrDefault(emptyList())
                .filter { d -> OBD_NAMES.any { (d.name ?: "").uppercase().contains(it) } }
            candidates.any { d ->
                bluetoothSession(d.address, d.name ?: "", quiet = true).also { ok ->
                    if (ok) { s.btAddress = d.address; s.btName = d.name ?: d.address }
                }
            }
        }
        if (connected) pause.reset() else pause.failed(android.os.SystemClock.elapsedRealtime())
        return connected
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            SsmTrace.log("polling stopped")
            throw e
        } catch (e: Exception) {
            SsmTrace.log("error: $e")
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = kg.forester.sg.data.Failure.link(e, "Ошибка связи")
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

    /** Addresses per request each ECU (by ROM) took this run of the service: kept over reconnects (see SsmClient.presetReadLimit). */
    private val ecuReadLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Stream restarts each ECU let pass in silence (see the fast poll in [session]). The ROMs whose stream fell silent
     * twice are kept in [Settings.fastPollOffRoms] so a cable reopen or an app restart doesn't try them again on this
     * build.
     */
    private val streamRestarts = StreamRestarts(
        load = { Repo.settings.fastPollOffRoms },
        save = { Repo.settings.fastPollOffRoms = it },
    )

    /** ECUs (by ROM) that went mute on a plain cable this run of the service: the long gap from the next connection's start (KLineTransport.longGap). */
    private val longGapRoms = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) // newKeySet needs API 24

    /** The K-line is another device's (Настройки, «На линии другой прибор»): the transport only listens. */
    private fun syncListenOnly(t: SsmTransport?) {
        val kl = t as? kg.forester.sg.ssm.KLineTransport ?: return
        val shared = Repo.settings.lineShared
        if (kl.listenOnly != shared) { kl.listenOnly = shared; SsmTrace.log("K-line: ${if (shared) "listening only, another device on it" else "ours again"}") }
    }

    /** A plain KKL cable, the engine unit mute on it although the line is live: hold the line low at once. */
    private fun ecuMuteOnLine(t: SsmTransport?) = t is kg.forester.sg.ssm.KklTransport && t.ecuMute()

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
        // silent rounds in a row without our echo: maybe no cable at all but the SensorBox (a Nano's CH340), see below
        var noEchoRounds = 0
        // the K-line already held low once for that silent ECU: the next silent round reopens the cable
        var lineHeldLow = false
        // the pre-1999 SSM1 fallback already tried this session (1953 baud GET_ID): not pushed again on the same cable
        var ssmP1Tried = false
        // the polling stopped because the ECU went mute on a live line (KLineTransport.ecuMute): hold it low at once
        var muteHold = false
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
                syncListenOnly(transport)
                // a laptop or a gauge already polling: heard before our first request, not garbled by it
                if (transport is kg.forester.sg.ssm.KLineTransport && !transport.listenOnly) delay(LISTEN_FIRST_MS)
            }
            syncListenOnly(transport)
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
                // no echo round after round: the box loop asks the device whether it is the SensorBox (once per wake)
                if (roundDone && klineOnly) noEchoRounds = if (roundEcho) 0 else noEchoRounds + 1
                if (roundDone && klineOnly && SensorBoxLink.ecuNoEcho(noEchoRounds)) return
                // a KKL cable echoing on a live line, the ECU that spoke a minute ago silent to every request: on a
                // Legacy BP with an FTDI cable it stayed so for minutes and answered at once after the cable was
                // plugged in again. Reopening the USB device alone did not wake it (beta24 logs): the re-plug also leaves the
                // K-line low for seconds (TxD of an unpowered chip at 0), which seems to be what resets the ECU's serial
                // port. So hold the line low the same way first, then, if it is still silent, reopen the cable.
                if (roundDone && klineOnly && ecuSpoke && roundEcho && !lineHeldLow) {
                    lineHeldLow = true
                    Repo.linkMessage.value = "ЭБУ молчит, сбрасываю K-линию…"
                    if (kl?.holdLineLow(2500) == true) {
                        SsmTrace.log("ECU silent while the cable echoes: K-line held low 2.5 s")
                        roundEcho = false
                        kl.heardEcho = false
                        delay(500)
                        continue
                    }
                }
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
                // Nothing from SSM2 (4800 baud) for two full rounds on a plain USB serial cable: ask SSM1 (1953 baud)
                // once. The pre-1999 units (GC8/GF8 and SF5 up to 1998, early Legacy, SVX, L-series/XT) speak only that,
                // on the OBD socket's pins 12 and 13 with separate lines, so the cable for them hears no echo: no echo
                // condition here. Up to about 1.5 s and silent on screen; the 3 s pause of the round is skipped instead,
                // so a usual car with the ignition off waits no longer than before. An OpenPort 1.3 sits on the K-line
                // only and can't reach those pins.
                val p1Kkl = transport as? kg.forester.sg.ssm.KklTransport
                if (roundDone && p1Kkl != null && !p1Kkl.openPort13 && silentRounds >= 2) {
                    // a car already known to be SSM1: back to its own session (the ignition was off a while)
                    if (Repo.settings.ssm1Rom.isNotEmpty()) { SsmTrace.log("SSM2 silent, back to SSM1"); return }
                    if (!ssmP1Tried) {
                        ssmP1Tried = true
                        transport.close()
                        transport = null
                        EcuLink.client = null
                        val rom = kg.forester.sg.ssm.SsmP1Probe.run(p1Kkl.port)
                        if (rom.isNotEmpty()) {
                            Repo.settings.ssm1Rom = rom
                            Repo.linkMessage.value = "Найден старый ЭБУ (SSM1), ROM $rom: подключаюсь…"
                            return
                        }
                        continue
                    }
                }
                delay(if (roundDone) 3000 else 300)
                continue
            }
            silentRounds = 0
            ecuSpoke = true
            lineHeldLow = false
            Repo.obdMode.value = false
            // an SSM2 car on this cable now: the old one remembered goes, or every connection would ask SSM1 first
            if (Repo.settings.ssm1Rom.isNotEmpty()) Repo.settings.ssm1Rom = ""
            // with the sensors the driver says this ECU has although its list leaves them out (old JECS)
            val capsSeen = Repo.capsVersion.value
            val init = kg.forester.sg.data.EcuCaps.apply(answer)
            if (init !== answer) SsmTrace.log("capability bits set by hand: ${kg.forester.sg.data.EcuCaps.forced(init.romId).joinToString(" ") { "${it.first}.${it.second}" }}")
            ecuReadLimits[init.romId]?.let { client.presetReadLimit(it); SsmTrace.log("ECU: reading by ${client.ecuReadLimit}, as learnt earlier") }
            (transport as? kg.forester.sg.ssm.KLineTransport)?.let { kl ->
                if (init.romId in longGapRoms && !kl.longGap) { kl.longGap = true; SsmTrace.log("K-line: the long gap before each request, this ECU went mute earlier") }
            }
            Repo.ecuInitRaw.value = answer
            Repo.ecuInit.value = init
            runCatching { carFromEcu(init.romId) }
            Repo.link.value = LinkState.CONNECTED
            val car = kg.forester.sg.ssm.EcuIds.car(this, init.romId)
            Repo.linkMessage.value = "ROM ${init.romId}" + (car?.let { " ($it)" } ?: "") + " · ${client.transport.name}"
            RomRaider.load(this)
            RomRaider.siblingOf(init.romId)?.let { SsmTrace.log("no extended parameters for ROM ${init.romId}, using sibling $it") }
            faultWatch.newConnection()
            Repo.checkEngine.value = null
            codeWatch.newConnection(System.currentTimeMillis())
            SsmTrace.log("ECU init: SSM ${init.ssmId} ROM ${init.romId} caps ${init.capabilities.toHex(" ")}")
            activeRomId = init.romId
            // the ECU answered through this cable: whatever timed out before is over
            writeTimeoutStreak = 0
            guard.romIam = EngineGuard.RomIam()
            val addrs = choosePollAddresses(client, init)
            if (addrs == null) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = "ЭБУ ${init.romId} ответил на init, но не отдаёт параметры"
                delay(3000)
                continue
            }
            Repo.pollAddresses.value = addrs.size
            runCatching { connectChecks(client, init) }.onFailure { SsmTrace.log("connect checks: $it") }
            runCatching { readRomIam(client, init) }.onFailure { SsmTrace.log("rom iam: $it") }
            ecuWatch.setup(init)
            tcuWatch.clear()
            // boost once seen on this ROM counts from the start: the boost cards must not leave at every connection
            val ecuSaysTurbo = kg.forester.sg.ssm.EngineId.turbo(this, init, Repo.settings.boostRom == init.romId)
            Repo.settings.carTurbo = kg.forester.sg.data.HomeLayout.carHasTurbo(true, ecuSaysTurbo, false, false)
            // an engine without a turbo on the untouched factory screen: no empty boost cards
            if (Repo.settings.homeTiles.isEmpty() && !ecuSaysTurbo) {
                Repo.settings.homeTiles = kg.forester.sg.data.HomeLayout.atmoDefault()
            }
            val afSensor = EcuParams.reported(init, EcuParams.AF_SENSOR)
            val skip = EcuParams.unsupported(init, addrs)
            if (skip.isNotEmpty()) SsmTrace.log("not supported by this ECU, ignored: ${skip.joinToString(" ") { "%02X".format(it) }}")
            var failures = 0
            var cycle = 0
            tcuAddrs = null; tcuMisses = 0; tcuAnswered = false; tcuNoFwd = false
            tcuNextTryMs = 0
            SsmTrace.polling = true
            // «fast poll» (K-line): the ECU sends the values by itself, three times as often; off for this connection
            // when it doesn't, and for this ROM when its restarts have fallen silent (see StreamRestarts) or the host
            // kernel errored out on the big stream-init TX earlier in this run (see [usbGuiltyRoms])
            val usbGuilty = init.romId in usbGuiltyRoms
            val streamOff = streamRestarts.off(init.romId) || usbGuilty
            var fast = Repo.settings.fastPoll && transport is kg.forester.sg.ssm.KLineTransport && !streamOff
            if (streamOff && Repo.settings.fastPoll) SsmTrace.log(
                if (usbGuilty) "fast poll: off for ROM ${init.romId}, host kernel errored out on stream earlier in this run, plain requests"
                else "fast poll: off for ROM ${init.romId}, its stream restarts fell silent, plain requests"
            )
            val fastFrames = Channel<ByteArray>(Channel.CONFLATED)
            var fastAddrs: IntArray = addrs
            var fastFrameSeen = false
            var fastRun = 0
            var fastTcuMs = 0L
            var fastWatchMs = 0L
            // the dyno on the screen, the pedal floored: the short list only, into the last full read (see DynoPoll)
            val dynoPoll = DynoPoll()
            var dynoFine = true
            var fastShort = false
            /** What the stream reads, or last read, when it isn't the short list. */
            var fullAddrs: IntArray = addrs
            /** Plain requests: the ticked parameters go in the regular read until that is refused (see joinRefused). */
            var joinWatch = true
            var joinRefused = 0
            try { while (scope.isActive && failures < 5 && !Repo.settings.demo && capsSeen == Repo.capsVersion.value &&
                SensorBoxLink.ecuLease?.permitted != false) {
                syncListenOnly(transport)
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
                val dynoNow = System.currentTimeMillis()
                val dynoShort = if (dynoFine && dynoPoll.wanted(Repo.dynoTab.value, Repo.live.value, dynoNow))
                    DynoPoll.shortList(addrs, skip, client.transport.maxReadAddrs, ecuWatch.proven(guard.fastRoles().toList().filterNotNull())) else null
                if (fast) {
                    // a pull goes on with the rest of the values as they were: a whole read every couple of seconds
                    // keeps them fresh for the alerts (the request itself stops the stream, the next round starts it)
                    if (dynoShort != null && dynoPoll.fullDue(dynoNow)) {
                        val read = fullAddrs
                        val whole = EcuLink.mutex.withLock { client.read(read) }
                        val now = System.currentTimeMillis()
                        if (whole == null) {
                            // refused with the ticked ones: without them from now on, as the plain requests do
                            if (read.size > addrs.size) fullAddrs = addrs else {
                                failures++
                                tracker.tick(now)
                                if (ecuMuteOnLine(transport)) { muteHold = true; break }
                            }
                            continue
                        }
                        failures = 0
                        var snap = EcuParams.decode(read, whole, now, skip)
                        if (!afSensor) snap = snap.copy(lambda = null)
                        if (read.size > addrs.size) ecuWatch.take(read, whole)
                        dynoPoll.noteFull(snap, now)
                        onSnapshot(snap, now)
                        cycle++
                        continue
                    }
                    if (client.streaming && (dynoShort != null) != fastShort) EcuLink.mutex.withLock { client.stopStream() }
                    // the ticked parameters ride along in the stream, but each new list is read on its own first:
                    // an address this ECU doesn't know would silence the whole stream, not just them
                    if (dynoShort == null && ecuWatch.refresh() && client.streaming) EcuLink.mutex.withLock { client.stopStream() }
                    // ticked ones that didn't answer get another try now and then (a manual gearbox never stops the stream)
                    if (dynoShort == null && client.streaming && !ecuWatch.answers && ecuWatch.addresses.isNotEmpty() &&
                        System.currentTimeMillis() - fastWatchMs > 10_000) EcuLink.mutex.withLock { client.stopStream() }
                    if (!client.streaming) {
                        fastWatchMs = System.currentTimeMillis()
                        fastShort = dynoShort != null
                        if (dynoShort != null) fastAddrs = dynoShort
                        else {
                            // a list already read on its own needs no second read after each transmission request: that
                            // would leave the stream silent for one more request every N × 250 ms
                            if (!ecuWatch.answers) ecuWatch.poll(client)
                            val extra = if (ecuWatch.answers) ecuWatch.addresses.filter { it !in addrs } else emptyList()
                            val room = client.transport.maxReadAddrs - addrs.size
                            if (extra.size > room) SsmTrace.log("fast poll: ${extra.size - room} watched addresses left out")
                            fastAddrs = addrs + extra.take(room.coerceAtLeast(0))
                            fullAddrs = fastAddrs
                        }
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
                        streamInitAtMs = android.os.SystemClock.elapsedRealtime()
                    }
                    val raw = withTimeoutOrNull(1500) { fastFrames.receive() }
                    val now = System.currentTimeMillis()
                    if (raw == null) {
                        // the cable was taken from us (its connection closed): nothing more will come
                        if (SensorBoxLink.ecuLease?.permitted == false) break
                        // stopped by another request in between (trouble codes, a write): start it again
                        if (!client.streaming) continue
                        EcuLink.mutex.withLock { client.stopStream() }
                        streamInitAtMs = 0L
                        // no answers, or one or two as to a plain request: this ECU doesn't stream. One that has streamed
                        // in this connection does: its silence now is the ECU's, not the stream's (an alps unit lost
                        // the stream for the connection this way, the ECU mute a moment)
                        if (fastRun < 3 && !fastFrameSeen) {
                            fast = false
                            SsmTrace.log("fast poll: no stream from this ECU ($fastRun answers), plain requests")
                        } else {
                            // silent right after a restart (the transmission read in between): see StreamRestarts
                            val ignored = (transport as? kg.forester.sg.ssm.KLineTransport)?.ignoredRequests ?: 0
                            val n = streamRestarts.silent(init.romId, fastRun, fastFrameSeen, ignored)
                            if (n >= StreamRestarts.MAX) {
                                fast = false
                                SsmTrace.log("fast poll: the ECU fell silent on a stream restart again, plain requests for ROM ${init.romId}")
                            } else if (n > 0) SsmTrace.log("fast poll: the ECU fell silent on a stream restart, transmission every " +
                                "${streamRestarts.tcuGapMs(init.romId, Repo.settings.tcuEvery) / 1000} s")
                            failures++
                            tracker.tick(now)
                            if (ecuMuteOnLine(transport)) { muteHold = true; break }
                        }
                        continue
                    }
                    failures = 0
                    fastRun++
                    streamInitAtMs = 0L
                    if (!fastFrameSeen) { fastFrameSeen = true; SsmTrace.log("fast poll: ${fastAddrs.size} addresses") }
                    if (cycle == 0) logFirstRead(addrs, raw)
                    var snap = EcuParams.decode(fastAddrs, raw, now, skip)
                    if (!afSensor) snap = snap.copy(lambda = null)
                    if (fastShort) {
                        val fresh = dynoFresh(fastAddrs, raw)
                        snap = dynoPoll.full?.let { DynoPoll.merge(it, snap) } ?: snap
                        onSnapshot(snap, now, merged = true, knockFresh = fresh)
                    } else {
                        ecuWatch.take(fastAddrs, raw)
                        dynoPoll.noteFull(snap, now)
                        onSnapshot(snap, now)
                    }
                    cycle++
                    // the transmission in between; its request stops the stream, the next round starts it again. The gap
                    // counts from the end of the last one, so the stream always runs [fastTcuGapMs] in between
                    if (!fastShort && Repo.settings.gearbox != "mt" && now - fastTcuMs >= streamRestarts.tcuGapMs(init.romId, Repo.settings.tcuEvery)) {
                        pollTcu(client, now)
                        fastTcuMs = System.currentTimeMillis()
                    }
                    if (!fastShort) checkCodes(now)
                    continue
                }
                if (dynoShort != null && !dynoPoll.fullDue(dynoNow)) {
                    val short = EcuLink.mutex.withLock { client.read(dynoShort) }
                    val now = System.currentTimeMillis()
                    if (short != null) {
                        failures = 0
                        val snap = EcuParams.decode(dynoShort, short, now, skip)
                        val fresh = dynoFresh(dynoShort, short)
                        onSnapshot(dynoPoll.full?.let { DynoPoll.merge(it, snap) } ?: snap, now, merged = true, knockFresh = fresh)
                        cycle++
                        continue
                    }
                    if (transport.passive) continue
                    // this ECU won't take the short list: the whole one, for this connection
                    dynoFine = false
                }
                // the ticked parameters that answered on their own ride in the same request, as in the stream: read
                // apart, they were a request later than the regular values (load_grev and P200 differed in the log)
                ecuWatch.refresh()
                val extra = if (joinWatch && ecuWatch.answers) ecuWatch.addresses.filter { it !in addrs }.toIntArray() else IntArray(0)
                var read = addrs + extra
                var raw = EcuLink.mutex.withLock { client.read(read) }
                if (raw == null && extra.isNotEmpty() && !transport.passive) {
                    read = addrs
                    raw = EcuLink.mutex.withLock { client.read(read) }
                    // refused together only when the regular ones then answer on their own, twice running: one lost
                    // answer, or the ECU going mute, read the ticked ones apart for good on a Legacy BP and an alps unit
                    if (raw != null && ++joinRefused >= 2) {
                        joinWatch = false
                        SsmTrace.log("watched addresses refused together with the regular ones: read apart")
                    }
                } else if (raw != null && extra.isNotEmpty()) joinRefused = 0
                val now = System.currentTimeMillis()
                if (raw == null && transport.passive) continue
                if (raw == null) {
                    failures++
                    tracker.tick(now)
                    if (ecuMuteOnLine(transport)) { muteHold = true; break }
                    continue
                }
                failures = 0
                if (cycle == 0) logFirstRead(addrs, raw)
                var snap = EcuParams.decode(read, raw, now, skip)
                // SSM answers any address; an A/F byte from an ECU without the wideband sensor would fake
                // "enrichment" in the fuel calculation
                if (!afSensor) snap = snap.copy(lambda = null)
                if (read.size > addrs.size) ecuWatch.take(read, raw) else ecuWatch.pollAlongside(client, read, raw)
                dynoPoll.noteFull(snap, now)
                onSnapshot(snap, now)
                // not in a pull: its turn would cost the short list one more request
                if (++cycle % Repo.settings.tcuEvery.coerceAtLeast(1) == 0 && dynoShort == null && Repo.settings.gearbox != "mt") pollTcu(client, now)
                if (dynoShort == null) checkCodes(now)
            } } finally {
                // the ECU would go on sending to nobody; whoever comes next starts from a quiet line (a cable taken
                // from us has no line left to quiet)
                if (client.streaming && SensorBoxLink.ecuLease?.permitted != false) runCatching { client.stopStream() }
                // 0: never learnt, or proven wrong by a longer answer (SsmClient.proven)
                if (client.ecuReadLimit > 0) ecuReadLimits[init.romId] = client.ecuReadLimit else ecuReadLimits.remove(init.romId)
                if ((transport as? kg.forester.sg.ssm.KLineTransport)?.longGap == true) longGapRoms += init.romId
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
            SsmTrace.keepPollTail(12)
            SsmTrace.log("ECU silent, polling stopped")
            // a fault, not a key-off: the engine ran up to the last sample, and the link is still down half a minute later
            if (faultWatch.looksLikeFault()) {
                val since = System.currentTimeMillis()
                scope.launch {
                    delay(30_000)
                    if (faultWatch.confirm(Repo.link.value == LinkState.CONNECTED, System.currentTimeMillis() - since)) LogUpload.auto(this@EcuService, "ECU silent")
                }
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь с ЭБУ потеряна"
            logger?.close(); logger = null
            // mute on a live line: the K-line held low now, not after five silent cycles and a round of DTR/RTS levels
            // (a Legacy BP lost 30 s each time, answering only once the line had been low; the cable just worked, so
            // its levels are right). Still silent after it: the round below, with its own hold and the reopen
            if (muteHold) {
                muteHold = false
                Repo.linkMessage.value = "ЭБУ молчит, сбрасываю K-линию…"
                val kl = transport as? kg.forester.sg.ssm.KLineTransport
                if (kl?.holdLineLow(2500) == true) {
                    SsmTrace.log("ECU mute while the cable echoes: K-line held low 2.5 s at once")
                    kl.heardEcho = false
                    delay(500)
                }
            }
        }
    }

    private var wasPassive = false
    private var wasShared = false

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
        // by the setting: the other device (a Blitz and the like) is always there, asking or not
        val shared = (transport as? kg.forester.sg.ssm.KLineTransport)?.listenOnly == true
        if (!wasPassive || shared != wasShared) {
            wasPassive = true
            wasShared = shared
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = if (shared) "K-линия занята другим прибором: только слушаю, показываю то, что спрашивает он"
                else "Тихий режим: на K-линии второй диагност (ноутбук). Не мешаю ему, читаю то, что он спрашивает"
            SsmTrace.log(if (shared) "passive: listening only (another device on the line, by the setting)" else "passive: another tester polls the ECU")
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
     * samples a second would triple what every chart draws on each new sample.
     */
    private fun addHistory(snap: EcuSnapshot) {
        val t = snap.timeMs
        if (historyMs != 0L && t - historyMs in 0 until HISTORY_STEP_MS) return
        historyMs = t
        Repo.history.add(snap)
    }

    /**
     * The ROM's own values read in a short frame ([read], [raw] in its order) go where the full read puts them.
     * True when the FBKC was among them: the knock events can be fed from this frame.
     */
    private fun dynoFresh(read: IntArray, raw: ByteArray): Boolean {
        val (fbkc, map) = guard.fastRoles()
        val got = listOfNotNull(fbkc, map).filter { ecuWatch.proven(listOf(it)).let { a -> a.isNotEmpty() && a.all { x -> x in read } } }
        ecuWatch.takeOnly(got, read, raw)
        return fbkc != null && fbkc in got
    }

    /**
     * [merged]: a short frame of the dyno over the last full read, see [DynoPoll.route]; [knockFresh]: with the FBKC in
     * the frame itself.
     */
    private fun onSnapshot(raw0: EcuSnapshot, now: Long, merged: Boolean = false, knockFresh: Boolean = false) {
        val route = DynoPoll.route(merged, knockFresh)
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
        sampleGaps.tick(android.os.SystemClock.elapsedRealtime())
        addHistory(snap)
        if (route.rules) battery.process(snap)
        // the Link demo's made-up drive is no trip of this car (as the regular demo's isn't)
        if (!Repo.linkDemo.value) tracker.process(snap, Repo.gps.value) // first: a new trip clears the knock events
        if (route.scales) kg.forester.sg.data.Scales.process(snap)
        // a merged frame's MAF and timing are from the last full read: a load from them with the fresh rpm would put a
        // full-throttle knock down as part throttle
        if (route.knock) knock.process(if (merged) snap.copy(mafGs = null, timingDeg = null) else snap)
        extras(snap, now, route.rules)
        // merged rows stay out of the log: old values with a new time. DynoStore keeps the fast samples
        if (merged) { /* the file goes on with the next full read */ }
        else if (Repo.settings.logEnabled && (snap.rpm ?: 0) > 0 && !Repo.linkDemo.value) {
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
        // a sports ECU has no ROM to tell the car by, and says nothing against the model it has
        if (romId != "LINK") kg.forester.sg.ui.car3dFromEcu(s.car3d, s.car3dCustom, known).let { if (it != s.car3d) s.car3d = it }
        if (known == null) return
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
        var lastTick = android.os.SystemClock.elapsedRealtime()
        while (scope.isActive) {
            delay(30_000)
            // awake time only: a unit that went to sleep after the drive did right, and the hours it slept are no
            // «on without the ECU» (1.0-beta4 said «842 min» on waking up from a night's sleep)
            val now = android.os.SystemClock.uptimeMillis()
            val tick = android.os.SystemClock.elapsedRealtime()
            val slept = tick - lastTick > 5 * 60_000L
            lastTick = tick
            if (slept) { ranMs = 0L; silentSince = 0L; said = 0; continue }
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
    private fun extras(snap: EcuSnapshot, now: Long, rules: Boolean = true) {
        faultWatch.sample(snap.rpm, now)
        runCatching { guard.process(snap, Repo.tcu.value, now, rules) }.onFailure { SsmTrace.log("guard: $it") }
        val s = Repo.settings
        if (s.feature("motion")) runCatching { Motion.process(snap, Repo.gps.value, now) }
        if (s.feature("perf")) runCatching { Perf.process(snap, Repo.gps.value, now) }.onFailure { SsmTrace.log("perf: $it") }
    }

    /**
     * A CANable on the dash CAN of a sports ECU (Link G4X…): nothing is asked, the ECU streams by itself. The Link's
     * Dash2Pro or Generic Dash is found on its own; anything else is read with the driver's own CAN channels.
     */
    private suspend fun canableSession(port: UsbSerialPort) {
        // «Подобрать скорость» (the CAN wizard): listen-only at each speed before the session opens the bus
        if (kg.forester.sg.ssm.CanBaudProbe.requested) {
            Repo.link.value = LinkState.CONNECTING
            Repo.linkMessage.value = "CANable: подбираю скорость шины (только приём)"
            kg.forester.sg.ssm.CanBaudProbe.runOn(port) { Repo.settings.canKbit = it }
        }
        val kbit = Repo.settings.canKbit
        // normal mode on a sports ECU's dash CAN (it may be the only node to ACK the stream), listen-only on the car's bus
        val listenOnly = kg.forester.sg.data.CanConfig.slcanListenOnly()
        val bus = kg.forester.sg.ssm.SlcanPort(port)
        if (!bus.open(kbit, listenOnly)) {
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "CANable не включил режим «только приём»: шина машины не открыта. Обновите прошивку slcan или выключите этот режим в мастере CAN"
            SsmTrace.log("CANable: listen-only refused")
            delay(5000)
            return
        }
        SsmTrace.log("CANable: bus open at $kbit kbit/s" + if (listenOnly) ", listen-only" else "")
        canSession(bus, kbit) {
            Repo.settings.canKbit == kbit && kg.forester.sg.data.CanConfig.slcanListenOnly() == listenOnly && !kg.forester.sg.ssm.CanBaudProbe.requested
        }
    }

    /**
     * The CAN adapter on a USB serial chip (an Arduino or ESP32 with RealDash CAN, an ESP32RET, an slcan clone): its
     * protocol picked, remembered or asked for, then the same session as the CANable's.
     */
    private suspend fun canUsbSession(port: UsbSerialPort, driver: UsbSerialDriver, id: String) {
        val sig = kg.forester.sg.data.CanConfig.signature()
        Repo.link.value = LinkState.CONNECTING
        Repo.linkMessage.value = "USB $id: узнаю протокол CAN-адаптера…"
        val serial = UsbDeviceRegistry.serial(driver.device)?.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        // an slcan adapter may find the speed first (the CAN wizard's probe): the one it opened at is in the settings then
        val bus = CanLinks.openUsb(port, driver.device.deviceName, id + serial, Repo.settings.canKbit)
        val kbit = Repo.settings.canKbit
        if (bus == null) {
            Repo.link.value = LinkState.ERROR
            Repo.linkMessage.value = if (CanLinks.lastNote.isNotEmpty()) "USB $id → ${CanLinks.lastNote}"
                else "USB $id не ответил как CAN-адаптер (RealDash CAN, GVRET, slcan). RealDash-адаптер молчит, " +
                    "пока на шине нет кадров: включите зажигание или выберите протокол вручную"
            delay(2000)
            return
        }
        Repo.adapterFound.value = "USB $id → ${CanLinks.lastNote}"
        SsmTrace.log("CAN ${bus.title} on USB $id at $kbit kbit/s")
        canSession(bus, kbit) {
            Repo.settings.canKbit == kbit && kg.forester.sg.data.CanConfig.signature() == sig && !kg.forester.sg.ssm.CanBaudProbe.requested
        }
    }

    /** The CAN adapter on Bluetooth (SPP or BLE UART) or Wi-Fi (TCP, GVRET's port 23 by default, found by mDNS). */
    private suspend fun canRemoteSession() {
        val cfg = kg.forester.sg.data.CanConfig
        val sig = cfg.signature()
        Repo.link.value = LinkState.CONNECTING
        val link = if (cfg.transport == "bt") {
            if (cfg.btAddress.isEmpty()) {
                Repo.link.value = LinkState.NO_CABLE
                Repo.linkMessage.value = "Выберите Bluetooth CAN-адаптер: Настройки → «Машина и адаптер»"
                return
            }
            if (!btAllowed()) {
                Repo.link.value = LinkState.NO_PERMISSION
                Repo.linkMessage.value = "Нет разрешения на Bluetooth (Настройки → «Машина и адаптер»)"
                return
            }
            Repo.linkMessage.value = "Подключение к ${cfg.btName.ifEmpty { cfg.btAddress }}…"
            CanLinks.bluetooth(this, cfg.btAddress) { btSocket(cfg.btAddress, cfg.btName, quiet = true) }
        } else {
            val target = CanLinks.hostPort(cfg.tcpHost)
                ?: CanLinks.discover(this, 3000).firstOrNull()?.let { CanLinks.hostPort(it.second) }
                ?: CanLinks.hostPort(CanLinks.DEFAULT_TCP)!!
            Repo.linkMessage.value = "Подключение к CAN-адаптеру ${target.first}:${target.second}…"
            CanLinks.tcp(this, target.first, target.second)
        }
        if (link == null) {
            Repo.link.value = LinkState.NO_CABLE
            Repo.linkMessage.value = if (cfg.transport == "bt") "Bluetooth CAN-адаптер не отвечает (включено питание? не занят другим приложением?)"
                else "Wi-Fi CAN-адаптер не отвечает: магнитола в его сети? Адрес в настройках верный?"
            return
        }
        try {
            val order = if (cfg.transport == "tcp") listOf("gvret", "realdash", "slcan") else listOf("realdash", "gvret", "slcan")
            val bus = CanLinks.openRemote(link, Repo.settings.canKbit, order)
            // the speed the adapter was opened at (an slcan one may have found it by the wizard's probe)
            val kbit = Repo.settings.canKbit
            if (bus == null) {
                Repo.link.value = LinkState.ERROR
                Repo.linkMessage.value = if (CanLinks.lastNote.startsWith("slcan:")) "${link.label} → ${CanLinks.lastNote}"
                    else "${link.label}: не похоже на CAN-адаптер (RealDash CAN, GVRET, slcan). " +
                        "RealDash-адаптер молчит, пока на шине нет кадров: включите зажигание или выберите протокол вручную"
                return
            }
            Repo.adapterFound.value = "${link.label} → ${bus.title} (${CanLinks.lastNote})"
            SsmTrace.log("CAN ${bus.title} on ${link.label} at $kbit kbit/s")
            canSession(bus, kbit) {
                Repo.settings.adapter == "canable" && Repo.settings.canKbit == kbit && cfg.signature() == sig && !kg.forester.sg.ssm.CanBaudProbe.requested
            }
        } finally {
            runCatching { link.close() }
        }
    }

    /**
     * The SensorBox's own CAN (an ESP32-S3 with a transceiver) in place of a CANable: the same session, the frames
     * come through [SensorBoxLink]'s connection to the box. Its speed and mode are the box's settings («Датчики и экраны», CAN-шина).
     */
    private suspend fun boxCanSession() {
        val kbit = kg.forester.sg.data.BoxCan.cfg.value.kbit
        SsmTrace.log("SensorBox CAN: session at $kbit kbit/s")
        // the box takes the speed it is set to: the wizard's speed probe is for slcan adapters
        kg.forester.sg.ssm.CanBaudProbe.unsupported("SensorBox")
        kg.forester.sg.data.BoxCanBus.Source().use { bus ->
            canSession(bus, kbit) {
                Repo.settings.adapter == "canable" && kg.forester.sg.data.BoxCanBus.available() && kg.forester.sg.data.BoxCan.cfg.value.kbit == kbit &&
                    !kg.forester.sg.ssm.CanBaudProbe.requested
            }
        }
    }

    /**
     * The frames of [bus] (a CANable, a RealDash CAN or GVRET device, the SensorBox or the Link demo) through the Link's
     * decoding, while [on]. The status line names the adapter by its [kg.forester.sg.ssm.CanSource.title].
     */
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
        Repo.settings.carTurbo = kg.forester.sg.data.HomeLayout.carHasTurbo(false, false, true, Repo.settings.linkTurbo)
        // the untouched factory screen: the cards a Link fills, not the stock ECU's it leaves empty
        if (Repo.settings.homeTiles.isEmpty()) Repo.settings.homeTiles = kg.forester.sg.data.HomeLayout.linkDefault()
        ecuWatch.clear(); tcuWatch.clear()
        Repo.obdMode.value = false
        Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
        Repo.tcuStatus.value = "АКПП: нет (спортивный ЭБУ по CAN)"
        Repo.link.value = LinkState.CONNECTING
        val name = bus.title
        Repo.linkMessage.value = "$name: шина $kbit кбит открыта, жду кадры"
        // the one adapter the transmit gate may write to while this session runs
        kg.forester.sg.ssm.CanTx.attach(bus)
        var lastFrame = System.currentTimeMillis()
        var lastSample = 0L
        var linkLogged = false
        var limitsLogged = emptyList<String>()
        try {
            while (scope.isActive && !Repo.settings.demo && on() && SensorBoxLink.ecuLease?.permitted != false) {
                val frames = bus.read(100)
                val now = System.currentTimeMillis()
                for (f in frames) kg.forester.sg.ssm.CanBus.dispatch(f, now, link = true)
                if (frames.isNotEmpty()) lastFrame = now
                if (now - lastSample < 200) continue
                lastSample = now
                // a sports ECU says how many by itself, in its stream: no request
                kg.forester.sg.data.CanEcu.values()["${kg.forester.sg.data.CanEcu.PREFIX}FAULTS"]?.toInt()?.let { n ->
                    if (Repo.checkEngine.value?.engine != n) Repo.checkEngine.value = CheckEngine(engine = n)
                }
                if (now - lastFrame > 3000) {
                    Repo.link.value = LinkState.NO_ECU
                    Repo.linkMessage.value = "$name на связи, но шина молчит. Зажигание включено? Скорость $kbit кбит та же, что в настройках CAN у ЭБУ?" +
                        if (bus.errors > 0) " Адаптер отвечает ошибкой (${bus.errors})" else ""
                    tracker.tick(now)
                    continue
                }
                val id = link.id
                if (id != null && !linkLogged) { linkLogged = true; SsmTrace.log("Link ${link.format?.title} on CAN id %03X (%d)".format(id, id)) }
                link.limits(now).takeIf { it != limitsLogged }?.let { limitsLogged = it; if (it.isNotEmpty()) SsmTrace.log("Link limits: ${it.joinToString()}") }
                Repo.link.value = LinkState.CONNECTED
                Repo.linkMessage.value = if (id != null) "Link (${link.format?.title}, ID %d) · %s %d кбит".format(id, name, kbit)
                    else "$name: на шине ${kg.forester.sg.ssm.CanMonitor.idsSeen(now)} ID, поток Link (Dash2Pro или Generic Dash) не найден. Что идёт по CAN: Настройки, Машина и адаптер"
                Repo.rrAvailable.value = kg.forester.sg.data.CanEcu.params()
                Repo.rrPolled.value = Repo.rrAvailable.value
                Repo.rrValues.value = kg.forester.sg.data.CanEcu.values(now)
                Repo.pollAddresses.value = Repo.rrAvailable.value.size
                val snap = if (link.fresh(now)) link.snapshot(now) else EcuSnapshot(timeMs = now)
                onSnapshot(snap, now)
            }
        } finally {
            kg.forester.sg.ssm.CanTx.detach(bus)
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
            // a new connection takes up what the last one learned, before anything watches obdMode (CarKind writes it once it
            // knows the car is another make)
            Repo.settings.updateOtherTurbo { it.connected() }
            Repo.obdMode.value = true
            Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
            Repo.tcuStatus.value = "АКПП: недоступна в режиме OBD-II"
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "OBD-II · ${link.name}: основные параметры (SSM не ответил)"
            // the VIN once: the make for the trouble codes and OBDb, the car for the header
            val vin = EcuLink.mutex.withLock { runCatching { obd.vin() }.getOrNull() }
            SsmTrace.log(if (vin != null) "VIN read ok, ${vin.length} bytes (OBD 09 02)" else "VIN read failed (OBD 09 02)")
            // a Subaru that has spoken SSM on this app before, caught by the K-line OBD try just as the ignition came on:
            // SSM gives it all, so back to it (one older than SSM2 has never answered it and stays here)
            if (link is kg.forester.sg.ssm.KLineObd && vin != null && Repo.settings.lastRomId.isNotEmpty() &&
                runCatching { kg.forester.sg.data.Brands.get(this).isSubaru(vin) }.getOrDefault(false)) {
                SsmTrace.log("K-line OBD: a Subaru that answers SSM, back to SSM")
                return true
            }
            if (vin != null) Repo.vin.value = vin
            // the unit's own name and calibration (mode 09), for Настройки → Система instead of the SSM ids
            val ecuIds = EcuLink.mutex.withLock { runCatching { obd.ecuIds() }.getOrNull() }
            SsmTrace.log("ECU (OBD 09 0A/04): ${ecuIds ?: "—"}")
            Repo.obdEcu.value = ecuIds.orEmpty()
            var obdb = obdbPoller(vin)
            var obdbSeen = kg.forester.sg.ssm.Obdb.changed.value
            Repo.pollAddresses.value = obd.polledCount
            var failures = 0
            SsmTrace.polling = true
            Repo.checkEngine.value = null
            codeWatch.newConnection(System.currentTimeMillis())
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
                if (codeWatch.due(now)) {
                    // mode 01 PID 01: one request, the lamp and the stored codes' count
                    codeWatch.done(now)
                    EcuLink.mutex.withLock { runCatching { obd.readiness() }.getOrNull() }?.let { Repo.checkEngine.value = CheckEngine(it.dtcCount, 0, it.mil) }
                }
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
     * A pre-1999 engine unit over SSM1 (the old Select Monitor protocol: 1953 baud, one address streamed at a time) on
     * a USB serial cable wired to the OBD socket's pins 12 and 13. Its values fill the common snapshot where the unit
     * matches (rpm, speed, coolant, battery, timing, injection, mixture correction, wastegate), so the home screen, the
     * log and the trips work as on SSM2; every block of the unit is also a parameter on «Датчики».
     *
     * Returns false when nothing answered SSM1 (the caller makes the next connection an SSM2 one), true after a session.
     */
    private suspend fun ssm1Session(port: UsbSerialPort): Boolean {
        val client = kg.forester.sg.ssm.SsmP1Client(port)
        val base = kg.forester.sg.ssm.SsmP1Defs.base(this)
        try {
            client.open()
            var id: String? = null
            for (attempt in 1..5) {
                if (!scope.isActive) return true
                id = EcuLink.mutex.withLock { runCatching { client.getId() }.onFailure { SsmTrace.log("SSM1: $it") }.getOrNull() }
                    ?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                if (id != null) break
                Repo.link.value = LinkState.NO_ECU
                Repo.linkMessage.value = "Старый ЭБУ (SSM1) не отвечает. Зажигание включено?"
                delay(2000)
            }
            if (id == null) { SsmTrace.log("SSM1: no answer, an SSM2 connection next"); return false }
            Repo.settings.ssm1Rom = id
            val match = base.find(id)
            SsmTrace.log("SSM1 ROM $id: " + (match?.let { "${it.rom.name} (${it.rom.source})" + if (it.exact) "" else ", closest of the family" } ?: "unknown family"))
            if (match == null) {
                Repo.link.value = LinkState.NO_ECU
                Repo.linkMessage.value = "Старый ЭБУ (SSM1), ROM $id: такого ЭБУ пока нет в базе приложения. " +
                    "Отправьте журнал (Настройки, Машина и адаптер), его добавят"
                // keep the line until the unit goes quiet (the ignition off), then the usual reconnect
                while (scope.isActive) {
                    delay(5000)
                    if (EcuLink.mutex.withLock { runCatching { client.getId() }.getOrNull() } == null) return true
                }
                return true
            }
            val blocks = base.blocks(match.rom)
            val params = blocks.map { (mb, addr) -> kg.forester.sg.ssm.SsmP1Defs.param(mb, addr) }
            EcuLink.ssm1 = client
            Repo.ssm1.value = match
            Repo.obdMode.value = false
            Repo.ecuInit.value = null; Repo.ecuInitRaw.value = null
            Repo.tcuStatus.value = "АКПП: у старых ЭБУ (SSM1) не читается"
            Repo.checkEngine.value = null
            Repo.rrAvailable.value = params
            Repo.pollAddresses.value = blocks.size
            Repo.link.value = LinkState.CONNECTED
            Repo.linkMessage.value = "Старый ЭБУ (SSM1) · ${match.rom.name} · ROM $id" +
                (if (match.exact) "" else ". Адреса взяты у похожего ЭБУ этого семейства, проверьте показания") +
                ". Опрос медленный, пара обновлений в секунду"
            SsmTrace.polling = true
            codeWatch.newConnection(System.currentTimeMillis())
            val fast = blocks.filter { kg.forester.sg.ssm.SsmP1Defs.baseKey(it.first.key) in kg.forester.sg.ssm.SsmP1Defs.FAST }
            val core = setOf("MB_TW", "MB_VB", "MB_ADVS", "MB_TIM", "MB_ALPHA", "MB_WGC")
            val values = HashMap<String, Double>()
            var turn = 0
            var misses = 0
            while (scope.isActive && !Repo.settings.demo && SensorBoxLink.ecuLease?.permitted != false) {
                val now = System.currentTimeMillis()
                val ticked = Repo.settings.watchIds + Repo.internalWatch.value
                // the snapshot's slower values and what is ticked on «Датчики»: two of them a round, in turn
                val slow = blocks.filter { (mb, _) ->
                    fast.none { f -> f.first.key == mb.key } && (kg.forester.sg.ssm.SsmP1Defs.baseKey(mb.key) in core ||
                        kg.forester.sg.ssm.SsmP1Defs.PREFIX + mb.key in ticked)
                }
                val round = fast + if (slow.isEmpty()) emptyList() else List(minOf(2, slow.size)) { slow[(turn + it) % slow.size] }
                turn += 2
                var got = 0
                for ((mb, addr) in round) {
                    if (!scope.isActive) break
                    val raw = EcuLink.mutex.withLock { runCatching { client.readByte(addr) }.getOrNull() } ?: continue
                    got++
                    mb.value(raw)?.let { values[mb.key] = it }
                }
                if (got == 0) {
                    tracker.tick(now)
                    if (++misses >= 3) break
                    continue
                }
                misses = 0
                Repo.rrPolled.value = params.filter { it.id in ticked }
                Repo.rrValues.value = values.mapKeys { kg.forester.sg.ssm.SsmP1Defs.PREFIX + it.key }
                onSnapshot(kg.forester.sg.ssm.SsmP1Defs.snapshot(values, now), now)
            }
            Repo.link.value = LinkState.NO_ECU
            Repo.linkMessage.value = "Связь со старым ЭБУ (SSM1) потеряна"
            return true
        } finally {
            SsmTrace.polling = false
            EcuLink.ssm1 = null
            Repo.ssm1.value = null
            Repo.rrAvailable.value = emptyList(); Repo.rrPolled.value = emptyList(); Repo.rrValues.value = emptyMap()
            logger?.close(); logger = null
            runCatching { client.stop() }
            runCatching { client.close() }
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

        /** The CarBerry's own VE ticked once by itself, at a connection or at the profile's choice; if unticked, it stays so. */
        private fun tickVe() {
            val s = Repo.settings
            if (!tcu && !s.carberryVeTicked && avail.any { it.id == RomRaider.CARBERRY_VE }) {
                s.carberryVeTicked = true
                s.watchIds = s.watchIds + RomRaider.CARBERRY_VE
            }
        }

        fun setup(init: EcuInit) {
            all = defs()
            profile = Repo.settings.loggerProfile
            avail = RomRaider.available(this@EcuService, init, tcu, profile)
            this.init = init
            custom = Repo.customVersion.value
            defsSeen = Repo.defsVersion.value
            if (tcu) tcuAvail = avail else ecuAvail = avail
            tickVe()
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
                init?.let { avail = RomRaider.available(this@EcuService, it, tcu, profile); if (tcu) tcuAvail = avail else ecuAvail = avail; tickVe() }
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

        /**
         * [poll] after a read of [have] ([haveRaw] in its order): only the addresses that read lacks are asked for. A
         * Legacy BP read 0x09 0x0A 0x0E 0x0F 0x1C 0x3A 0x3C twice a cycle, in the regular list and again here.
         */
        suspend fun pollAlongside(client: SsmClient, have: IntArray, haveRaw: ByteArray) {
            refresh()
            if (addrs.isEmpty()) return
            val missing = addrs.filter { it !in have }.toIntArray()
            if (missing.size == addrs.size) return poll(client)
            val raw = if (missing.isEmpty()) ByteArray(0) else EcuLink.mutex.withLock { client.read(missing, dest) }
            if (raw == null) {
                answers = false
                if (++fails >= 3) prune(client)
                return
            }
            fails = 0
            answers = true
            take(have + missing, haveRaw + raw)
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

        /** The addresses of [list], if all of it is watched and has answered in a read: safe for a stream or a short list. */
        fun proven(list: List<RrParam>): IntArray {
            if (!answers) return IntArray(0)
            val out = RomRaider.readList(list.filter { it in params }, all)
            return if (out.all { it in addrs }) out else IntArray(0)
        }

        /** Only the values of [list] out of a read that has their addresses. */
        fun takeOnly(list: List<RrParam>, read: IntArray, raw: ByteArray) {
            if (list.isEmpty()) return
            Repo.rrValues.value = Repo.rrValues.value + RomRaider.decode(list, read, raw, all)
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
                val raw = EcuLink.mutex.withLock { SsmTrace.muted { client.read(IntArray(17) { at + it }) } }
                val vin = raw?.map { (it.toInt() and 0xFF).toChar() }?.joinToString("")?.trim()
                if (vin != null && vin.length == 17 && vin.all { it.isLetterOrDigit() }) Repo.vin.value = vin
                SsmTrace.log(if (vin != null) "VIN read ok, ${vin.length} bytes" else "VIN read failed")
            }
        }
        // on CAN the same unit also answers standard OBD-II: one request (mode 09 PID 02) for a VIN SSM did not give
        if (Repo.vin.value.isNullOrEmpty() && client.transport.isCan) {
            val r = EcuLink.mutex.withLock { SsmTrace.muted { runCatching { client.transport.exchange(SsmClient.ECU, byteArrayOf(0x09, 0x02)) }.getOrNull() } }
            val vin = r?.takeIf { it.size > 3 && it[0] == 0x49.toByte() && it[1] == 0x02.toByte() }?.let { Obd2.vinOf(it.copyOfRange(3, it.size)) }
            SsmTrace.log(if (vin != null) "VIN read ok, ${vin.length} bytes (OBD 09 02)" else "VIN read failed (OBD 09 02)")
            if (vin != null) Repo.vin.value = vin
        }
        if (init.supports(19, 5)) {
            val t = EcuLink.mutex.withLock { client.read(intArrayOf(0x61)) }
            SsmTrace.log("diag: 0x61=${t?.firstOrNull()?.let { "%02X".format(it.toInt() and 0xFF) } ?: "—"}", keep = true)
            if (t != null && t.isNotEmpty() && (t[0].toInt() shr 5) and 1 == 1) {
                Alerts.raise(
                    "testMode", "ЭБУ видит замкнутые разъёмы тест-режима (зелёные, под рулём): «чек» мигает и ЭБУ может записать " +
                        "ложные ошибки. Разъедините их, если не проводите диагностику. Тот же признак может дать кабель, " +
                        "вставленный в разъём до включения зажигания: попробуйте вынуть его и включить зажигание ещё раз", Alerts.Level.WARN, 60 * 60_000L,
                )
            }
        }
    }

    /**
     * Once per ROM id (kept in the settings, so a reconnect costs nothing): the ROM's own IAM constants for the guard
     * and, where the ROM has both P90 and E31, the two compared in the trace to see which is right. Nothing of this is
     * polled. The constants are read one by one and the first refusal stops it and counts a failed connection; after 3
     * of them the ROM is left alone for 30 days ([EngineGuard.shouldReadRomIam]), a success clears the count.
     */
    private suspend fun readRomIam(client: SsmClient, init: EcuInit) {
        val rom = init.romId.uppercase()
        val s = Repo.settings
        val cache = runCatching { JSONObject(s.romIamCache) }.getOrDefault(JSONObject())
        val e = cache.optJSONObject(rom) ?: JSONObject().also { cache.put(rom, it) }
        val all = RomRaider.available(this, init, false)
        suspend fun value(p: RrParam): Double? {
            val raw = EcuLink.mutex.withLock { runCatching { client.read(p.addresses) }.getOrNull() } ?: return null
            return RomRaider.decode(listOf(p), p.addresses, raw, all)[p.id]
        }
        fun f(k: String) = if (e.has(k)) e.getDouble(k).toFloat() else null
        val consts = RomRaider.romConstants(rom)
        val now = System.currentTimeMillis()
        if (consts.isNotEmpty() && (e.has("IAM_INIT") || EngineGuard.shouldReadRomIam(e.optInt("failures"), e.optLong("lastTry"), now))) {
            if (!e.has("IAM_INIT")) {
                val got = LinkedHashMap<String, Float>()
                for (p in consts) { got[p.id] = value(p)?.toFloat() ?: break }
                val ok = got.size == consts.size
                if (ok) { got.forEach { (k, v) -> e.put(k, v.toDouble()) }; e.remove("failures") }
                else { e.put("failures", e.optInt("failures") + 1); e.put("lastTry", now) }
                SsmTrace.log("rom iam $rom: $got" + if (ok) "" else " refused (${e.optInt("failures")})", keep = true)
                s.romIamCache = cache.toString()
            }
            if (e.has("IAM_INIT")) guard.romIam = EngineGuard.RomIam(f("IAM_INIT"), f("IAM_CUT"), f("IAM_RESTORE"), f("IAM_STEP")).checked()
        }
        val both = listOf("P90", "E31").mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (both.size == 2 && !e.optBoolean("compared")) {
            val v = both.map { p -> value(p)?.toFloat()?.let { EngineGuard.iamOf(it, p.units) } }
            SsmTrace.log("iam $rom: P90 ${v[0]}, E31 ${v[1]}" + if (v[0] != null && v[1] != null && kotlin.math.abs(v[0]!! - v[1]!!) >= 0.02f) " DIFFER" else "", keep = true)
            e.put("compared", true)
            s.romIamCache = cache.toString()
        }
    }

    /**
     * The rare look at the trouble codes behind the check-engine icon (see [CodeWatch]), between two regular reads. A unit
     * that gives no answer keeps its last count: one lost request must not put the icon out.
     */
    private suspend fun checkCodes(now: Long) {
        if (!codeWatch.due(now)) return
        codeWatch.done(now)
        // a failed look is only a missed check-engine icon, never a reason to drop the polling
        val ecu = runCatching { Repo.ecuInit.value?.let { kg.forester.sg.ui.DtcRepo.readCurrent(kg.forester.sg.ui.DtcRepo.defs(this, it), SsmClient.ECU) } }
            .onFailure { SsmTrace.log("codes: ECU read failed: ${it.message}") }.getOrNull()
        // the transmission only after the engine answered: two more long requests on a line that just lost one
        val tcu = if (Repo.settings.gearbox == "mt") 0 else if (ecu == null) null
            else runCatching { Repo.tcuInit.value?.let { kg.forester.sg.ui.DtcRepo.readCurrent(kg.forester.sg.ui.DtcRepo.defs(this, it), SsmClient.TCU) } }
                .onFailure { SsmTrace.log("codes: TCU read failed: ${it.message}") }.getOrNull()
        if (ecu == null && tcu == null) return
        val old = Repo.checkEngine.value
        Repo.checkEngine.value = CheckEngine(ecu ?: old?.engine ?: 0, tcu ?: old?.gearbox ?: 0)
    }

    /** The transmission unit answers at 0x18 on the same K-line; poll it every few ECU cycles. */
    private suspend fun pollTcu(client: SsmClient, now: Long) {
        var addrs = tcuAddrs
        if (addrs == null) {
            if (now < tcuNextTryMs) return
            val init = EcuLink.mutex.withLock { client.init(SsmClient.TCU) }
            if (init == null) {
                // MY1999–2000 (Impreza GC/GF, Forester SF, Legacy BE/BH early): the engine already speaks SSM2 on the
                // K-line, the TCU still the old SSM1 on its own line; FreeSSM can't read those either. One that has
                // answered in this connection has only gone quiet
                Repo.tcuStatus.value = if (tcuAnswered) TCU_QUIET else "Блок АКПП не отвечает по SSM. На Subaru 1999–2000 годов (Impreza GC/GF, Forester SF, " +
                    "ранние Legacy BE/BH) он работает по старому протоколу SSM1 на другой линии, через K-line его не прочитать " +
                    "(FreeSSM тоже не видит). У механики блока нет, а через часть Bluetooth-адаптеров он недоступен (надёжнее KKL-кабель)"
                tcuNextTryMs = now + 60_000
                return
            }
            Repo.tcuInit.value = init
            tcuWatch.setup(init)
            tcuAtfOnly = Repo.settings.tcuAtfOnly
            addrs = tcuList(init)
            tcuAddrs = addrs
            Repo.tcuStatus.value = "АКПП: ROM ${init.romId}"
        }
        if (addrs.isEmpty()) return
        if (Repo.settings.tcuAtfOnly != tcuAtfOnly) {
            tcuAtfOnly = Repo.settings.tcuAtfOnly
            addrs = tcuList(Repo.tcuInit.value ?: return)
            tcuAddrs = addrs
        }
        val raw = EcuLink.mutex.withLock { client.read(addrs, SsmClient.TCU) }
        if (raw == null) {
            tcuMisses++
            // 1.0-beta2 added the SportShift switch byte 0x6A wherever the init lists bit 7 of it: a unit that lists
            // it but refuses a request with it timed out on every turn. Two misses before any answer: drop it
            when (tcuMiss(tcuMisses, tcuAnswered, TcuParams.FWD in addrs)) {
                TcuMiss.DROP_FWD -> {
                    tcuNoFwd = true
                    tcuMisses = 0
                    tcuAddrs = addrs.filter { it != TcuParams.FWD }.toIntArray()
                    SsmTrace.log("TCU: no answer with 0x6A, reading without it")
                }
                TcuMiss.BACK_OFF -> {
                    tcuMisses = 0
                    tcuAddrs = null
                    tcuNextTryMs = now + TCU_RETRY_MS
                    Repo.tcu.value = null
                    Repo.tcuStatus.value = TCU_QUIET
                    SsmTrace.log("TCU: $TCU_MISSES_MAX requests unanswered, values cleared, init again in ${TCU_RETRY_MS / 1000} s")
                }
                TcuMiss.NONE -> Unit
            }
            return
        }
        tcuMisses = 0
        tcuAnswered = true
        // the ticked ones this read lacks only: 0x51 and 0x6D were read twice a turn
        tcuWatch.pollAlongside(client, addrs, raw)
        val t = lockUp(TcuParams.decode(addrs, raw, now, Repo.tcuInit.value), Repo.live.value.rpm, now)
        Repo.tcu.value = t
        Repo.tcuHistory.add(t)
    }

    /** What to read from the transmission: all it has, or with "only ATF" the temperature and the selector, a much shorter request. */
    private fun tcuList(init: EcuInit): IntArray {
        val all = TcuParams.supported(init).filter { !tcuNoFwd || it != TcuParams.FWD }.toIntArray()
        if (!tcuAtfOnly) return all
        // the selector keeps its switch byte: the SportShift manual gate is bit 7 of it
        return all.filter { it == TcuParams.ATF_OLD || it == TcuParams.ATF_NEW || it == TcuParams.RANGE || it == TcuParams.GEAR ||
            (it == TcuParams.FWD && TcuParams.manualSwitch(init)) }.toIntArray()
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

    /** The fixes come here, not on the main thread: there a busy screen held them up and the other way round. */
    private var gpsThread: android.os.HandlerThread? = null
    private val gpsLooper: Looper
        @Synchronized get() = (gpsThread ?: android.os.HandlerThread("gps").also { it.start(); gpsThread = it }).looper

    // for a log sent from a car whose map goes in steps: how the receiver takes its fixes, how they get here, what it is
    private val gpsTaken = GapLog("gps taken", 1500) { SsmTrace.gap(it) }
    private val gpsArrived = GapLog("gps arrived", 1500) { SsmTrace.gap(it) }
    private val sampleGaps = GapLog("ecu sample", 500) { SsmTrace.gap(it) }
    private var gpsSourceLogged = ""

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val now = System.currentTimeMillis()
            lastGpsFixMs = now
            Repo.gpsStatus.value = ""
            gpsTaken.tick(l.elapsedRealtimeNanos / 1_000_000)
            gpsArrived.tick(android.os.SystemClock.elapsedRealtime())
            // the time the fix was taken, not when it got here: it gets here 50–300 ms late, unevenly, and the run timer
            // counts between fixes
            val age = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
            val source = "${l.provider}, speed ${l.hasSpeed()}, bearing ${l.hasBearing()}, late ${age > 1000}"
            if (source != gpsSourceLogged) {
                gpsSourceLogged = source
                SsmTrace.gap("gps fix from $source" + if (age > 1000) ", age ${age.coerceIn(0L, 3000L)} ms" else "")
            }
            val fix = GpsFix(
                speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f,
                altitudeM = l.altitude,
                accuracyM = if (l.hasAccuracy()) l.accuracy else 99f,
                satellites = (l.extras?.getInt("satellites") ?: 0).takeIf { it > 0 } ?: gpsSats,
                timeMs = now - age.coerceIn(0L, 3000L),
                bearing = if (l.hasBearing()) l.bearing else null,
                lat = l.latitude, lon = l.longitude, cn0 = gpsCn0, speedKnown = l.hasSpeed(),
                arrivedMs = android.os.SystemClock.elapsedRealtime(),
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
            Repo.gps.value = GpsFix.fromNetwork(
                l.latitude, l.longitude, l.altitude, l.accuracy, l.hasAccuracy(), gpsSats,
                System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Satellites seen, the ones in the fix and their signal (dB-Hz), from either listener below. */
    private fun onSatellites(seen: Int, used: List<Float>) {
        gpsSats = used.size
        gpsCn0 = used.sortedDescending().take(4).takeIf { it.size == 4 }?.average()?.toFloat()
        Repo.gnssSats.value = gpsSats to seen
        if (System.currentTimeMillis() - lastGpsFixMs > 5000) {
            Repo.gpsStatus.value = "ищу спутники: видно $seen, в решении $gpsSats"
        }
    }

    // GnssStatus is Android 7.0+: the field is created only there, since on Android 6 the class itself is missing
    private val gnssCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) null else object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val n = status.satelliteCount
                onSatellites(n, (0 until n).filter { status.usedInFix(it) }.map { status.getCn0DbHz(it) })
            }
        }
    }

    /** Android 6: the old GpsStatus listener, the same numbers (SNR in place of C/N0). */
    @Suppress("DEPRECATION")
    private val gpsStatusListener = android.location.GpsStatus.Listener { event ->
        if (event != android.location.GpsStatus.GPS_EVENT_SATELLITE_STATUS) return@Listener
        @SuppressLint("MissingPermission")
        val sats = runCatching { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).getGpsStatus(null)?.satellites?.toList() }.getOrNull() ?: return@Listener
        onSatellites(sats.size, sats.filter { it.usedInFix() }.map { it.snr })
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun satellitesListen(lm: LocationManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cb = gnssCallback as android.location.GnssStatus.Callback
            if (on) runCatching { lm.registerGnssStatusCallback(cb, android.os.Handler(gpsLooper)) }
            else runCatching { lm.unregisterGnssStatusCallback(cb) }
        } else {
            if (on) runCatching { lm.addGpsStatusListener(gpsStatusListener) } else runCatching { lm.removeGpsStatusListener(gpsStatusListener) }
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
        satellitesListen(lm, false)
        runCatching {
            // as often as the receiver gives (most 1 Hz, some 5–10 Hz): the run timer goes by GPS from 4 Hz on
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, gpsListener, gpsLooper)
        }.onFailure { SsmTrace.log("gps: $it") }
        satellitesListen(lm, true)
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, netListener, gpsLooper)
        }
        gpsStartedMs = System.currentTimeMillis()
        gpsCheck()
        SsmTrace.log("gps started, providers ${lm.allProviders}, enabled ${lm.getProviders(true)}")
    }

    @Synchronized
    private fun stopGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(gpsListener) }
        runCatching { lm.removeUpdates(netListener) }
        satellitesListen(lm, false)
        gpsThread?.quitSafely()
        gpsThread = null
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
        else t.instPer100?.let { kg.forester.sg.data.DistanceUnits.consumptionText(it) } ?: "%.1f л/ч".format(t.instLh)
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(kg.forester.sg.data.tr(text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .apply {
                // another diagnostic app (BTSSM…) wants the same adapter: let it go from here, and take it back
                if (!Repo.settings.demo) {
                    val (label, action) = if (Repo.adapterReleased.value) "Подключиться" to ACTION_RECONNECT else "Отпустить адаптер" to ACTION_RELEASE
                    addAction(Notification.Action.Builder(null, kg.forester.sg.data.tr(label), PendingIntent.getService(
                        this@EcuService, 2, Intent(this@EcuService, EcuService::class.java).setAction(action),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )).build())
                }
            }
            .addAction(Notification.Action.Builder(null, kg.forester.sg.data.I18n.pick("Выход", "Exit"), PendingIntent.getService(
                this, 1, Intent(this, EcuService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE
            )).build())
            .build()
    }

    companion object {
        /** The charts' step (see addHistory): every sample of the plain polling, every third of the fast poll. */
        private const val HISTORY_STEP_MS = 200L

        /**
         * The stream's run between two transmission reads: N × 250 ms as with plain requests, but at least 3 s. Each
         * read stops the stream, and starting it again takes its whole request on the line (44 addresses, ~0.3 s at
         * 4800 baud): every 1.1 s that left a 0.7 s hole in the engine values each second on an FYT unit. An ECU that has
         * fallen silent on a restart ([restartFails], see [StreamRestarts]): every 20 s, the gearbox values a little older
         * rather than the engine's gone for 6 s.
         */
        internal fun fastTcuGapMs(tcuEvery: Int, restartFails: Int = 0): Long =
            maxOf(tcuEvery.coerceAtLeast(1) * 250L, if (restartFails > 0) 20_000L else 3000L)

        /** A freshly opened K-line only listens this long: another tester or gauge polling is heard before our init. */
        private const val LISTEN_FIRST_MS = 1000L

        /**
         * Consecutive USB write-timeouts the pollLoop puts up with before it resets the USB device on the next pass.
         * Three timeouts at ~1 s each is about 12 s of silence on the screen, which is enough to tell a wedged
         * FTDI from an ignition flicker.
         */
        private const val WRITE_TIMEOUT_STREAK = 3
        /** Write timeouts in a row after which the soft FTDI reset has clearly not helped: the bus reset. */
        private const val WRITE_TIMEOUT_HARD_RESET = 10
        /** A USB error this soon after a stream start, before its first frame, is put down to the stream. */
        private const val STREAM_INIT_BLAME_MS = 5000L
        /** FTDI vendor request, host-to-device, device recipient: `0x40` per USB spec, used for every FTDI control command. */
        private const val FTDI_VENDOR_OUT = 0x40
        /** `SIO_RESET_REQUEST`: subcommand goes in wValue. */
        private const val FTDI_SIO_RESET = 0x00
        /** Reset SIO state machine (data flow, flow control, line parameters) without a USB bus reset. */
        private const val FTDI_RESET_SIO = 0
        /** Purge the on-chip RX FIFO. */
        private const val FTDI_RESET_PURGE_RX = 1
        /** Purge the on-chip TX FIFO. */
        private const val FTDI_RESET_PURGE_TX = 2
        /** Transmission requests unanswered in a row: its values go, it is asked again later (see [tcuMiss]). */
        internal const val TCU_MISSES_MAX = 3
        internal const val TCU_RETRY_MS = 30_000L
        private const val TCU_QUIET = "Блок АКПП перестал отвечать, пробую снова"

        /**
         * One more unanswered transmission read ([misses] in a row). 1.0-beta2 added the SportShift switch byte 0x6A
         * wherever the init lists bit 7 of it: a unit that lists it but refuses a request with it timed out on every
         * turn, so two misses before any answer drop it. [TCU_MISSES_MAX]: gone quiet, as an FYT unit's TCU that never
         * answered again (a timeout every turn, a fifth of the stream lost, its last ATF and gear shown as live): its
         * values go, the init is sent again in [TCU_RETRY_MS].
         */
        internal fun tcuMiss(misses: Int, answered: Boolean, fwdInList: Boolean): TcuMiss = when {
            !answered && misses >= 2 && fwdInList -> TcuMiss.DROP_FWD
            misses >= TCU_MISSES_MAX -> TcuMiss.BACK_OFF
            else -> TcuMiss.NONE
        }

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
 * Stream restarts each ECU (by ROM) let pass in silence, the line live. A Legacy BP had 104 of 117 stream drops right
 * after a restart (the transmission read in between), one restart in seven, the ECU mute each time until the line was
 * held low. Once: the transmission far less often ([tcuGapMs]); [MAX] times: plain requests for that ROM, which kept
 * its line up far better. An ECU that streams through it all keeps the stream as it was.
 *
 * The [MAX]-and-over set is sticky: a cable reopen inside this session, a service restart, or an app relaunch brings
 * it back (the Legacy BP's log of 2026-11-16 had 12 session restarts in 65 min on the same ROM, each one starting the
 * 25-address stream afresh before falling back to plain requests). [load] and [save] carry it; a new build clears the
 * set (keyed by the installed versionCode in [Settings.fastPollOffRoms]). [load] is only called from the constructor.
 */
internal class StreamRestarts(
    load: () -> Set<String> = { emptySet() },
    private val save: (Set<String>) -> Unit = { },
) {
    private val fails = java.util.concurrent.ConcurrentHashMap<String, Int>().also { m ->
        load().forEach { rom -> if (rom.isNotBlank()) m[rom] = MAX }
    }

    fun off(rom: String) = (fails[rom] ?: 0) >= MAX

    /**
     * No frame since the stream was started again ([run] 0) though it ran in this connection ([frameSeen]), and our
     * start and stop came back off a live line ([ignored], see KLineTransport.ignoredRequests: not the ignition off).
     * Returns how many such restarts this ROM has had, 0 when this one is not one. On the [MAX]-th one the ROM is
     * remembered so a cable reopen or an app restart doesn't try the stream again.
     */
    fun silent(rom: String, run: Int, frameSeen: Boolean, ignored: Int): Int {
        if (run > 0 || !frameSeen || ignored < 2) return 0
        val n = (fails[rom] ?: 0) + 1
        fails[rom] = n
        if (n == MAX) save(fails.entries.asSequence().filter { it.value >= MAX }.map { it.key }.toSet())
        return n
    }

    fun tcuGapMs(rom: String, tcuEvery: Int) = EcuService.fastTcuGapMs(tcuEvery, fails[rom] ?: 0)

    companion object {
        const val MAX = 2
    }
}

/** See [EcuService.tcuMiss]. */
internal enum class TcuMiss { NONE, DROP_FWD, BACK_OFF }

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
