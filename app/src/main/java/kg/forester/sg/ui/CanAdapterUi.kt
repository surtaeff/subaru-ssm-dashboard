package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.ShieldAlert
import kg.forester.sg.data.CanConfig
import kg.forester.sg.service.CanLinks
import kg.forester.sg.ssm.CanTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The CAN adapter's protocol and link («Машина и адаптер», adapter «CAN»): slcan, RealDash CAN or GVRET, over USB,
 * Bluetooth or Wi-Fi, and the way to the transmit gate. The bus speed and the rest stay in [CanablePanel].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanAdapterPanel() {
    val cfg = CanConfig
    CanConfig.version.collectAsState().value
    var proto by remember { mutableStateOf(cfg.protocol) }
    var transport by remember { mutableStateOf(cfg.transport) }
    var tx by remember { mutableStateOf(false) }
    T("CAN: протокол адаптера", 15.sp)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CanConfig.PROTOCOLS.forEach { (k, l) -> Chip(l, proto == k, { proto = k; cfg.protocol = k }) }
    }
    Spacer(Modifier.height(4.dp))
    SettingHint(when (proto) {
        "slcan" -> "Текстовый протокол Lawicel: CANable, CANtact и клоны. Поддерживает CAN FD у CANable 2.0 (кадры d/b)"
        "realdash" -> "Протокол RealDash CAN: самодельные платы (Arduino с MCP2515, ESP32) и мосты CAN–Bluetooth. Адаптер сам шлёт кадры " +
            "44/66 (66 с CRC32, до 64 байт для CAN FD); скорость и режим ему передаются кадрами 67, если он их понимает"
        "gvret" -> "Протокол GVRET, как у SavvyCAN: ESP32RET, M2RET, A0RET. USB на 1 Мбод или Wi-Fi, порт 23. Несколько шин, CAN FD"
        else -> "Приложение узнает протокол само: слушает кадры RealDash, спрашивает GVRET, затем slcan. Найденный запоминается для этого " +
            "USB-устройства. Опрашиваются только устройства, которые не оказались SensorBox"
    })
    Spacer(Modifier.height(10.dp))
    T("Подключение", 15.sp)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CanConfig.TRANSPORTS.forEach { (k, l) -> Chip(l, transport == k, { transport = k; cfg.transport = k }) }
    }
    Spacer(Modifier.height(6.dp))
    when (transport) {
        "bt" -> CanBluetoothPicker()
        "tcp" -> CanWifiHost()
        else -> {
            var baud by remember { mutableStateOf(cfg.baud) }
            Labeled("Скорость USB") {
                listOf(0 to "по протоколу", 115_200 to "115200", 500_000 to "500000", 1_000_000 to "1 Мбод", 2_000_000 to "2 Мбод").forEach { (b, l) ->
                    Chip(l, baud == b, { baud = b; cfg.baud = b })
                }
            }
            SettingHint("По протоколу: GVRET 1 Мбод (ESP32RET), RealDash и slcan 115200. У CANable скорость порта не важна")
        }
    }
    if (proto == "realdash" || proto == "auto") {
        Spacer(Modifier.height(6.dp))
        var rd by remember { mutableStateOf(cfg.realdashConfig) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("RealDash: передавать адаптеру скорость и режим (кадр 67)", 13.sp, modifier = Modifier.weight(1f))
            Switch(rd, { rd = it; cfg.realdashConfig = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
        }
    }
    Spacer(Modifier.height(10.dp))
    CanBusRoles()
    Spacer(Modifier.height(8.dp))
    Chip(if (cfg.txEnabled) "Передача в CAN: включена" + (if (cfg.txSimulate) " (симуляция)" else "") else "Передача в CAN: выключена",
        cfg.txEnabled, { tx = true }, Lucide.ShieldAlert)
    if (tx) CanTxDialog { tx = false }
    Spacer(Modifier.height(14.dp))
}

/** What each bus of the adapter is: the transmit gate and the listen-only choice go by it. */
@Composable
private fun CanBusRoles() {
    val roles = CanConfig.roles()
    val n = maxOf(1, (CanTx.source?.buses ?: 1), (roles.keys.maxOrNull() ?: 0) + 1).coerceAtMost(4)
    T("Шины адаптера", 15.sp)
    for (bus in 0 until n) {
        Labeled(if (n == 1) "Шина" else "Шина $bus") {
            CanTx.Role.entries.forEach { r -> Chip(r.title, (roles[bus] ?: CanTx.Role.UNKNOWN) == r, { CanConfig.setRole(bus, r) }) }
        }
    }
    SettingHint("Силовая и неотмеченная шина: адаптер только слушает (без подтверждений, если он это умеет), передача запрещена. " +
        "Своя шина датчиков: адаптер участвует в обмене (иначе датчик без подтверждений уйдёт в ошибку). Шину с потоком Link " +
        "приложение всегда считает силовой")
}

/** The Bluetooth CAN adapter: a paired one (SPP or BLE), or one found by a BLE scan (BLE adapters are often not paired). */
@OptIn(ExperimentalLayoutApi::class)
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun CanBluetoothPicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = CanConfig
    val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted() = perms.all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    var chosen by remember { mutableStateOf(cfg.btAddress) }
    val scanned = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { ok = granted() }
    if (!ok) {
        ActionButton("Разрешить Bluetooth", true) { ask.launch(perms) }
        return
    }
    val bt = (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    if (bt == null) { T("На устройстве нет Bluetooth", 12.sp, C.Orange); return }
    if (!bt.isEnabled) T("Bluetooth выключен", 12.sp, C.Orange)
    val bonded = runCatching { bt.bondedDevices?.toList() }.getOrNull().orEmpty().map { (it.name ?: it.address) to it.address }
    val all = (bonded + scanned).distinctBy { it.second }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { (name, addr) ->
            Chip(name, chosen == addr, { chosen = addr; cfg.btAddress = addr; cfg.btName = name })
        }
        Chip(if (scanning) "Ищу BLE…" else "Найти BLE-адаптер", false, {
            if (scanning) return@Chip
            scanning = true
            scope.launch {
                val found = withContext(Dispatchers.IO) { bleScan(bt, 8000) }
                found.forEach { f -> if (scanned.none { it.second == f.second }) scanned += f }
                scanning = false
            }
        }, Lucide.Search)
    }
    SettingHint("Классический Bluetooth (SPP) сопрягите в настройках Android. BLE-адаптер (сервис UART: Nordic, HM-10, Microchip) " +
        "можно найти поиском здесь, сопрягать не нужно")
}

