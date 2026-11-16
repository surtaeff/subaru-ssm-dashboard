package kg.forester.sg.service

import android.annotation.SuppressLint
import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.CanConfig
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.ByteLink
import kg.forester.sg.ssm.CanBaudProbe
import kg.forester.sg.ssm.CanFrame
import kg.forester.sg.ssm.CanProbe
import kg.forester.sg.ssm.CanSource
import kg.forester.sg.ssm.CanTx
import kg.forester.sg.ssm.GvretSource
import kg.forester.sg.ssm.RealDashSource
import kg.forester.sg.ssm.Slcan
import kg.forester.sg.ssm.SlcanIo
import kg.forester.sg.ssm.SlcanLinkIo
import kg.forester.sg.ssm.SlcanListen
import kg.forester.sg.ssm.SlcanPort
import kg.forester.sg.ssm.SlcanUsbIo
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ssm.StreamByteLink
import kg.forester.sg.ssm.UsbByteLink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opening the CAN adapter the driver set up (protocol and link in [CanConfig]): a USB serial device the ECU loop found,
 * a Bluetooth one (classic SPP or BLE UART, by what the device is), or a Wi-Fi one (TCP, found by mDNS). The CANable
 * known by its USB ids keeps its own path (slcan, see EcuService.canableSession).
 *
 * Two probes, in this order: [CanProbe] finds the protocol, then, on an slcan adapter and only when the CAN wizard asked
 * for it, [CanBaudProbe] finds the bus speed, listen-only. RealDash CAN and GVRET devices take the speed they are told.
 */
object CanLinks {
    /** USB devices that answered as no CAN adapter, by device name: asked again after [RETRY_MS] (a RealDash board on a quiet bus). */
    private val notCan = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 20_000L

    /** The ESP32RET's own Wi-Fi network: it is the access point at 192.168.4.1, GVRET on port 23. */
    const val DEFAULT_TCP = "192.168.4.1:23"
    const val GVRET_PORT = 23

    fun mayProbe(device: String, now: Long = System.currentTimeMillis()) = notCan[device]?.let { now - it > RETRY_MS } ?: true

    /** What the last open said, for the status line: "RealDash CAN (найден опросом)". */
    @Volatile var lastNote = ""
        private set

    /**
     * [port] (open) as the CAN adapter: the protocol picked, else the one this device ([id] = VID:PID) spoke last time,
     * else found by asking ([CanProbe]). Null when it is no CAN adapter.
     */
    fun openUsb(port: UsbSerialPort, device: String, id: String, kbit: Int): CanSource? {
        val link = UsbByteLink(port, "USB $id")
        val picked = CanConfig.protocol
        val remembered = CanConfig.usbProtocol(id)
        var proto = if (picked != "auto") picked else remembered
        var how = if (picked != "auto") "выбран" else "запомнен"
        if (proto == null) {
            proto = CanProbe.detect(link, bauds = CanConfig::baudFor, log = SsmTrace::log)
            how = "найден опросом"
            if (proto == null) { notCan[device] = System.currentTimeMillis(); lastNote = ""; return null }
            CanConfig.setUsbProtocol(id, proto)
        }
        notCan.remove(device)
        lastNote = ""
        val source = open(proto, link, kbit, port)
        // a GVRET board that does not answer is not the one remembered: asked again next time
        if (source is GvretSource && !source.info.answered && picked == "auto") CanConfig.setUsbProtocol(id, null)
        // no source: open said why (an slcan adapter that would not listen only)
        if (source != null || lastNote.isEmpty()) lastNote = "${source?.title ?: proto} ($how)"
        return source
    }

