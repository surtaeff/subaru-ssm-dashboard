package kg.forester.sg.ssm

/**
 * Which CAN protocol an adapter speaks, by asking it as gently as can be. Only run when the driver picked the CAN adapter
 * (never in «Авто», where the same USB chips are KKL cables and the SensorBox), and only on a device the SensorBox loop
 * has let go of. In order:
 *
 * 1. RealDash CAN: nothing is sent, the device streams by itself; a good «44» or «66» frame within [listenMs].
 * 2. GVRET: E7 E7 (binary mode) and the number-of-buses and device-info questions; an answer to either.
 * 3. slcan: "V" (version); an slcan version answer (see [Slcan.versionReply]), not an echo or an ELM's "?".
 *
 * A RealDash device on a silent bus (ignition off) sends nothing: it is found once frames flow, or picked by hand.
 */
object CanProbe {
    /** [bauds]: the serial speed for each protocol (a USB link; others ignore it). */
    fun detect(
        link: ByteLink,
        order: List<String> = listOf("realdash", "gvret", "slcan"),
        bauds: (String) -> Int = { if (it == "gvret") 1_000_000 else 115_200 },
        listenMs: Int = 1200,
        log: (String) -> Unit = {},
    ): String? {
        val buf = ByteArray(4096)
        for (proto in order) {
            val found = runCatching {
                link.setBaud(bauds(proto))
                when (proto) {
                    "realdash" -> {
                        link.drain(50)
                        val dec = RealDashCan.Decoder()
                        val out = ArrayList<CanFrame>()
                        until(listenMs) { val n = link.read(buf, 50); if (n > 0) dec.feed(buf, 0, n, out); out.isNotEmpty() }
                        log("probe RealDash: ${out.size} frames, ${dec.stats.skipped} other bytes, ${dec.stats.crcErrors} CRC errors")
                        out.isNotEmpty()
                    }
                    "gvret" -> {
                        link.drain(50)
                        link.write(Gvret.hello())
                        val dec = Gvret.Decoder()
                        val out = ArrayList<CanFrame>()
                        until(800) { val n = link.read(buf, 50); if (n > 0) dec.feed(buf, 0, n, out); dec.info.buses != null || dec.info.build != null }
                        log("probe GVRET: buses ${dec.info.buses}, build ${dec.info.build}, ${dec.frames} frames")
                        dec.info.buses != null || dec.info.build != null
                    }
                    "slcan" -> {
                        link.write("\r\r".toByteArray())
                        link.drain(80)
                        link.write("V\r".toByteArray())
                        val sb = StringBuilder()
                        until(500) {
                            val n = link.read(buf, 50)
                            for (i in 0 until n) sb.append((buf[i].toInt() and 0xFF).toChar())
                            Slcan.versionReply(sb.toString())
                        }
                        log("probe slcan: ${sb.toString().replace('\r', '|').take(60)}")
                        Slcan.versionReply(sb.toString())
                    }
                    else -> false
                }
            }.onFailure { log("probe $proto: $it") }.getOrDefault(false)
            if (found) return proto
        }
        return null
    }

    private inline fun until(ms: Int, step: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) if (step()) return
    }
}
