package kg.forester.sg.flash

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kg.forester.sg.data.BoxBoard
import kg.forester.sg.service.SensorBoxLink
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

sealed class FlashState {
    object Idle : FlashState()
    data class Working(val progress: Float, val text: String) : FlashState()
    /** [warn]: written and read back, but the board does not answer as the SensorBox afterwards. */
    data class Done(val text: String, val warn: Boolean = false) : FlashState()
    data class Failed(val text: String) : FlashState()
}

/** What the upload needs to know about a board (docs/knowledge/arduino-flashing.md, section 1). */
data class FlashSpec(val asset: String, val signature: String, val pageSize: Int, val maxSketch: Int, val proto: Proto)

/** The USB serial port as a [ByteLink]: reads go through the library's IO manager into a queue (short bulk reads lose data). */
class UsbByteLink(private val port: UsbSerialPort) : ByteLink, Closeable {
    private val q = LinkedBlockingQueue<Byte>()
    @Volatile private var failure: Exception? = null
    private val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) = data.forEach(q::put)
        override fun onRunError(e: Exception) { failure = e }
    })

    fun start() = io.start()
    override fun write(b: ByteArray) { failure?.let { throw it }; port.write(b, 1000) }
    override fun byte(timeoutMs: Long): Int {
        failure?.let { throw it }
        return (q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw FlashTimeout()).toInt() and 0xFF
    }
    override fun drain(quietMs: Long) { while (q.poll(quietMs, TimeUnit.MILLISECONDS) != null) Unit }
    override fun close() { runCatching { io.stop() }; runCatching { port.close() } }
}

/**
 * «Прошить плату» in the app: the SensorBox firmware shipped in the assets goes onto the board through its own
 * bootloader. The box loop lets go of the board for the time, the ECU loop never gets it, fuses and the bootloader
 * stay untouched, so whatever goes wrong a second attempt starts over.
 */
object FirmwareFlasher {
    val state = MutableStateFlow<FlashState>(FlashState.Idle)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val specs = mapOf(
        BoxBoard.NANO to FlashSpec("sensorbox/hex/sensorbox-nano.hex", "1E950F", 128, 30720, Proto.STK500V1),
        BoxBoard.UNO to FlashSpec("sensorbox/hex/sensorbox-uno.hex", "1E950F", 128, 32256, Proto.STK500V1),
        BoxBoard.PROMICRO to FlashSpec("sensorbox/hex/sensorbox-32u4.hex", "1E9587", 128, 28672, Proto.AVR109),
        BoxBoard.MEGA to FlashSpec("sensorbox/hex/sensorbox-mega.hex", "1E9801", 256, 253952, Proto.STK500V2),
    )

    private val chips = mapOf("1E950F" to "ATmega328P (Nano или Uno)", "1E9587" to "ATmega32U4 (Pro Micro)", "1E9801" to "ATmega2560 (Mega)")

    /** The Caterina bootloader as it enumerates: Leonardo, Micro (arduino.cc and .org), SparkFun Pro Micro 5 V and 3.3 V. */
    fun isCaterina(d: UsbDevice) = (d.vendorId == 0x2341 || d.vendorId == 0x2A03) && d.productId in setOf(0x0036, 0x0037) ||
        d.vendorId == 0x1B4F && d.productId in setOf(0x9205, 0x9203)

    /** The ESP32-S3's firmware: one image for address 0 (hardware/sensorbox/build-bin.sh). */
    const val ESP_ASSET = "sensorbox/bin/sensorbox-esp32s3.bin"

    /**
     * Uploads the firmware shipped in the app, or [custom] (a .hex the driver picked, checked by [FirmwareCheck]); on
     * the ESP32-S3 [customBin] (a .bin, checked the same way).
     */
    fun start(ctx: Context, board: BoxBoard, custom: HexImage? = null, customBin: ByteArray? = null) {
        if (state.value is FlashState.Working) return
        state.value = FlashState.Working(0f, "Готовлю прошивку")
        scope.launch { if (board == BoxBoard.ESP32S3) runEsp(ctx.applicationContext, customBin) else run(ctx.applicationContext, board, custom) }
    }

