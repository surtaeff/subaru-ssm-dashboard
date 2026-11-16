package kg.forester.sg.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kg.forester.sg.ssm.ByteLink
import kg.forester.sg.ssm.SsmTrace
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A BLE "serial port": the UART-like GATT services CAN-to-BLE bridges use (RealDash CAN boards on an ESP32 among them).
 * Known ones: Nordic UART (6E400001…: write to 6E400002, notifications from 6E400003), the HM-10 / CC254x one (FFE0 with
 * FFE1 for both) and Microchip's transparent UART (49535343…). The first the device has is taken.
 */
@SuppressLint("MissingPermission")
class BleUart private constructor(private val gatt: BluetoothGatt, override val label: String) : ByteLink {
    private val chunks = ArrayBlockingQueue<ByteArray>(4096)
    private var pending: ByteArray? = null
    private var pendingAt = 0
    @Volatile private var lost = false
    private var rx: BluetoothGattCharacteristic? = null
    private val writeDone = Semaphore(0)
    @Volatile private var mtu = 23

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var p = pending
        if (p == null) {
            p = chunks.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: run {
                if (lost) throw IOException("BLE link lost")
                return 0
            }
            pending = p; pendingAt = 0
        }
        val n = minOf(buf.size, p.size - pendingAt)
        System.arraycopy(p, pendingAt, buf, 0, n)
        pendingAt += n
        if (pendingAt >= p.size) pending = null
        return n
    }

    @Suppress("DEPRECATION")
    override fun write(b: ByteArray) {
        if (lost) throw IOException("BLE link lost")
        val ch = rx ?: throw IOException("no BLE UART")
        val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ch.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val step = (mtu - 3).coerceAtLeast(20)
        var at = 0
        while (at < b.size) {
            val part = b.copyOfRange(at, minOf(b.size, at + step))
            ch.value = part
            writeDone.drainPermits()
            if (!gatt.writeCharacteristic(ch)) throw IOException("BLE write refused")
            writeDone.tryAcquire(500, TimeUnit.MILLISECONDS)
            at += part.size
        }
    }

    override fun close() {
        lost = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun incoming(v: ByteArray?) {
        if (v == null || v.isEmpty()) return
        if (!chunks.offer(v.copyOf())) { chunks.poll(); chunks.offer(v.copyOf()) }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Service, write characteristic, notify characteristic. */
        private val SERVICES = listOf(
            Triple("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            Triple("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
            Triple("49535343-fe7d-4ae5-8fa9-9fafd205e455", "49535343-8841-43f4-a8d4-ecbe34729bb3", "49535343-1e4d-4bd9-ba61-23c647249616"),
        ).map { (s, w, n) -> Triple(UUID.fromString(s), UUID.fromString(w), UUID.fromString(n)) }

        /** The service UUIDs, for a scan that shows only such devices. */
        val serviceIds: List<UUID> get() = SERVICES.map { it.first }

        /** Connects, finds a UART service and turns its notifications on; null (with the reason in the trace) when it can't. */
        @Suppress("DEPRECATION")
        fun connect(ctx: Context, dev: BluetoothDevice, timeoutMs: Long = 12_000): BleUart? {
            val ready = CountDownLatch(1)
            var link: BleUart? = null
            var failed: String? = null
            lateinit var gattRef: BluetoothGatt
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // a bigger MTU carries a 66 frame in one notification; refused, the default 23 still works
                        if (!g.requestMtu(247)) g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        link?.lost = true
                        if (ready.count > 0) { failed = "disconnected ($status)"; ready.countDown() }
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    link?.mtu = mtu
                    pendingMtu = mtu
                    g.discoverServices()
                }

                var pendingMtu = 23

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val found = SERVICES.firstNotNullOfOrNull { (s, w, n) ->
                        val svc = g.getService(s) ?: return@firstNotNullOfOrNull null
                        val wc = svc.getCharacteristic(w) ?: return@firstNotNullOfOrNull null
                        val nc = svc.getCharacteristic(n) ?: return@firstNotNullOfOrNull null
                        wc to nc
                    }
                    if (found == null) { failed = "no UART service"; ready.countDown(); return }
                    val l = BleUart(g, "BLE ${dev.name ?: dev.address}").also { it.rx = found.first; it.mtu = pendingMtu }
                    link = l
                    g.setCharacteristicNotification(found.second, true)
                    val d = found.second.getDescriptor(CCCD)
                    if (d == null) { ready.countDown(); return }
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(d)) ready.countDown()
                }

                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ready.countDown() }

                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { link?.writeDone?.release() }

                // before Android 13 the value comes on the characteristic; from 13 on, as its own argument
                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < 33) link?.incoming(c.value)
                }

                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    link?.incoming(value)
                }
            }
            gattRef = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return null
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) failed = "timeout"
            val l = link
            if (failed != null || l == null) {
                SsmTrace.log("BLE ${dev.address}: ${failed ?: "no link"}")
                runCatching { gattRef.disconnect() }; runCatching { gattRef.close() }
                return null
            }
            runCatching { gattRef.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            return l
        }
    }
}