    /**
     * [proto] on [link], set up for [kbit]; [usb] when it is a USB port (slcan there keeps the CANable's own code).
     * slcan: the speed probe first when the wizard asked for it (the speed found is saved in the settings and used),
     * then the bus opened, listen-only on the car's bus ([CanConfig.slcanListenOnly]); null when the adapter refused
     * listen-only (the bus stays closed, [lastNote] says why). Any other protocol answers a speed probe as unsupported.
     */
    fun open(proto: String, link: ByteLink, kbit: Int, usb: UsbSerialPort? = null): CanSource? = when (proto) {
        "slcan" -> {
            val dev = usb?.driver?.device
            val canableFw = dev != null && SlcanListen.canableFw(dev.vendorId, dev.productId)
            val k = probeSpeed(if (usb != null) SlcanUsbIo(usb).also { it.setUp() } else SlcanLinkIo(link), canableFw, kbit)
            val listenOnly = CanConfig.slcanListenOnly()
            val source: CanSource? = if (usb != null) SlcanPort(usb).takeIf { it.open(k, listenOnly) }
                else SlcanLinkSource(link).takeIf { it.open(k, listenOnly) }
            if (source == null) lastNote = "slcan: адаптер не включил режим «только приём», шина не открыта"
            source
        }
        "realdash" -> {
            link.setBaud(CanConfig.baudFor(proto))
            RealDashSource(link, kbit, CanConfig.realdashConfig, CanConfig.listenOnly(0)).also { it.open() }
        }
        "gvret" -> {
            link.setBaud(CanConfig.baudFor(proto))
            GvretSource(link, kbit, CanConfig::listenOnly).also { it.open() }
        }
        else -> null
    }.also { if (proto != "slcan") CanBaudProbe.unsupported(it?.title ?: proto) }

    /** The CAN wizard's «Подобрать скорость» on an slcan adapter: listen-only at each speed; the speed to open at. */
    private fun probeSpeed(io: SlcanIo, canableFw: Boolean, kbit: Int): Int {
        if (!CanBaudProbe.requested) return kbit
        var found = kbit
        CanBaudProbe.runOn(io, canableFw) { found = it; Repo.settings.canKbit = it }
        return found
    }

    /** A link without serial settings (Bluetooth, Wi-Fi): the protocol picked, else found by asking, [order] first. */
    fun openRemote(link: ByteLink, kbit: Int, order: List<String>): CanSource? {
        val picked = CanConfig.protocol
        val proto = if (picked != "auto") picked else CanProbe.detect(link, order, log = SsmTrace::log)
        lastNote = proto?.let { "${if (picked != "auto") "выбран" else "найден опросом"}" } ?: ""
        return proto?.let { open(it, link, kbit) }
    }

    /** "host:port" → the pair; port 23 when none is given. */
    fun hostPort(s: String): Pair<String, Int>? {
        val t = s.trim().removePrefix("tcp://")
        if (t.isEmpty()) return null
        val host = t.substringBeforeLast(':', t).trim()
        val port = if (':' in t) t.substringAfterLast(':').trim().toIntOrNull() ?: return null else GVRET_PORT
        return if (host.isEmpty() || port !in 1..65535) null else host to port
    }