    /** The ESP32-S3: its own USB into the chip's ROM loader, as the colour screen is flashed ([EspFlasher.burn]). */
    private fun runEsp(ctx: Context, custom: ByteArray?) {
        val t0 = System.currentTimeMillis()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sg:flash").apply { acquire(5 * 60_000L) }
        val held = HashSet<String>()
        // the screen loop must not ask the board meanwhile (the box loop is paused, the ECU loop never takes an Espressif board)
        fun hold(n: String) { held += n; kg.forester.sg.service.ScreenLink.pauseForFlash(n, true) }
        SensorBoxLink.pauseForFlash(true)
        var written: String? = null
        try {
            val img = custom ?: ctx.assets.open(ESP_ASSET).use { it.readBytes() }
            EspFlasher.checkImage(img)?.let { throw IllegalStateException(it) }
            Thread.sleep(800) // the box loop closes its session
            val d = pickEsp(usb)
            SsmTrace.log("flash: esp32s3 on %04X:%04X".format(d.device.vendorId, d.device.productId))
            hold(d.device.deviceName)
            // a board without the firmware is being asked by the screen loop whether it is a screen: it lets go in a few seconds
            val until = System.currentTimeMillis() + 8000
            while (kg.forester.sg.service.ScreenLink.busy(d.device.deviceName) && System.currentTimeMillis() < until) Thread.sleep(200)
            val screens = kg.forester.sg.data.UsbScreen.devices.values.toSet() - d.device.deviceName
            EspFlasher.burn(usb, ctx, d, img, ::progress, screens, ::hold)
            val s = (System.currentTimeMillis() - t0) / 1000
            if (kg.forester.sg.data.SensorBox.info.value?.via?.startsWith("USB") == true) kg.forester.sg.data.SensorBox.info.value = null
            written = "Записано и проверено (MD5): ${img.size / 1024} КБ за $s с."
            progress(0.99f, "Жду, что ответит плата с новой прошивкой")
        } catch (e: Exception) {
            SsmTrace.log("flash failed: $e")
            state.value = FlashState.Failed(kg.forester.sg.data.Failure.link(e, "Прошить не вышло") +
                ". Если плата не отвечает: зажмите на ней BOOT, нажмите и отпустите RST (или вставьте USB с зажатой BOOT), отпустите BOOT и прошейте ещё раз")
        } finally {
            held.forEach { kg.forester.sg.service.ScreenLink.pauseForFlash(it, false) }
            SensorBoxLink.pauseForFlash(false)
            runCatching { wake.release() }
        }
        written?.let { state.value = answer(it) }
    }

    /** The ESP32-S3: the box's own device, else the only Espressif board that is not a colour screen on line. */
    private fun pickEsp(usb: UsbManager): UsbSerialDriver {
        val all = SensorBoxLink.drivers(usb).filter { kg.forester.sg.service.ScreenLink.isScreen(it.device.vendorId) }
        SensorBoxLink.lastBoxDevice?.let { n -> all.firstOrNull { it.device.deviceName == n }?.let { return it } }
        val screens = kg.forester.sg.data.UsbScreen.devices.values.toSet() + kg.forester.sg.service.ScreenLink.knownScreens
        val free = all.filter { it.device.deviceName !in screens }
        return free.singleOrNull() ?: throw IllegalStateException(when {
            all.isEmpty() -> "Плата ESP32-S3 не найдена на USB. Подключите её к магнитоле разъёмом USB (не UART/COM, если на плате их два)"
            free.isEmpty() -> "На USB только цветные экраны: плату датчиков не видно. Подключите её к магнитоле"
            else -> "На USB несколько плат ESP32-S3: подключите для прошивки одну"
        })
    }

    fun reset() { if (state.value !is FlashState.Working) state.value = FlashState.Idle }

    private fun progress(p: Float, text: String) { state.value = FlashState.Working(p, text) }

    private fun run(ctx: Context, board: BoxBoard, custom: HexImage?) {
        val t0 = System.currentTimeMillis()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sg:flash").apply { acquire(5 * 60_000L) }
        SensorBoxLink.pauseForFlash(true)
        var written: String? = null
        try {
            val spec = specs.getValue(board)
            val img = custom ?: IntelHex.parse(ctx.assets.open(spec.asset).bufferedReader().readText(), spec.maxSketch)
            Thread.sleep(800) // the box loop closes its session
            val d = pickDevice(usb)
            SensorBoxLink.flashDevice = d.device.deviceName
            SsmTrace.log("flash: %s on %04X:%04X".format(board.id, d.device.vendorId, d.device.productId))
            if (spec.proto == Proto.AVR109) caterina(usb, ctx, d, spec, img) else bridge(usb, ctx, d, spec, img)
            val s = (System.currentTimeMillis() - t0) / 1000
            // what the board said before belongs to the old firmware
            if (kg.forester.sg.data.SensorBox.info.value?.via?.startsWith("USB") == true) kg.forester.sg.data.SensorBox.info.value = null
            written = "Записано и проверено чтением: ${img.end} байт за $s с."
            progress(0.99f, "Жду, что ответит плата с новой прошивкой")
        } catch (e: Exception) {
            SsmTrace.log("flash failed: $e")
            state.value = FlashState.Failed(kg.forester.sg.data.Failure.link(e, "Прошить не вышло"))
        } finally {
            SensorBoxLink.flashDevice = null
            SensorBoxLink.pauseForFlash(false)
            runCatching { wake.release() }
        }
        written?.let { state.value = answer(it) }
    }

