package kg.forester.sg.service

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Wideband
import kg.forester.sg.data.WidebandCandidate
import kg.forester.sg.data.WidebandConfig
import kg.forester.sg.data.WidebandLinkState
import kg.forester.sg.data.WidebandPick
import kg.forester.sg.data.WidebandSample
import kg.forester.sg.data.WidebandState
import kg.forester.sg.devices.innovate.InnovateDecoder
import kg.forester.sg.devices.innovate.InnovateProbe
import kg.forester.sg.devices.innovate.InnovateReading
import kg.forester.sg.devices.innovate.InnovateStatus
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The USB wideband (Innovate LC-2, LC-1, MTX-L on a USB-serial adapter), apart from the ECU loop: finds which device
 * is its adapter (see [WidebandPick]), keeps that one from the ECU loop and the SensorBox, and reads it.
 * Switched off, it does nothing and holds nothing.
 */
object InnovateLink {
    /** What listening to a device showed, by device name: Innovate packets heard or not, when, and taken from the ECU loop for it. */
    private class Heard(val yes: Boolean, val atMs: Long, val fromEcu: Boolean)
    private val heard = HashMap<String, Heard>()
    /** Devices the USB permission was asked for since they were plugged in: asked once, not every round. */
    private val asked = HashSet<String>()

    const val AMBIGUOUS = "ШДК: не понял, какой из двух одинаковых адаптеров ШДК, а какой кабель ЭБУ, и кабель ЭБУ не трогаю. " +
        "Включите зажигание, чтобы контроллер слал данные, или подключите ШДК через адаптер с другим чипом"

