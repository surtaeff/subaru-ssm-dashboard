package kg.forester.sg.service

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.ScreenInfo
import kg.forester.sg.data.UsbScreen
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ui.ScreenRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The colour screen on its own USB (an ESP32-S3, Espressif's vendor id): asks who it is, then draws the chosen page
 * as often as it keeps up and sends only what changed (hardware/usbscreen/usbscreen.ino). Its BOOT button turns pages.
 */
object ScreenLink {
    /** Devices being flashed: their sessions stop, nobody opens them. */
    private val flashing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Devices with a session running. */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Espressif boards that answered as a screen, while they are plugged in: the box loop leaves them alone. */
    val knownScreens: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Espressif boards that answered as the SensorBox: not asked again while they are plugged in. */
    private val boxes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Espressif's vendor id: the colour screen, or the SensorBox on an ESP32-S3 (told apart by the answer to «?»). */
    fun isScreen(vid: Int) = vid == 0x303A

    /** A session (or a board being asked who it is) has [device] open. */
    fun busy(device: String) = device in active

    fun pauseForFlash(device: String, on: Boolean) {
        if (on) flashing += device else flashing -= device
        UsbDeviceRegistry.flash(device, on)
    }

    /** Every screen on USB gets its own session: two or three can be on at once. */
    fun start(scope: CoroutineScope, ctx: Context, usb: UsbManager, askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val all = runCatching { SensorBoxLink.drivers(usb).filter { isScreen(it.device.vendorId) } }.getOrDefault(emptyList())
                val present = all.map { it.device.deviceName }.toSet()
                boxes.retainAll(present)
                knownScreens.retainAll(present)
                val found = all.filter { it.device.deviceName !in boxes }
                UsbScreen.status.value = if (found.isEmpty()) "Экран не найден на USB" else ""
                for (d in found) {
                    val n = d.device.deviceName
                    if (n in active || n in flashing || !UsbDeviceRegistry.available(d.device, UsbDeviceRegistry.SCREEN)) continue
                    if (!usb.hasPermission(d.device)) {
                        UsbScreen.status.value = "Разрешите доступ к экрану в окне Android"
                        askPermission(d.device)
                        continue
                    }
                    val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.SCREEN) ?: continue
                    active += n
                    scope.launch(Dispatchers.IO) {
                        try { runCatching { session(ctx, usb, d, lease) }.onFailure { SsmTrace.log("screen $n: $it") } }
                        finally { lease.close(); active -= n }
                    }.invokeOnCompletion { lease.close(); active -= n }
                }
                delay(2000)
            }
        }
    }

    /** Lines from the board, split as they come; K/E acknowledge a picture, the rest are its own news. */
    private class Lines(val port: UsbSerialPort) {
        private val buf = ByteArray(512)
        private val line = StringBuilder()
        private val ready = ArrayDeque<String>()

        fun next(timeoutMs: Int): String? {
            val end = System.currentTimeMillis() + timeoutMs
            while (ready.isEmpty()) {
                val left = (end - System.currentTimeMillis()).toInt()
                if (left <= 0) return null
                val n = port.read(buf, left.coerceIn(10, 200))
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n' || c == '\r') { if (line.isNotEmpty()) { ready.addLast(line.toString()); line.clear() } }
                    else if (line.length < 200) line.append(c)
                }
            }
            return ready.removeFirst()
        }
    }

    private suspend fun session(ctx: Context, usb: UsbManager, d: UsbSerialDriver, lease: UsbOwnership.Lease) {
        val dev = d.device.deviceName
        val conn = usb.openDevice(d.device) ?: return
        lease.interrupt = { runCatching { conn.close() }; Unit }
        val port = d.ports[0]
        try {
            if (!lease.permitted) return
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR and RTS stay low: on this USB they are the reset and boot lines
            val lines = Lines(port)
            var info: ScreenInfo? = null
            var box = false
            repeat(4) {
                if (info != null || box) return@repeat
                port.write("?\n".toByteArray(), 300)
                val end = System.currentTimeMillis() + 800
                while (info == null && !box && System.currentTimeMillis() < end) lines.next(200)?.let { l ->
                    info = ScreenInfo.parse(l)
                    box = l.startsWith("I SENSORBOX")
                }
            }
            // the SensorBox on an ESP32-S3: the box loop takes it from here
            if (box) { boxes += dev; SsmTrace.log("screen: $dev is the SensorBox"); return }
            val inf = info ?: run { UsbScreen.status.value = "Плата ESP32 на USB не отвечает как экран: прошейте её"; Thread.sleep(3000); return }
            knownScreens += dev
            SsmTrace.log("screen: ${inf.board} ${inf.w}x${inf.h} fw ${inf.fw} id ${inf.id}")
            UsbScreen.attach(inf, dev)
            val id = inf.id.ifEmpty { "dev:$dev" }
            try {
                if (inf.isBig) bigLoop(ctx, port, lines, inf, id, dev, lease) else loop(ctx, port, lines, inf, id, dev, lease)
            } finally { UsbScreen.detach(id) }
        } finally {
            runCatching { port.close() }
            runCatching { conn.close() }
        }
    }

    /** Sends one rectangle and waits for its answer; the button and redraw requests may come in between. */
    private fun sendRect(port: UsbSerialPort, lines: Lines, head: String, body: ByteArray, news: (String) -> Unit): Boolean {
        // at the 500 KB/s a full-speed link can be counted on, plus the board's work on it
        val patience = 2000 + body.size / 200
        port.write((head + "\n").toByteArray() + body, patience)
        val end = System.currentTimeMillis() + patience
        while (System.currentTimeMillis() < end) {
            val l = lines.next(200) ?: continue
            if (l == "K") return true
            if (l == "E") return false
            news(l)
        }
        return false
    }

    /** A rectangle of the panel's picture as JPEG, for the board's decoder. Null: it stays lossless. */
    private fun jpegOf(panel: android.graphics.Bitmap, r: ScreenFrames.Rect, buf: java.io.ByteArrayOutputStream): ByteArray? = runCatching {
        buf.reset()
        val part = android.graphics.Bitmap.createBitmap(panel, r.x, r.y, r.w, r.h)
        val ok = part.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, buf)
        if (part !== panel) part.recycle()
        if (ok) buf.toByteArray() else null
    }.getOrNull()

    /**
     * A big panel: the same loop as [loop], on its own so the small boards' stays as it was. Two pictures and two pixel
     * arrays kept for the session, rectangles and JPEG by [BigSender]; all of it on this IO thread.
     */
    private suspend fun bigLoop(ctx: Context, port: UsbSerialPort, lines: Lines, inf: ScreenInfo, id: String, dev: String, lease: UsbOwnership.Lease) {
        var prev: IntArray? = null
        var spare: IntArray? = null
        var drawn = Triple(-1, -1, "")
        var bright = -1
        var turnedAt = System.currentTimeMillis()
        var frames = 0
        var countedAt = System.currentTimeMillis()
        var lastSend = 0L
        var upright: android.graphics.Bitmap? = null
        val panel = android.graphics.Bitmap.createBitmap(inf.w, inf.h, android.graphics.Bitmap.Config.ARGB_8888)
        val jpegBuf = java.io.ByteArrayOutputStream(128 * 1024)
        var useJpeg = true
        fun news(l: String) {
            when (l) {
                "N" -> { UsbScreen.next(id); turnedAt = System.currentTimeMillis() }
                "Y R" -> prev = null
            }
        }
        var sent = false
        val sender = BigSender(inf.w, inf.h, inf.maxPx, { useJpeg && inf.jpeg }, { r -> jpegOf(panel, r, jpegBuf) }) { head, body ->
            sent = true
            sendRect(port, lines, head, body, ::news)
        }
        try {
            while (dev !in flashing && lease.permitted && currentCoroutineContext().isActive) {
                val t0 = System.currentTimeMillis()
                val cfg = UsbScreen.cfgOf(id)
                useJpeg = cfg.jpeg
                if (cfg.brightness != bright) { bright = cfg.brightness; port.write("B $bright\n".toByteArray(), 300) }
                if (cfg.switchS > 0 && t0 - turnedAt > cfg.switchS * 1000L) { UsbScreen.next(id); turnedAt = t0 }
                val m = ScreenRenderer.ctxNow()
                val pageIx = ScreenRenderer.pageToShow(cfg, UsbScreen.pageOf(id), m)
                val page = cfg.pages.getOrNull(pageIx) ?: cfg.pages.first()
                val (cw, ch) = if (cfg.rotation % 2 == 1) inf.h to inf.w else inf.w to inf.h
                val key = Triple(pageIx, cfg.rotation, page.toString() + cfg.theme)
                if (key != drawn) { drawn = key; prev = null }
                val look = kg.forester.sg.ui.ScreenLook.of(cfg.theme, true)
                if (cfg.rotation % 4 == 0) ScreenRenderer.render(ctx, page, cw, ch, false, m, look, panel)
                else {
                    val up = upright?.takeIf { it.width == cw && it.height == ch }
                        ?: android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888).also { upright = it }
                    ScreenRenderer.render(ctx, page, cw, ch, false, m, look, up)
                    ScreenRenderer.toPanelInto(up, cfg.rotation, panel)
                }
                val px = spare ?: IntArray(inf.w * inf.h)
                panel.getPixels(px, 0, inf.w, 0, 0, inf.w, inf.h)
                for (i in px.indices) px[i] = ScreenFrames.rgb565(px[i])
                sent = false
                val ok = sender.frame(prev, px)
                if (sent) lastSend = System.currentTimeMillis()
                spare = prev
                // a lost answer or a refused picture: start again from black
                prev = if (ok) px else { port.write("Z\n".toByteArray(), 300); null }
                if (ok && !sent && System.currentTimeMillis() - lastSend > 2000) { port.write("H\n".toByteArray(), 300); lastSend = System.currentTimeMillis() }
                frames++
                val now = System.currentTimeMillis()
                if (now - countedAt >= 2000) { UsbScreen.fps.value = UsbScreen.fps.value + (id to frames * 1000f / (now - countedAt)); frames = 0; countedAt = now }
                val fps = ScreenFrames.fpsFor(cfg.fps.coerceIn(2, 30), kg.forester.sg.data.DeviceTier.current.value, true)
                val until = now + (1000L / fps - (now - t0)).coerceAtLeast(5)
                while (System.currentTimeMillis() < until) lines.next((until - System.currentTimeMillis()).toInt().coerceAtLeast(1))?.let(::news)
            }
        } finally {
            panel.recycle()
            upright?.recycle()
        }
    }

    private suspend fun loop(ctx: Context, port: UsbSerialPort, lines: Lines, inf: ScreenInfo, id: String, dev: String, lease: UsbOwnership.Lease) {
        var prev: IntArray? = null
        var drawn = Triple(-1, -1, "")
        var bright = -1
        var turnedAt = System.currentTimeMillis()
        var frames = 0
        var countedAt = System.currentTimeMillis()
        var lastSend = 0L
        fun news(l: String) {
            when (l) {
                "N" -> { UsbScreen.next(id); turnedAt = System.currentTimeMillis() }
                "Y R" -> prev = null
            }
        }
        while (dev !in flashing && lease.permitted && currentCoroutineContext().isActive) {
            val t0 = System.currentTimeMillis()
            val cfg = UsbScreen.cfgOf(id)
            if (cfg.brightness != bright) { bright = cfg.brightness; port.write("B $bright\n".toByteArray(), 300) }
            if (cfg.switchS > 0 && t0 - turnedAt > cfg.switchS * 1000L) { UsbScreen.next(id); turnedAt = t0 }
            val m = ScreenRenderer.ctxNow()
            val pageIx = ScreenRenderer.pageToShow(cfg, UsbScreen.pageOf(id), m)
            val page = cfg.pages.getOrNull(pageIx) ?: cfg.pages.first()
            // the board's own size is the truth; the turn decides which way up the page is drawn
            val (cw, ch) = if (cfg.rotation % 2 == 1) inf.h to inf.w else inf.w to inf.h
            val key = Triple(pageIx, cfg.rotation, page.toString())
            if (key != drawn) { drawn = key; prev = null }
            val bmp = ScreenRenderer.toPanel(ScreenRenderer.render(ctx, page, cw, ch, inf.round, m), cfg.rotation)
            val px = IntArray(inf.w * inf.h)
            bmp.getPixels(px, 0, inf.w, 0, 0, inf.w, inf.h)
            for (i in px.indices) px[i] = ScreenFrames.rgb565(px[i])
            val rects = ScreenFrames.dirty(prev, px, inf.w, inf.h)
            var ok = true
            for (r in rects) {
                val packed = ScreenFrames.pack(px, inf.w, r)
                port.write("P ${r.x} ${r.y} ${r.w} ${r.h} ${packed.size}\n".toByteArray() + packed, 2000)
                // wait for this picture's answer; the button and redraw requests may come in between
                var ack: String? = null
                val end = System.currentTimeMillis() + 2000
                while (ack == null && System.currentTimeMillis() < end) {
                    val l = lines.next(200) ?: continue
                    if (l == "K" || l == "E") ack = l else news(l)
                }
                if (ack != "K") { ok = false; break }
            }
            if (rects.isNotEmpty()) lastSend = System.currentTimeMillis()
            // a lost answer or a refused picture: start again from black
            prev = if (ok) px else { port.write("Z\n".toByteArray(), 300); null }
            // nothing changed for a while: tell the board the app is still here, or it shows NO LINK
            if (ok && rects.isEmpty() && System.currentTimeMillis() - lastSend > 2000) { port.write("H\n".toByteArray(), 300); lastSend = System.currentTimeMillis() }
            frames++
            val now = System.currentTimeMillis()
            if (now - countedAt >= 2000) { UsbScreen.fps.value = UsbScreen.fps.value + (id to frames * 1000f / (now - countedAt)); frames = 0; countedAt = now }
            // the board's button between frames
            val wait = (1000L / cfg.fps.coerceIn(2, 30) - (now - t0)).coerceAtLeast(5)
            val until = now + wait
            while (System.currentTimeMillis() < until) lines.next((until - System.currentTimeMillis()).toInt().coerceAtLeast(1))?.let(::news)
        }
    }
}
