package kg.forester.sg.flash

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.UsbScreen
import kg.forester.sg.service.ScreenLink
import kg.forester.sg.service.SensorBoxLink
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * «Прошить экран»: the colour-screen firmware for the chosen board (or a .bin the driver picked) onto an ESP32-S3
 * through its own USB, as esptool does it: the USB serial's DTR/RTS put the chip into its ROM loader, which cannot
 * be broken by a bad upload, so a second attempt always starts over.
 */
object EspFlasher {
    val state = MutableStateFlow<FlashState>(FlashState.Idle)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Flashes the screen [screenId] (its USB device), or for "" a board that is not one of the screens on line yet. */
    fun start(ctx: Context, model: ScreenModel, screenId: String, custom: ByteArray? = null) {
        if (state.value is FlashState.Working) return
        state.value = FlashState.Working(0f, "Готовлю прошивку")
        scope.launch { run(ctx.applicationContext, model, screenId, custom) }
    }

    fun reset() { if (state.value !is FlashState.Working) state.value = FlashState.Idle }

    private fun progress(p: Float, t: String) { state.value = FlashState.Working(p, t) }

    /**
     * A .bin for address 0 checked before it goes on: an ESP image (0xE9) with a partition table at 0x8000, else the
     * chip would not start from it. Returns why not, or null.
     */
    fun checkImage(b: ByteArray): String? = when {
        b.size < 0x10000 -> "Файл слишком мал для прошивки ESP32"
        b.size > 4 * 1024 * 1024 -> "Файл больше 4 МБ: это не прошивка для этих плат"
        (b[0].toInt() and 0xFF) != 0xE9 -> "Это не образ ESP32 (нет метки 0xE9 в начале)"
        (b[0x8000].toInt() and 0xFF) != 0xAA || (b[0x8001].toInt() and 0xFF) != 0x50 ->
            "В файле нет таблицы разделов: нужен склеенный образ (merged.bin) для адреса 0, а не один .bin программы"
        else -> null
    }

    private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun hold(device: String) { held += device; ScreenLink.pauseForFlash(device, true) }

