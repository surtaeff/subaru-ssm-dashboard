package kg.forester.sg.ssm

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ELM327-compatible OBD adapter (Bluetooth "OBD2" dongles and their clones) on any byte stream:
 * "AT…" commands and hex requests end with CR, every answer ends with the '>' prompt.
 *
 * Limits that shape the SSM transports below: the ELM sends at most one CAN frame / 7 data bytes per
 * request, so a read carries one address at a time.
 */
class Elm327(private val input: InputStream, private val output: OutputStream, private val link: Closeable) {
    var version = ""
        private set

    fun open() {
        // wake it up and let the answer to the empty line (a prompt, "?" or a repeat) arrive, however slow the link
        output.write("\r".toByteArray()); output.flush()
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) { if (input.read() == '>'.code) break } else Thread.sleep(10)
        }
        Thread.sleep(50)
        drain()
        version = cmd("ATZ", 4000).lines().lastOrNull { it.contains("ELM", true) || it.contains("STN", true) }?.trim() ?: "?"
        Thread.sleep(100) // some clones print a second banner after the reset
        drain()
        cmd("ATE0"); cmd("ATL0"); cmd("ATS0")
        cmd("ATAT1") // adaptive timing
        cmd("ATST FF") // wait up to ~1 s for an answer
    }

    /** Sends one line and returns everything up to the prompt, without the prompt. */
    @Synchronized
    fun cmd(line: String, timeoutMs: Long = 2500): String {
        drain() // a late or unasked-for answer must not be taken for this one's
        SsmTrace.log(">> $line")
        output.write((line + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("ELM327: связь потеряна")
                if (c == '>'.code) {
                    val r = sb.toString().replace("\u0000", "").trim()
                    SsmTrace.log("<< ${r.replace('\r', '|')}")
                    return r
                }
                sb.append(c.toChar())
            } else {
                Thread.sleep(4)
            }
        }
        SsmTrace.log("<< timeout: ${sb.toString().trim().replace('\r', '|')}")
        throw ElmTimeout("ELM327: нет ответа на «$line»")
    }

    private fun drain() {
        var stale = 0
        while (input.available() > 0) { input.read(); stale++ }
        if (stale > 0) SsmTrace.log("(dropped $stale stale bytes)")
    }

    fun close() {
        runCatching { link.close() }
    }

    companion object {
        private val errors = listOf("NO DATA", "ERROR", "?", "STOPPED", "UNABLE", "BUS BUSY", "FB ERROR", "DATA ERROR", "BUFFER FULL")

        fun isError(r: String) = r.isEmpty() || errors.any { r.contains(it, true) }

        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        /** Hex text to bytes; anything that is not hex (an "OK", a message) gives an empty array. */
        fun bytes(h: String): ByteArray {
            val s = h.filter { it.isLetterOrDigit() }
            if (s.any { it !in "0123456789ABCDEFabcdef" }) return ByteArray(0)
            return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** SSM over CAN through an ELM327: 500 kbit/s 11-bit (ATSP6), ECU 7E0/7E8, TCU 7E1/7E9. */
/** No answer within the time: the adapter or the car is slow or silent, the link itself may still be up. */
class ElmTimeout(message: String) : IOException(message)

class ElmCanTransport(private val elm: Elm327) : SsmTransport {
    override val name = "ELM327, CAN"
    override val isCan = true
    override val maxReadAddrs = 1 // A8 00 + one 3-byte address fills the single frame
    private var dest = -1

    fun open() {
        elm.cmd("ATSP6"); elm.cmd("ATCAF1"); elm.cmd("ATH0")
    }

    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        if (body.size > 7) return null
        if (dest != this.dest) {
            val tx = if (dest == SsmClient.TCU) "7E1" else "7E0"
            val rx = if (dest == SsmClient.TCU) "7E9" else "7E8"
            elm.cmd("ATSH$tx"); elm.cmd("ATCRA$rx")
            this.dest = dest
        }
        val r = runCatching { elm.cmd(Elm327.hex(body), 3000) }.getOrNull() ?: return null
        if (Elm327.isError(r)) return null
        return parse(r)?.takeUnless { it.isNotEmpty() && it[0] == 0x7F.toByte() }
    }

    /** Single frame: one hex line. Multi-frame (ATCAF1): a length line, then "0:…", "1:…" lines. */
    private fun parse(r: String): ByteArray? {
        val lines = r.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) }
        if (lines.isEmpty()) return null
        if (lines.any { it.length > 2 && it[1] == ':' }) {
            val len = lines.first().takeIf { !it.contains(':') }?.toIntOrNull(16) ?: return null
            val data = lines.filter { it.contains(':') }.joinToString("") { it.substringAfter(':') }
            return Elm327.bytes(data).takeIf { it.size >= len }?.copyOf(len)
        }
        return Elm327.bytes(lines.first()).takeIf { it.isNotEmpty() }
    }

    override fun close() {}
}

/**
 * SSM2 on the K-line through an ELM327 (experimental): ISO 9141 at 4800 baud without the slow init
 * (ATIB48 + ATBI, genuine ELM v1.4+; many clones ignore them). The ELM adds the header 80 <dest> F0 and a
 * sum checksum, which is exactly SSM's.
 */
class ElmKLineTransport(private val elm: Elm327) : SsmTransport {
    override val name = "ELM327, K-Line"
    override val isCan = false
    override val maxReadAddrs = 1 // length byte + A8 00 + one address = 6 of the 7 bytes
    private var dest = -1

    fun open() {
        elm.cmd("ATSP3"); elm.cmd("ATIB48"); elm.cmd("ATH1")
        runCatching { elm.cmd("ATBI") }
    }

    override fun exchange(dest: Int, body: ByteArray): ByteArray? {
        if (body.size > 6) return null
        if (dest != this.dest) {
            elm.cmd("ATSH80%02XF0".format(dest))
            this.dest = dest
        }
        val r = runCatching { elm.cmd(Elm327.hex(byteArrayOf(body.size.toByte()) + body), 3000) }.getOrNull() ?: return null
        if (Elm327.isError(r)) return null
        val all = Elm327.bytes(r.replace("\r", "").replace("\n", ""))
        for (i in 0..all.size - 4) {
            if (all[i] == 0x80.toByte() && all[i + 1] == 0xF0.toByte() && all[i + 2] == dest.toByte()) {
                val len = all[i + 3].toInt() and 0xFF
                if (i + 4 + len <= all.size) return all.copyOfRange(i + 4, i + 4 + len)
            }
        }
        return null
    }

    override fun close() {}
}