    /** A TCP connection to [host]:[port], over the Wi-Fi network when there is one (it has no internet: Android skips it otherwise). */
    fun tcp(ctx: Context, host: String, port: Int): ByteLink? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
        val sock = runCatching {
            (wifi?.socketFactory?.createSocket() ?: java.net.Socket()).also {
                it.connect(java.net.InetSocketAddress(host, port), 3000)
                it.tcpNoDelay = true
            }
        }.onFailure { SsmTrace.log("CAN tcp $host:$port: $it") }.getOrNull() ?: return null
        return StreamByteLink(sock.getInputStream(), sock.getOutputStream(), sock, "Wi-Fi $host:$port")
    }

    /**
     * GVRET boards announcing themselves on the local network (ESP32RET: mDNS "_telnet._tcp" on port 23), as
     * "name" to "host:port". Waits up to [ms].
     */
    fun discover(ctx: Context, ms: Long = 4000): List<Pair<String, String>> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return emptyList()
        val found = java.util.Collections.synchronizedList(ArrayList<android.net.nsd.NsdServiceInfo>())
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { SsmTrace.log("mDNS: start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) { found += info }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices("_telnet._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
        Thread.sleep(ms)
        runCatching { nsd.stopServiceDiscovery(listener) }
        // one resolve at a time: the manager refuses a second while one runs
        return found.toList().distinctBy { it.serviceName }.mapNotNull { info ->
            val done = CountDownLatch(1)
            var out: Pair<String, String>? = null
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, errorCode: Int) { done.countDown() }
                    override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) {
                        val h = i.host?.hostAddress
                        if (h != null) out = i.serviceName to "$h:${i.port}"
                        done.countDown()
                    }
                })
            }
            done.await(2, TimeUnit.SECONDS)
            out
        }
    }

    /** The Bluetooth CAN adapter: BLE UART for a BLE-only device, SPP ([spp]) otherwise, BLE as a second try. */
    @SuppressLint("MissingPermission")
    fun bluetooth(ctx: Context, addr: String, spp: () -> android.bluetooth.BluetoothSocket?): ByteLink? {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter ?: return null
        val dev = runCatching { bt.getRemoteDevice(addr) }.getOrNull() ?: return null
        val name = runCatching { dev.name }.getOrNull() ?: addr
        val type = runCatching { dev.type }.getOrDefault(android.bluetooth.BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        if (type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE) return BleUart.connect(ctx, dev)
        spp()?.let { return StreamByteLink(it.inputStream, it.outputStream, it, "Bluetooth $name") }
        return if (type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC) BleUart.connect(ctx, dev) else null
    }

    /** The manual tool's frame from what the driver typed; null with junk. */
    fun parseFrame(idText: String, ext: Boolean, dataText: String, bus: Int, fd: Boolean): CanFrame? {
        val id = idText.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
        if (!CanFrame.validId(id, ext)) return null
        val hex = dataText.filter { !it.isWhitespace() && it != ':' && it != '-' }
        if (hex.length % 2 != 0) return null
        val data = ByteArray(hex.length / 2) { i -> (hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte() }
        if (data.size > if (fd) CanFrame.MAX_FD else CanFrame.MAX_CLASSIC) return null
        return CanFrame(id, ext, data, bus = bus, fd = fd)
    }
}

/** slcan on a link that is no USB port (a Bluetooth bridge): the same lines as [SlcanPort], without serial settings. */
class SlcanLinkSource(private val link: ByteLink) : CanSource {
    private val buf = ByteArray(4096)
    private val line = StringBuilder()
    override var errors = 0
        private set
    override val title get() = "slcan"
    override val canTransmit get() = !silent

    private fun send(cmd: String) = link.write((cmd + "\r").toByteArray(Charsets.US_ASCII))

    /** Opened listen-only: nothing goes out, not even what the transmit gate let through. */
    @Volatile private var silent = false

    /** [listenOnly]: Lawicel's "L", never "O" (see [SlcanListen]); false when the adapter refused it (the bus stays closed). */
    fun open(kbit: Int, listenOnly: Boolean = false): Boolean {
        silent = listenOnly
        send("C"); Thread.sleep(50); link.drain(100)
        send(Slcan.speedCommand(kbit)); Thread.sleep(30)
        if (listenOnly) {
            val ok = SlcanListen.open(SlcanLinkIo(link), canableFw = false)
            errors = 0
            return ok
        }
        send("O"); Thread.sleep(30)
        errors = 0
        return true
    }

    override fun read(timeoutMs: Int): List<CanFrame> {
        val n = link.read(buf, timeoutMs)
        if (n <= 0) return emptyList()
        val out = ArrayList<CanFrame>()
        for (i in 0 until n) {
            when (val c = (buf[i].toInt() and 0xFF).toChar()) {
                '\r', '\n' -> { Slcan.parse(line.toString())?.let(out::add); line.setLength(0) }
                '\u0007' -> { errors++; line.setLength(0) }
                else -> if (line.length < 160) line.append(c) else line.setLength(0)
            }
        }
        return out
    }

    override fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean =
        !silent && permit.covers(f) && runCatching { send(Slcan.format(f)) }.isSuccess

    override fun close() { runCatching { send("C") }; link.close() }
}
