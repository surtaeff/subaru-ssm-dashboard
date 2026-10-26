package kg.forester.sg

import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.ssm.OpenPort2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPort2RecordTest {
    /** The parser never touches the port; a proxy that refuses everything is enough. */
    private val port = java.lang.reflect.Proxy.newProxyInstance(
        UsbSerialPort::class.java.classLoader, arrayOf(UsbSerialPort::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException() } as UsbSerialPort

    private fun record(proto: Int, body: ByteArray) = byteArrayOf('a'.code.toByte(), 'r'.code.toByte(), ('0' + proto).code.toByte(), body.size.toByte()) + body

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun kLineRecordsHaveNoTimestamp() {
        val op2 = OpenPort2(port)
        val got = mutableListOf<Pair<Int, ByteArray>>()
        op2.onFrame = { proto, _, data -> got += proto to data }
        val echo = bytes(0x80, 0x10, 0xF0, 0x01, 0xBF, 0x40)
        val head = bytes(0x80, 0xF0, 0x10, 0x69, 0xFF, 0xA2, 0x10, 0x11)
        // the echo, a start indication with its timestamp only, the answer's start split over two USB reads
        val stream = record(3, bytes(0x20) + echo) + record(3, bytes(0x80, 1, 2, 3, 4)) + record(3, bytes(0x00) + head)
        op2.feed(stream.copyOfRange(0, 20))
        op2.feed(stream.copyOfRange(20, stream.size))
        assertEquals(2, got.size)
        assertArrayEquals(echo, got[0].second)
        assertArrayEquals(head, got[1].second)
    }

    @Test fun canRecordsKeepTheTimestamp() {
        val op2 = OpenPort2(port)
        var data: ByteArray? = null
        op2.onFrame = { _, _, d -> data = d }
        val frame = bytes(0, 0, 0x07, 0xE8, 0x03, 0x41, 0x0C, 0x1A)
        op2.feed(record(5, bytes(0x00, 9, 9, 9, 9) + frame))
        assertArrayEquals(frame, data)
    }
}