/** Named BLE devices seen within [ms], the ones advertising a UART service first. */
@android.annotation.SuppressLint("MissingPermission")
private fun bleScan(bt: android.bluetooth.BluetoothAdapter, ms: Long): List<Pair<String, String>> {
    val scanner = bt.bluetoothLeScanner ?: return emptyList()
    val uart = kg.forester.sg.service.BleUart.serviceIds.map { android.os.ParcelUuid(it) }.toSet()
    val seen = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()
    val cb = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, r: android.bluetooth.le.ScanResult) {
            val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: return
            val isUart = r.scanRecord?.serviceUuids?.any { it in uart } == true
            seen[r.device.address] = name to isUart
        }
    }
    runCatching { scanner.startScan(cb) }.onFailure { return emptyList() }
    Thread.sleep(ms)
    runCatching { scanner.stopScan(cb) }
    return seen.entries.sortedByDescending { it.value.second }.map { it.value.first to it.key }
}

/** The Wi-Fi adapter's address, and an mDNS search for ESP32RET boards on the network. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanWifiHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var host by remember { mutableStateOf(CanConfig.tcpHost) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val scope = rememberCoroutineScope()
    TextBox("Адрес (IP:порт), пусто: поиск по mDNS, затем ${CanLinks.DEFAULT_TCP}", host) { host = it; CanConfig.tcpHost = it.trim() }
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (searching) "Ищу в сети…" else "Найти в сети (mDNS)", false, {
            if (searching) return@Chip
            searching = true
            scope.launch {
                found = withContext(Dispatchers.IO) { CanLinks.discover(ctx.applicationContext, 4000) }
                searching = false
            }
        }, Lucide.Search)
        found?.forEach { (name, hp) -> Chip("$name · $hp", host == hp, { host = hp; CanConfig.tcpHost = hp }) }
    }
    if (found?.isEmpty() == true) T("Ничего не найдено. Магнитола в сети адаптера (ESP32RETSSID)? Адрес в своей сети ESP32RET — 192.168.4.1:23", 12.sp, C.Sub)
}

/**
 * The transmit gate: off by default, with a warning; the allowed ids, the rate limits, simulation, the log of what was
 * sent, and the manual «отправить кадр» tool for a bench or the driver's own sensor bus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanTxDialog(onClose: () -> Unit) {
    val cfg = CanConfig
    CanConfig.version.collectAsState().value
    val log by CanTx.log.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    CarSheet(onClose, 860.dp, 640.dp, head = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Передача в CAN", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Готово", true, onClose)
        }
        Spacer(Modifier.height(4.dp))
    }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(12.dp)) {
            T("Осторожно", 15.sp, C.Red, FontWeight.SemiBold)
            T("Кадр, отправленный в шину машины, могут принять её блоки: ошибки ABS, подушек, иммобилайзера, отказ узлов на ходу. " +
                "Передавайте только на стенде или в свою шину датчиков. В силовую шину и в неотмеченную приложение не передаёт никогда, " +
                "диагностические ID (7DF, 7E0–7EF, 18DA…, 18DB…) запрещены всегда. Сами по себе никакие кадры не шлются: только то, что вы " +
                "отправите здесь вручную", 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Разрешить передачу", 15.sp)
                SettingHint("Выключено по умолчанию. Всё, что проходит через шлюз, записывается в журнал ниже")
            }
            Switch(cfg.txEnabled, { on -> if (on) confirm = true else cfg.txEnabled = false },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Red))
        }
        if (confirm) ConfirmDialog("Разрешить передачу в CAN?", "Только для стенда или своей шины датчиков. Симуляция останется включённой, " +
            "пока вы её не выключите.", "Разрешить", { cfg.txEnabled = true }, { confirm = false })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Симуляция", 15.sp)
                SettingHint("Кадр проходит все проверки и пишется в журнал, но в шину не уходит")
            }
            Switch(cfg.txSimulate, { cfg.txSimulate = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
        }
        Spacer(Modifier.height(8.dp))
        var ids by remember { mutableStateOf(cfg.txAllowed) }
        TextBox("Разрешённые ID (hex через запятую; 29-битный с x на конце: 18FF0001x)", ids) { ids = it; cfg.txAllowed = it }
        val parsed = CanConfig.parseIds(ids)
        T(if (parsed.isEmpty()) "Список пуст: не уйдёт ни один кадр" else "В списке ${parsed.size} ID", 12.sp, C.Sub)
        Spacer(Modifier.height(6.dp))
        var perId by remember { mutableStateOf(cfg.txPerIdPerSec) }
        var total by remember { mutableStateOf(cfg.txTotalPerSec) }
        Labeled("Не чаще, на ID в секунду") { listOf(1, 5, 10, 20).forEach { n -> Chip("$n", perId == n, { perId = n; cfg.txPerIdPerSec = n }) } }
        Labeled("Всего в секунду") { listOf(10, 50, 100).forEach { n -> Chip("$n", total == n, { total = n; cfg.txTotalPerSec = n }) } }
        Spacer(Modifier.height(8.dp))
        CanBusRoles()
        Spacer(Modifier.height(10.dp))
        CanSendTool()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Журнал (${log.size})", 15.sp, modifier = Modifier.weight(1f))
            if (log.isNotEmpty()) Chip("Очистить", false, { CanTx.clearLog() })
        }
        if (log.isEmpty()) T("Пока ничего не отправлялось.", 13.sp, C.Sub)
        val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US) }
        log.take(60).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(fmt.format(java.util.Date(e.timeMs)), Modifier.width(100.dp), color = C.Sub, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(e.frame.toString(), Modifier.weight(1f), color = C.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                T(e.verdict.text, 12.sp, if (e.verdict.ok) C.Green else C.Orange, modifier = Modifier.width(220.dp))
            }
        }
    }
}

/** «Отправить кадр»: one frame typed by hand, through the gate like any other. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanSendTool() {
    var idText by remember { mutableStateOf("") }
    var ext by remember { mutableStateOf(false) }
    var dataText by remember { mutableStateOf("") }
    var bus by remember { mutableStateOf(0) }
    var fd by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CanTx.Verdict?>(null) }
    val scope = rememberCoroutineScope()
    val source = CanTx.source
    T("Отправить кадр", 15.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FormField("ID (hex)", idText, Modifier.weight(0.6f), KeyboardType.Ascii, KeyboardCapitalization.Characters) { idText = it; result = null }
        FormField("Данные (hex): 01 02 0A FF", dataText, Modifier.weight(1.4f), KeyboardType.Ascii, KeyboardCapitalization.Characters) { dataText = it; result = null }
    }
    Labeled("ID") { Chip("11 бит", !ext, { ext = false }); Chip("29 бит", ext, { ext = true }) }
    Labeled("Кадр") { Chip("обычный (до 8 байт)", !fd, { fd = false }); Chip("CAN FD (до 64)", fd, { fd = true }) }
    val buses = source?.buses ?: 1
    if (buses > 1) Labeled("Шина") { (0 until buses).forEach { b -> Chip("$b", bus == b, { bus = b }) } }
    val frame = CanLinks.parseFrame(idText, ext, dataText, bus, fd)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(when {
            idText.isBlank() -> "Впишите ID и данные"
            frame == null -> "Неверный кадр: ID в hex (до 7FF или 1FFFFFFF), данные парами hex, до 8 байт (CAN FD до 64)"
            result != null -> "${frame}: ${result!!.text}"
            source == null -> "Сейчас нет CAN-сессии: кадр проверится, но отправлять некуда"
            else -> "Через ${source.title}: $frame"
        }, 13.sp, when { result?.ok == false || (frame == null && idText.isNotBlank()) -> C.Orange; result?.ok == true -> C.Green; else -> C.Sub },
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Chip("Отправить", frame != null, {
            // a socket must not be written from the main thread
            if (frame != null) scope.launch { result = withContext(Dispatchers.IO) { CanTx.send(frame) } }
        }, Lucide.Send)
    }
}