    fun start(scope: CoroutineScope, usb: UsbManager, askPermission: (UsbDevice) -> Unit) = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val cfg = Wideband.config.value
                if (Wideband.preview) {
                    val status = InnovateStatus.entries[((SystemClock.elapsedRealtime() / 3000) % 8).toInt()]
                    publish("demo", InnovateReading(status, 500, 147), true)
                    delay(100)
                    continue
                }
                if (!cfg.enabled) { drop(); state(WidebandLinkState.DISABLED, "Выключено"); delay(1000); continue }
                if (!cfg.chosen) { drop(); state(WidebandLinkState.WAITING, "Выберите USB-адаптер ШДК ниже"); delay(1000); continue }
                if (Repo.demoOn.value || Repo.linkDemo.value) {
                    drop()
                    state(WidebandLinkState.WAITING, "В деморежиме USB ШДК не открывается. Можно включить тест статусов.")
                    delay(1000); continue
                }
                val all = runCatching { UsbDeviceRegistry.drivers(usb) }.getOrDefault(emptyList())
                    .filter { it.device.vendorId == cfg.vendor && it.device.productId == cfg.product }
                when (val pick = WidebandPick.of(cfg, candidates(usb, all))) {
                    is WidebandPick.Found -> {
                        val d = all.first { it.device.deviceName == pick.name }
                        found(cfg, d)
                        val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.INNOVATE)
                        if (lease == null) { state(WidebandLinkState.BUSY, "Освобождаю USB-адаптер ШДК"); delay(300); continue }
                        try {
                            session(usb, d, Wideband.config.value, lease)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            SsmTrace.log("Innovate: $e")
                            state(WidebandLinkState.ERROR, kg.forester.sg.data.Failure.link(e, "Ошибка USB ШДК"))
                        } finally { lease.close() }
                        delay(500)
                    }
                    is WidebandPick.Probe -> {
                        val d = all.first { it.device.deviceName == pick.name }
                        val fromEcu = UsbDeviceRegistry.ownership.holder(pick.name) == UsbDeviceRegistry.ECU
                        val yes = runCatching { listen(usb, d, cfg.port) }.onFailure { SsmTrace.log("Innovate probe: $it") }.getOrNull()
                        if (yes != null) {
                            heard[pick.name] = Heard(yes, SystemClock.elapsedRealtime(), fromEcu)
                            SsmTrace.log("Innovate probe ${pick.name}: ${if (yes) "MTS packets" else "nothing"}")
                        } else delay(500)
                    }
                    is WidebandPick.Permission -> {
                        drop()
                        pick.names.firstOrNull { it !in asked }?.let { n ->
                            asked += n
                            all.firstOrNull { it.device.deviceName == n }?.let { askPermission(it.device) }
                        }
                        state(WidebandLinkState.PERMISSION, "Разрешите доступ к USB-адаптеру ШДК в окне Android")
                        delay(1000)
                    }
                    WidebandPick.Missing -> {
                        drop(); state(WidebandLinkState.WAITING, "Подключите выбранный USB-адаптер ШДК"); delay(1000)
                    }
                    WidebandPick.Silent -> {
                        drop()
                        state(WidebandLinkState.WAITING, "Адаптер на месте, но данных Innovate нет: контроллер включён (зажигание)? Проверяю снова")
                        delay(1000)
                    }
                    WidebandPick.Busy -> {
                        state(WidebandLinkState.BUSY, "USB-адаптер ШДК сейчас открыт другой частью приложения: жду"); delay(1000)
                    }
                    WidebandPick.Ambiguous -> {
                        drop()
                        state(WidebandLinkState.AMBIGUOUS, AMBIGUOUS)
                        Alerts.raise("wideband", AMBIGUOUS, Alerts.Level.WARN, 30 * 60_000L, speak = false)
                        delay(2000)
                    }
                }
            }
        } finally {
            drop()
            state(WidebandLinkState.DISABLED, "Сервис остановлен")
        }
    }

    /** The devices with the chosen ids as [WidebandPick] needs them; forgets what it knew of unplugged ones. */
    private fun candidates(usb: UsbManager, all: List<UsbSerialDriver>): List<WidebandCandidate> {
        val names = all.map { it.device.deviceName }.toSet()
        heard.keys.retainAll(names)
        asked.retainAll(names)
        Wideband.device?.let { if (it !in names) drop() }
        val now = SystemClock.elapsedRealtime()
        // silence is asked again: the controller may have been off; a cable taken from the ECU loop only now and then
        heard.entries.removeAll { (_, h) -> !h.yes && now - h.atMs > if (h.fromEcu) 60_000 else 10_000 }
        val link = Repo.link.value
        return all.map { d ->
            val n = d.device.deviceName
            val permitted = usb.hasPermission(d.device)
            val holder = UsbDeviceRegistry.ownership.holder(n)
            // the ECU answers through it: the ECU cable, for sure
            val ecuCable = holder == UsbDeviceRegistry.ECU && link == LinkState.CONNECTED
            WidebandCandidate(
                name = n,
                serial = UsbDeviceRegistry.serial(d.device)?.takeIf { permitted || it.isNotEmpty() },
                permitted = permitted,
                probe = heard[n]?.yes ?: if (ecuCable) false else null,
                free = holder == null || holder == UsbDeviceRegistry.INNOVATE ||
                    (holder == UsbDeviceRegistry.ECU && link != LinkState.CONNECTED && link != LinkState.CONNECTING),
            )
        }
    }

    /** [d] is the adapter: kept from the others, remembered (its serial number, where it is plugged in). */
    private fun found(cfg: WidebandConfig, d: UsbSerialDriver) {
        val n = d.device.deviceName
        val serial = UsbDeviceRegistry.serial(d.device).orEmpty()
        if (cfg.path != n || (cfg.serial.isEmpty() && serial.isNotEmpty())) {
            Wideband.save(cfg.copy(path = n, serial = cfg.serial.ifEmpty { serial }))
        }
        if (Wideband.device != n) {
            drop()
            Wideband.device = n
            SsmTrace.log("Innovate: adapter %04X:%04X %s".format(d.device.vendorId, d.device.productId, n))
            Alerts.clear("wideband")
        }
        // whoever has it open (the ECU loop trying it as a cable) lets go now
        UsbDeviceRegistry.ownership.reserve(n, UsbDeviceRegistry.INNOVATE)
    }

    /** The adapter is no longer the wideband's: everybody may use it again. */
    private fun drop() {
        Wideband.device?.let { UsbDeviceRegistry.ownership.unreserve(it, UsbDeviceRegistry.INNOVATE) }
        Wideband.device = null
    }

    /**
     * Listens to [d] for [InnovateProbe.LISTEN_MS] at 19200 8N1, sending nothing; true when Innovate packets came.
     * Null when it couldn't be opened (somebody else still has it). Not it: let go of at once, the ECU cable works on.
     */
    private suspend fun listen(usb: UsbManager, d: UsbSerialDriver, portIndex: Int): Boolean? {
        val n = d.device.deviceName
        val own = UsbDeviceRegistry.ownership
        // the ECU has just answered through it: that is the ECU cable, not to be touched
        if (own.holder(n) == UsbDeviceRegistry.ECU && Repo.link.value == LinkState.CONNECTED) return null
        own.reserve(n, UsbDeviceRegistry.INNOVATE)
        var yes = false
        try {
            var lease: UsbOwnership.Lease? = null
            for (i in 0 until 25) {
                lease = own.claim(n, UsbDeviceRegistry.INNOVATE) ?: run { delay(100); null }
                if (lease != null) break
            }
            if (lease == null) return null
            lease.use {
                state(WidebandLinkState.CONNECTING, "Ищу ШДК: слушаю адаптер $n")
                val conn = usb.openDevice(d.device) ?: return null
                it.interrupt = { runCatching { conn.close() }; Unit }
                val port = d.ports[portIndex.coerceIn(d.ports.indices)]
                try {
                    port.open(conn)
                    port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    val buf = ByteArray(1024)
                    val chunk = ByteArray(256)
                    var size = 0
                    val until = SystemClock.elapsedRealtime() + InnovateProbe.LISTEN_MS
                    while (SystemClock.elapsedRealtime() < until && it.permitted) {
                        val k = port.read(chunk, 200)
                        if (k > 0) {
                            // keep the tail: a packet may sit across the cut
                            if (size + k > buf.size) { buf.copyInto(buf, 0, size - 64, size); size = 64 }
                            chunk.copyInto(buf, size, 0, minOf(k, buf.size - size))
                            size += minOf(k, buf.size - size)
                            if (InnovateProbe.sees(buf, size)) { yes = true; break }
                        }
                        yield()
                    }
                } finally {
                    runCatching { port.close() }
                    runCatching { conn.close() }
                }
            }
            if (yes) Wideband.device = n
            return yes
        } finally {
            if (!yes) own.unreserve(n, UsbDeviceRegistry.INNOVATE)
        }
    }

    private suspend fun session(usb: UsbManager, d: UsbSerialDriver, cfg: WidebandConfig, lease: UsbOwnership.Lease) {
        val name = d.device.deviceName
        state(WidebandLinkState.CONNECTING, "Подключение LC/MTXL · 19200 8N1")
        val conn = usb.openDevice(d.device) ?: error("Не удалось открыть USB-адаптер ШДК")
        lease.interrupt = { runCatching { conn.close() }; Unit }
        val port = d.ports[cfg.port.coerceIn(d.ports.indices)]
        try {
            if (!lease.permitted) return
            port.open(conn)
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val decoder = InnovateDecoder()
            val buffer = ByteArray(512)
            var lastFrame = SystemClock.elapsedRealtime()
            state(WidebandLinkState.CONNECTING, "Ожидание пакетов Innovate")
            while (currentCoroutineContext().isActive && lease.permitted && Wideband.config.value == cfg &&
                Wideband.device == name && !Wideband.preview && !Repo.demoOn.value && !Repo.linkDemo.value) {
                val count = port.read(buffer, 200)
                if (Wideband.config.value != cfg || Wideband.device != name || Wideband.preview) break
                for (reading in decoder.feed(buffer, count.coerceAtLeast(0))) {
                    lastFrame = SystemClock.elapsedRealtime()
                    publish(name, reading)
                }
                val age = SystemClock.elapsedRealtime() - lastFrame
                if (age > WidebandState.STALE_MS) {
                    Wideband.state.value = Wideband.state.value.copy(link = WidebandLinkState.STALE, message = "Нет свежих пакетов Innovate")
                }
                if (age > 5000) break
                // a cancellation point even on a USB stream that never blocks
                yield()
            }
        } finally {
            runCatching { port.close() }
            runCatching { conn.close() }
            state(WidebandLinkState.WAITING, "Соединение ШДК закрыто")
        }
    }

    private fun state(link: WidebandLinkState, message: String) { Wideband.state.value = WidebandState(link, message) }

    private fun publish(device: String, reading: InnovateReading, demo: Boolean = false) {
        Wideband.state.value = WidebandState(if (demo) WidebandLinkState.DEMO else WidebandLinkState.RECEIVING,
            (if (demo) "Тест · " else "") + reading.text(),
            WidebandSample(device, System.currentTimeMillis(), SystemClock.elapsedRealtime(), reading))
    }
}