    /** After the upload: does the board now answer as the SensorBox, and with what. */
    private fun answer(written: String): FlashState {
        if (!kg.forester.sg.data.SensorBox.enabled.value) return FlashState.Done(
            "$written Включите «искать на USB» вверху слева: приложение спросит плату, ответит ли она как SensorBox")
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            kg.forester.sg.data.SensorBox.info.value?.takeIf { it.via.startsWith("USB") }?.let { i ->
                val disp = when (i.disp) { "oled" -> ", нашла OLED"; "lcd" -> ", нашла LCD"; else -> "" }
                return FlashState.Done("$written Плата ответила: SensorBox, ${BoxBoard.of(i.board).short}, прошивка ${i.fw}$disp, входы ${i.inputs.joinToString(", ")}")
            }
            Thread.sleep(250)
        }
        return FlashState.Done("$written Но плата не отвечает как SensorBox. Если это своя прошивка не SensorBox, так и должно быть; " +
            "если SensorBox, проверьте скорость Serial 115200 и ответ на «?»", warn = true)
    }

    /** The board, never the ECU cable: the box's own device, else an Arduino by its ids, else the only one there is. */
    private fun pickDevice(usb: UsbManager): UsbSerialDriver {
        val all = SensorBoxLink.drivers(usb)
        val cands = all.filter { d ->
            !kg.forester.sg.service.UsbDeviceRegistry.isWideband(d.device) &&
            d.device.deviceName != SensorBoxLink.ecuDevice && !kg.forester.sg.service.ScreenLink.isScreen(d.device.vendorId) &&
                !kg.forester.sg.ssm.OpenPort2.matches(d.device.vendorId, d.device.productId) &&
                !kg.forester.sg.ssm.OpenPort13.matches(d.device.vendorId, d.device.productId)
        }
        return SensorBoxLink.lastBoxDevice?.let { n -> cands.firstOrNull { it.device.deviceName == n } }
            ?: cands.firstOrNull { SensorBoxLink.isArduino(it.device.vendorId) || isCaterina(it.device) }
            ?: cands.singleOrNull()
            ?: throw IllegalStateException(
                if (cands.isEmpty()) "Плата не найдена на USB. Подключите её к магнитоле"
                else "На USB несколько похожих устройств, и непонятно, какое из них плата. Откройте «Датчики и экраны», чтобы приложение " +
                    "узнало плату, или на время прошивки отключите кабель ЭБУ",
            )
    }

    /** Asks Android for the device and waits for the answer (the dialog is the user's). */
    private fun permission(usb: UsbManager, ctx: Context, d: UsbDevice, waitMs: Long) {
        if (usb.hasPermission(d)) return
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, android.app.PendingIntent.getBroadcast(ctx, 7, android.content.Intent("kg.forester.sg.FLASH_USB").setPackage(ctx.packageName), flags))
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            if (usb.hasPermission(d)) return
            Thread.sleep(200)
        }
        throw IllegalStateException("Нет доступа к USB-устройству. Нажмите «Прошить» ещё раз и в окне Android поставьте галочку «по умолчанию» и ОК")
    }

    private fun open(usb: UsbManager, d: UsbSerialDriver, baud: Int): Pair<UsbSerialPort, UsbByteLink> {
        var conn = usb.openDevice(d.device)
        // a device that has just appeared can refuse for a moment
        repeat(5) { if (conn == null) { Thread.sleep(300); conn = usb.openDevice(d.device) } }
        val port = d.ports[0]
        port.open(conn ?: throw IllegalStateException("Не удалось открыть USB-устройство"))
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        (port as? FtdiSerialDriver.FtdiSerialPort)?.let { runCatching { it.latencyTimer = 1 } }
        return port to UsbByteLink(port).also { it.start() }
    }

    /** DTR (and RTS) edge into the board's RESET through its 100 nF capacitor, as avrdude does. */
    private fun pulse(port: UsbSerialPort, settleMs: Long) {
        runCatching { port.dtr = false; port.rts = false }
        Thread.sleep(settleMs)
        runCatching { port.dtr = true; port.rts = true }
        runCatching { port.dtr = false; port.rts = false }
        Thread.sleep(100)
    }

    private fun checkSignature(p: Programmer, spec: FlashSpec) {
        val sig = p.signature().joinToString("") { "%02X".format(it) }
        if (sig != spec.signature) throw IllegalStateException(
            "Это не та плата: на ней ${chips[sig] ?: "чип с подписью $sig"}. Выберите правильную плату вверху и прошейте ещё раз",
        )
    }

    /** Nano, Uno, Mega: a USB-serial bridge; the bootloader is found by trying protocol and speed (old Nanos: 57600). */
    private fun bridge(usb: UsbManager, ctx: Context, d: UsbSerialDriver, spec: FlashSpec, img: HexImage) {
        permission(usb, ctx, d.device, 20_000)
        val tries = if (spec.proto == Proto.STK500V2) listOf(Proto.STK500V2 to 115200, Proto.STK500V1 to 115200, Proto.STK500V1 to 57600)
        else listOf(Proto.STK500V1 to 115200, Proto.STK500V1 to 57600, Proto.STK500V2 to 115200)
        for ((proto, baud) in tries) {
            progress(0.01f, "Ищу загрузчик платы ($baud бод)")
            val (port, link) = open(usb, d, baud)
            try {
                val p: Programmer = if (proto == Proto.STK500V1) {
                    pulse(port, 250)
                    Stk500v1(link) { pulse(port, 20) }.takeIf { it.sync() } ?: continue
                } else {
                    pulse(port, 50)
                    Stk500v2(link).takeIf { it.sync() } ?: continue
                }
                progress(0.04f, "Загрузчик ответил: ${p.connect()}")
                checkSignature(p, spec)
                Upload.run(p, img, if (proto == Proto.STK500V2) 256 else 128, proto, ::progress)
                return
            } finally {
                link.close()
            }
        }
        throw IllegalStateException(
            "Плата не отвечает загрузчику. Проверьте, что выбрана правильная плата. Если не помогает, нажмите кнопку RESET на плате сразу после «Прошить»",
        )
    }

    /** Pro Micro / Leonardo: the 1200-baud touch restarts it into Caterina, which shows up as a new USB device for 8 s. */
    private fun caterina(usb: UsbManager, ctx: Context, d: UsbSerialDriver, spec: FlashSpec, img: HexImage) {
        val boot = if (isCaterina(d.device)) d else {
            permission(usb, ctx, d.device, 20_000)
            progress(0.01f, "Перезагружаю плату в режим прошивки")
            val before = usb.deviceList.keys.toSet()
            usb.openDevice(d.device)?.let { conn ->
                val port = d.ports[0]
                runCatching {
                    port.open(conn)
                    port.setParameters(1200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    port.dtr = false
                }
                runCatching { port.close() }
            }
            Thread.sleep(500)
            progress(0.02f, "Жду плату в режиме прошивки. Android спросит доступ: галочка «по умолчанию» и ОК")
            val end = System.currentTimeMillis() + 10_000
            var found: UsbSerialDriver? = null
            while (found == null && System.currentTimeMillis() < end) {
                found = SensorBoxLink.drivers(usb).firstOrNull { isCaterina(it.device) && it.device.deviceName !in before }
                if (found == null) Thread.sleep(250)
            }
            found ?: throw IllegalStateException(
                "Плата не перешла в режим прошивки. Нажмите «Прошить» и сразу дважды быстро нажмите RESET на плате (или замкните RST на GND)",
            )
        }
        SensorBoxLink.flashDevice = boot.device.deviceName
        // the 8 s of the bootloader run while the dialog is up
        permission(usb, ctx, boot.device, 7_000)
        val (_, link) = open(usb, boot, 57600)
        try {
            val p = Avr109(link)
            progress(0.04f, "Загрузчик ответил: ${p.connect()}")
            checkSignature(p, spec)
            Upload.run(p, img, 128, Proto.AVR109, ::progress)
        } finally {
            link.close()
        }
    }
}
