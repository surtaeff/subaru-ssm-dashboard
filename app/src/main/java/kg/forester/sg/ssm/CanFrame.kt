package kg.forester.sg.ssm

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One CAN frame off any adapter: a CANable (slcan), a RealDash CAN or GVRET device on USB, Bluetooth or Wi-Fi, the
 * OpenPort listening to the bus. Classic frames carry 0–8 bytes, CAN FD ones up to 64: nothing downstream may take
 * 8 for granted.
 */
open class CanFrame(
    val id: Int,
    val ext: Boolean,
    val data: ByteArray,
    /** The adapter's bus, 0 on a one-bus adapter (GVRET devices have up to three). */
    val bus: Int = 0,
    /** A CAN FD frame (up to 64 bytes). */
    val fd: Boolean = false,
    /** CAN FD with the data phase at the higher rate. */
    val brs: Boolean = false,
    /** The adapter's own time stamp, µs (wraps); 0 when it gives none. */
    val timeUs: Long = 0L,
) {
    /** Payload length in bytes (not the DLC code: see [lenToDlc]). */
    val len: Int get() = data.size

    fun idText(): String = (if (ext) "%08X" else "%03X").format(id)

    fun hex(): String = data.joinToString(" ") { "%02X".format(it) }

    override fun toString() = "${if (bus > 0) "$bus:" else ""}${idText()}${if (fd) " FD" else ""} [${data.size}] ${hex()}"

    companion object {
        const val MAX_STD_ID = 0x7FF
        const val MAX_EXT_ID = 0x1FFFFFFF
        const val MAX_CLASSIC = 8
        const val MAX_FD = 64

        /** The payload lengths a CAN FD frame can have, by DLC code 0–15. */
        private val FD_LEN = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64)

        /** Bytes for DLC code [dlc] (0–15); a classic frame's 9–15 mean 8. */
        fun dlcToLen(dlc: Int, fd: Boolean): Int = if (dlc !in 0..15) 0 else if (fd) FD_LEN[dlc] else minOf(dlc, 8)

        /** The smallest DLC code that holds [len] bytes. */
        fun lenToDlc(len: Int): Int = FD_LEN.indexOfFirst { it >= len }.let { if (it < 0) 15 else it }

        /** A length a CAN FD frame can have: [len] rounded up (the adapter pads with zeroes). */
        fun fdLen(len: Int): Int = FD_LEN[lenToDlc(len)]

        fun validId(id: Int, ext: Boolean) = id >= 0 && id <= if (ext) MAX_EXT_ID else MAX_STD_ID
    }
}

/**
 * Where a CAN session's frames come from: a CANable, a RealDash CAN or GVRET device, or the Link demo. The session
 * reads it in a loop and hands every frame to [CanBus].
 */
interface CanSource : Closeable {
    /** Frames that came within [timeoutMs]; empty when the bus is quiet. */
    fun read(timeoutMs: Int): List<CanFrame>
    /** Error answers since the bus was opened. */
    val errors: Int
    /** What the adapter is, for the status line. */
    val title: String get() = "CANable"
    /** How many buses the adapter has. */
    val buses: Int get() = 1
    /** The adapter can put frames on the bus at all (see [transmit]). */
    val canTransmit: Boolean get() = false

    /**
     * Puts [f] on the bus. Only [CanTx] calls this: every transmission passes its gate, and [permit] is what it hands
     * over for the one frame it let through. False when the adapter cannot send or the write failed.
     */
    fun transmit(f: CanFrame, permit: CanTx.Permit): Boolean = false
}

/**
 * Every frame heard, whatever the adapter (a CANable, a RealDash CAN or GVRET device, the SensorBox's CAN, the OpenPort
 * listening) or a log played back ([CanReplay]): the monitor («Что идёт по CAN», the sniffer), the driver's own
 * channels and, on a sports ECU's dash CAN, the Link's stream. Anything else that wants the raw frames (the log
 * recorder, the «нажмите кнопку» capture, a decoder) adds a [listeners] entry.
 */
object CanBus {
    /** One more consumer of every frame; [mux] is the Link's frame number when the frame was its compound stream. */
    fun interface Listener { fun frame(f: CanFrame, mux: Int?, now: Long) }

    /** Called on the reading thread for every frame: keep it short. */
    val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * [f] to everyone. [link]: look for the Link's dash stream in it too (a CAN adapter session, a replay; the OpenPort
     * listening to a Subaru's bus does not). Returns the Link's Generic Dash frame number when it was that stream.
     */
    fun dispatch(f: CanFrame, now: Long = System.currentTimeMillis(), link: Boolean = false): Int? {
        val mux = if (link && f.bus == 0) LinkDash.feed(f.id, f.ext, f.data, now) else null
        CanMonitor.feed(f, now, mux)
        kg.forester.sg.data.CanChannels.feed(f.id, f.ext, f.data, now)
        for (l in listeners) runCatching { l.frame(f, mux, now) }
        return mux
    }
}
