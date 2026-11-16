package kg.forester.sg.data

import android.content.Context
import android.content.SharedPreferences
import kg.forester.sg.ssm.CanTx

/**
 * The CAN adapter's settings beyond the bus speed ([Settings.canKbit]): which protocol, over what, and the transmit
 * gate's ([CanTx]) switches. Kept in the same preferences as the rest (so the backup has them), under "can." keys.
 */
object CanConfig {
    private var p: SharedPreferences? = null
    /** Bumped at every change: the screens showing these settings read them again. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        CanTx.policy = ::policy
    }

    private fun str(k: String, d: String) = p?.getString(k, d) ?: d
    private fun putStr(k: String, v: String) { p?.edit()?.putString(k, v)?.apply(); version.value++ }
    private fun bool(k: String, d: Boolean) = p?.getBoolean(k, d) ?: d
    private fun putBool(k: String, v: Boolean) { p?.edit()?.putBoolean(k, v)?.apply(); version.value++ }
    private fun int(k: String, d: Int) = p?.getInt(k, d) ?: d
    private fun putInt(k: String, v: Int) { p?.edit()?.putInt(k, v)?.apply(); version.value++ }

    /** The protocols, by key, with what the settings call them. */
    val PROTOCOLS = listOf("auto" to "Авто", "slcan" to "slcan (CANable)", "realdash" to "RealDash CAN", "gvret" to "GVRET (ESP32RET)")
    val TRANSPORTS = listOf("usb" to "USB", "bt" to "Bluetooth", "tcp" to "Wi-Fi")

    /** "auto" (found by asking the adapter), "slcan", "realdash", "gvret". */
    var protocol: String
        get() = str("can.proto", "auto")
        set(v) = putStr("can.proto", v)

    /** "usb", "bt" (classic SPP or BLE UART, by what the device is), "tcp". */
    var transport: String
        get() = str("can.transport", "usb")
        set(v) = putStr("can.transport", v)

    /** The Bluetooth CAN adapter (its own, apart from the ELM327's). */
    var btAddress: String
        get() = str("can.btAddress", "")
        set(v) = putStr("can.btAddress", v)

    var btName: String
        get() = str("can.btName", "")
        set(v) = putStr("can.btName", v)

    /** "host:port" of a Wi-Fi adapter; empty: found by mDNS, else an ESP32RET's own network (192.168.4.1:23). */
    var tcpHost: String
        get() = str("can.tcpHost", "")
        set(v) = putStr("can.tcpHost", v)

    /** The serial speed on USB; 0: by the protocol (1 Mbaud for GVRET as ESP32RET has it, 115200 otherwise). */
    var baud: Int
        get() = int("can.baud", 0)
        set(v) = putInt("can.baud", v)

    fun baudFor(proto: String) = baud.takeIf { it > 0 } ?: if (proto == "gvret") 1_000_000 else 115_200

    /** Tell a RealDash CAN device the bus speed and mode («67» frames) when connecting. */
    var realdashConfig: Boolean
        get() = bool("can.rdConfig", true)
        set(v) = putBool("can.rdConfig", v)

    /** What a USB device (VID:PID[/serial]) turned out to speak: the next time it is opened straight away. */
    fun usbProtocol(id: String): String? = p?.getString("can.usb.$id", null)
    fun setUsbProtocol(id: String, proto: String?) {
        p?.edit()?.apply { if (proto == null) remove("can.usb.$id") else putString("can.usb.$id", proto) }?.apply()
    }

    // ---- the transmit gate

    /** Transmitting at all. Off by default, and nothing car-specific is ever sent by the app itself. */
    var txEnabled: Boolean
        get() = bool("can.tx.on", false)
        set(v) = putBool("can.tx.on", v)

    /** Checked and logged, not written to the bus. On by default once transmitting is on. */
    var txSimulate: Boolean
        get() = bool("can.tx.sim", true)
        set(v) = putBool("can.tx.sim", v)

    /** Allowed ids, "3E8,1FFFF001x" (hex; an "x" marks a 29-bit one). */
    var txAllowed: String
        get() = str("can.tx.ids", "")
        set(v) = putStr("can.tx.ids", v)

    var txPerIdPerSec: Int
        get() = int("can.tx.perId", 10)
        set(v) = putInt("can.tx.perId", v)

    var txTotalPerSec: Int
        get() = int("can.tx.total", 50)
        set(v) = putInt("can.tx.total", v)

    /** What each bus is, "0:sensor,1:powertrain" (see [CanTx.Role]); a bus not listed is not marked. */
    var busRoles: String
        get() = str("can.busRoles", "")
        set(v) = putStr("can.busRoles", v)

    fun roles(): Map<Int, CanTx.Role> = parseRoles(busRoles)

    fun setRole(bus: Int, role: CanTx.Role) {
        busRoles = formatRoles(roles() + (bus to role))
    }

    fun policy(): CanTx.Policy = CanTx.Policy(
        enabled = txEnabled, simulate = txSimulate, allowed = parseIds(txAllowed),
        perIdPerSec = txPerIdPerSec.coerceIn(1, 100), totalPerSec = txTotalPerSec.coerceIn(1, 500), roles = roles(),
    )

    /**
     * The bus may not answer at all (listen-only, no ACK): the car's own buses and any not marked. Only the driver's
     * sensor bus (two nodes need the ACK) and a comfort bus with real transmitting on take part.
     */
    fun listenOnly(bus: Int): Boolean = when (roles()[bus] ?: CanTx.Role.UNKNOWN) {
        CanTx.Role.SENSOR -> false
        CanTx.Role.COMFORT -> !(txEnabled && !txSimulate)
        else -> true
    }

    /** What makes a running CAN session start over when it changes. */
    fun signature(): String = listOf(protocol, transport, btAddress, tcpHost, baud, realdashConfig, busRoles, txEnabled, txSimulate).joinToString("|")

    // ---- pure parts (tests)

    fun parseRoles(s: String): Map<Int, CanTx.Role> = s.split(',').mapNotNull { e ->
        val (b, r) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (b.trim().toIntOrNull() ?: return@mapNotNull null) to CanTx.Role.of(r.trim())
    }.toMap()

    fun formatRoles(m: Map<Int, CanTx.Role>) = m.toSortedMap().filterValues { it != CanTx.Role.UNKNOWN }.entries.joinToString(",") { "${it.key}:${it.value.key}" }

    /** "3E8, 0x7FF 18FEF100x" → gate keys; a 29-bit id is marked with "x" (or is above 7FF). Junk is left out. */
    fun parseIds(s: String): Set<Long> = s.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { t ->
        val x = t.endsWith("x", ignoreCase = true)
        val hex = t.removeSuffix("x").removeSuffix("X").removePrefix("0x").removePrefix("0X")
        val id = hex.toLongOrNull(16)?.takeIf { it in 0..0x1FFFFFFF }?.toInt() ?: return@mapNotNull null
        CanTx.key(id, x || id > 0x7FF)
    }.toSet()
}