    private fun run(ctx: Context, model: ScreenModel, screenId: String, custom: ByteArray?) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sg:espflash").apply { acquire(5 * 60_000L) }
        var done: String? = null
        val before = UsbScreen.online.value.keys
        try {
            val img = custom ?: ctx.assets.open(model.asset).use { it.readBytes() }
            checkImage(img)?.let { throw IllegalStateException(it) }
            progress(0.01f, "Ищу экран на USB")
            val d = target(usb, screenId)
            hold(d.device.deviceName)
            Thread.sleep(600) // its session lets go of the port
            burn(usb, ctx, d, img, ::progress, UsbScreen.devices.values.toSet() - d.device.deviceName, ::hold)
            done = "Записано и проверено (MD5): ${img.size / 1024} КБ."
        } catch (e: Exception) {
            SsmTrace.log("esp flash failed: $e")
            state.value = FlashState.Failed(kg.forester.sg.data.Failure.link(e, "Прошить не вышло") +
                ". Если плата не отвечает: зажмите на ней BOOT, нажмите и отпустите RST (или вставьте USB с зажатой BOOT), отпустите BOOT и прошейте ещё раз")
        } finally {
            held.forEach { ScreenLink.pauseForFlash(it, false) }
            held.clear()
            runCatching { wake.release() }
        }
        done?.let { state.value = answer(it, screenId, before) }
    }

    /**
     * [img] onto the ESP32-S3 on [d] through its ROM loader, checked by MD5, then restarted into it. [say] reports the
     * progress, [others] are the Espressif devices that are someone else (a board that comes back under a new name is
     * none of them), [hold] keeps a device for the upload. Also the SensorBox's on an ESP32-S3 ([FirmwareFlasher]).
     */
    fun burn(usb: UsbManager, ctx: Context, d: UsbSerialDriver, img: ByteArray, say: (Float, String) -> Unit,
             others: Set<String>, hold: (String) -> Unit) {
        val rom = loader(usb, ctx, d, say, others, hold)
        say(0.06f, "Загрузчик ответил, проверяю чип")
        if (!rom.first.isS3()) throw IllegalStateException("Это не ESP32-S3: эта прошивка только для плат на ESP32-S3")
        rom.first.attach()
        try {
            say(0.08f, "Стираю место под прошивку")
            rom.first.write(0, img) { f -> say(0.1f + 0.8f * f, "Записываю ${(f * 100).toInt()} %") }
            say(0.92f, "Проверяю записанное")
            val want = EspRom.md5Hex(img)
            val got = rom.first.md5(0, img.size)
            if (got != want) throw FlashProtocol("проверка не сошлась: на плате MD5 $got, а должно быть $want")
            rom.first.finish()
            hardReset(rom.second)
        } finally {
            rom.third.close()
        }
    }

    /** The USB device of [screenId], or the screen-type device none of the screens on line holds (a new board). */
    private fun target(usb: UsbManager, screenId: String): UsbSerialDriver {
        val all = SensorBoxLink.drivers(usb).filter { ScreenLink.isScreen(it.device.vendorId) }
        UsbScreen.devices[screenId]?.let { n -> all.firstOrNull { it.device.deviceName == n }?.let { return it } }
        val free = all.filter { it.device.deviceName !in UsbScreen.devices.values }
        return free.singleOrNull() ?: all.singleOrNull() ?: throw IllegalStateException(when {
            all.isEmpty() -> "Экран не найден на USB. Подключите плату ESP32-S3 к магнитоле"
            free.isEmpty() -> "Этот экран сейчас не на связи, а остальные уже прошиты: выберите в списке тот, что надо прошить"
            else -> "На USB несколько новых плат: подключите для прошивки одну"
        })
    }

    /** After the upload: does the screen answer (by its id, or a new one), and as which board. */
    private fun answer(written: String, screenId: String, before: Set<String>): FlashState {
        Thread.sleep(1500) // its old session notices the reset
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            UsbScreen.online.value.values.firstOrNull { it.id == screenId || it.id !in before }?.let { i ->
                return FlashState.Done("$written Экран ответил: ${ScreenModel.of(i.board)?.title ?: i.board}, ${i.w}×${i.h}, прошивка ${i.fw}")
            }
            Thread.sleep(250)
        }
        return FlashState.Done("$written Но экран не ответил. Выдерните и вставьте его USB; если это своя прошивка не для экрана, так и должно быть", warn = true)
    }

    private fun permission(usb: UsbManager, ctx: Context, d: android.hardware.usb.UsbDevice) {
        if (usb.hasPermission(d)) return
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, android.app.PendingIntent.getBroadcast(ctx, 8, android.content.Intent("kg.forester.sg.FLASH_USB").setPackage(ctx.packageName), flags))
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) { if (usb.hasPermission(d)) return; Thread.sleep(200) }
        throw IllegalStateException("Нет доступа к USB-устройству. Нажмите «Прошить» ещё раз и в окне Android поставьте галочку «по умолчанию» и ОК")
    }

    private fun open(usb: UsbManager, d: UsbSerialDriver): Pair<UsbSerialPort, UsbByteLink> {
        var conn = usb.openDevice(d.device)
        repeat(5) { if (conn == null) { Thread.sleep(300); conn = usb.openDevice(d.device) } }
        val port = d.ports[0]
        port.open(conn ?: throw IllegalStateException("Не удалось открыть USB-устройство"))
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        return port to UsbByteLink(port).also { it.start() }
    }

    /** The ESP32-S3's USB serial into its ROM loader (esptool's USB-JTAG-serial reset), reopened if it came back as a new device. */
    private fun loader(usb: UsbManager, ctx: Context, d: UsbSerialDriver, say: (Float, String) -> Unit, others: Set<String>,
                       hold: (String) -> Unit): Triple<EspRom, UsbSerialPort, UsbByteLink> {
        permission(usb, ctx, d.device)
        var (port, link) = open(usb, d)
        say(0.03f, "Перезагружаю плату в режим прошивки")
        runCatching {
            port.rts = false; port.dtr = false; Thread.sleep(100)
            port.dtr = true; port.rts = false; Thread.sleep(100)
            port.rts = true; port.dtr = false; port.rts = true; Thread.sleep(100)
            port.dtr = false; port.rts = false
        }
        Thread.sleep(300)
        link.drain(100)
        var rom = EspRom(link)
        if (rom.sync()) return Triple(rom, port, link)
        // a firmware with the USB of its own (TinyUSB) restarts into the loader as another device
        link.close()
        val end = System.currentTimeMillis() + 8000
        var again: UsbSerialDriver? = null
        while (again == null && System.currentTimeMillis() < end) {
            Thread.sleep(400)
            // the same board under a new name: an Espressif device that is none of the other screens
            again = SensorBoxLink.drivers(usb).firstOrNull { ScreenLink.isScreen(it.device.vendorId) && it.device.deviceName !in others }
        }
        again ?: throw IllegalStateException("Плата не перешла в режим прошивки")
        hold(again.device.deviceName)
        permission(usb, ctx, again.device)
        open(usb, again).let { (p, l) -> port = p; link = l }
        rom = EspRom(link)
        if (!rom.sync(12)) { link.close(); throw IllegalStateException("Загрузчик платы не отвечает") }
        return Triple(rom, port, link)
    }

    /** RTS pulses the chip's reset: it starts the program just written. */
    private fun hardReset(port: UsbSerialPort) = runCatching {
        port.dtr = false; port.rts = true; Thread.sleep(150); port.rts = false
    }
}
