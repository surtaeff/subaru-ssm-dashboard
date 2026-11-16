package kg.forester.sg.ssm

import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * A one-shot SSM1 check on a USB serial cable whose car stays silent to SSM2: switch to 1953 baud, ask the ROM id the
 * way the old Select Monitor does, hand the cable back. An SSM1 unit talks on the vendor pins 12 and 13 of the OBD
 * socket (5 V UART, separate lines: no echo of our own bytes), so a cable wired for it is silent to SSM2 and hears
 * no echo either.
 *
 * The caller stops its own transport first and opens it again after: this changes the port's baud rate and takes
 * its reading over for about a second and a half at most.
 */
object SsmP1Probe {

    /** The ROM id in hex ("A30113"), or "" when nothing answered SSM1 either. Never throws. */
    fun run(port: UsbSerialPort): String = runCatching {
        val client = SsmP1Client(port)
        try {
            client.open()
            val id = client.getId()?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.orEmpty()
            SsmTrace.log(if (id.isEmpty()) "SSM1 probe: no answer" else "SSM1 probe: ROM $id")
            id
        } finally {
            runCatching { client.stop() }
            runCatching { client.close() }
        }
    }.onFailure { SsmTrace.log("SSM1 probe: $it") }.getOrDefault("")
}
