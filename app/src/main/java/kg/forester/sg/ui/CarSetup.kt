package kg.forester.sg.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Car
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Usb
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.SsmTrace

private val adapters = listOf(
    "auto" to "Авто", "kkl" to "KKL-кабель", "op2" to "OpenPort 2.0",
    "elm_usb" to "ELM327 USB", "elm_bt" to "ELM327 Bluetooth", "elm_wifi" to "ELM327 Wi-Fi", "canable" to "CANable (Link)",
    "mt_bt" to "Multitronics Bluetooth",
)

private fun adapterNote(k: String) = when (k) {
    "auto" -> "Приложение само найдёт адаптер: USB (KKL, OpenPort 1.3/2.0, ELM327), Wi-Fi ELM327, к сети которого " +
        "подключена магнитола, или сопряжённый Bluetooth-адаптер. Если ЭБУ не отвечает по протоколу Subaru (SSM), " +
        "ELM327 и OpenPort 2.0 переходят на стандартный OBD-II: основные параметры и ошибки."
    "op2" -> "Tactrix OpenPort 2.0 или китайский клон. Умеет K-Line и CAN, а без SSM — стандартный OBD-II по CAN."
    "canable" -> "Экспериментально. USB-CAN адаптер CANable (прошивка slcan, как для RealDash) на шине для дашбордов спортивного ЭБУ: " +
        "Link G4+/G4X с потоком Dash2Pro или Generic Dash распознаётся сам, остальное добавляется своими значениями. Ничего не запрашивает, " +
        "только слушает. Коды ошибок Subaru, IAM и проверки по заводским таблицам при этом недоступны."
    "elm_usb" -> "ELM327 с USB-шнуром. SSM по CAN (SH 2009+); K-Line только у оригинальных ELM327 v1.4+. " +
        "Если SSM не отвечает — стандартный OBD-II."
    "elm_bt" -> "Bluetooth-свисток ELM327. SSM по CAN (SH 2009+); K-Line только у оригинальных ELM327 v1.4+, многие " +
        "клоны не умеют. Если SSM не отвечает — стандартный OBD-II. Данные обновляются примерно раз в секунду."
    "mt_bt" -> "Экспериментально. Бортовой компьютер Multitronics MPC-800 с Bluetooth: он сам читает машину по своему протоколу, " +
        "приложение берёт у него скорость, обороты, температуры, напряжение, расход и остальное, что он отдаёт (около 5 раз в секунду). " +
        "Коды ошибок, IAM и проверки по заводским таблицам при этом недоступны. Его собственное приложение в это время должно быть " +
        "отключено: Bluetooth-связь у коробки одна. В «Авто» коробка узнаётся сама, если сопряжённое устройство не ответило как ELM327."
    "elm_wifi" -> "Wi-Fi ELM327: подключите магнитолу к сети адаптера (WiFi_OBDII, V-LINK…), адрес почти всегда " +
        "192.168.0.10:35000. Если SSM не отвечает — стандартный OBD-II."
    else -> "Простой USB KKL (VAG-COM 409.1, CH340/FTDI), K+CAN Commander или Tactrix OpenPort 1.3. Только K-Line."
}
private val gearboxes = listOf("at" to "Автомат (АКПП)", "mt" to "Механика")
private val buses = listOf("auto" to "Авто", "kline" to "K-Line", "can" to "CAN")

/** Settings tab: the car (from its ECU), own name, gearbox, 3D car, diagnostic adapter and bus, UI scale, exchange log. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarAdapterSettings() {
    val s = Repo.settings
    Repo.carVersion.collectAsState().value // re-read the settings below when they change
    val msg by Repo.linkMessage.collectAsState()
    val init by Repo.ecuInit.collectAsState()
    val polled by Repo.pollAddresses.collectAsState()
    val obdMode by Repo.obdMode.collectAsState()
    val found by Repo.adapterFound.collectAsState()
    // another make (see CarKind): the Subaru-only parts below step aside
    val otherCar by kg.forester.sg.data.CarKind.other.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var wifiHost by remember { mutableStateOf(s.wifiHost) }
    var saved by remember { mutableStateOf("") }
    val scale by Repo.uiScale.collectAsState()
    var adapter by remember { mutableStateOf(s.adapter) }
    var bus by remember { mutableStateOf(s.bus) }
    var title by remember { mutableStateOf(if (s.carTitleCustom) s.carTitle else "") }
    var trace by remember { mutableStateOf(s.traceEnabled) }
    var gearbox by remember { mutableStateOf(s.gearbox) }
    var demo by remember { mutableStateOf(s.demo) }
    var awd by remember { mutableStateOf(s.awd) }
    var loggerProfile by remember { mutableStateOf(s.loggerProfile) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Car, "Автомобиль")
            Spacer(Modifier.height(10.dp))
            // the car is what its ECU says: model, year and engine by the ROM, nothing to pick here
            val demoOn by Repo.demoOn.collectAsState()
            val turboCar = Repo.guard.collectAsState().value.turboCar
            val engine = remember(init, demoOn, turboCar) { kg.forester.sg.ssm.EngineId.detect(ctx) }
            val (hTitle, hSub) = carHeader(engine, s.carTitle, s.carSub, s.carTitleCustom)
            val link = engine.source == "Link"
            T(when {
                otherCar -> rememberCarHeader().let { (t, sub) -> "$t · $sub" }
                engine.known -> "$hTitle · $hSub"
                link -> "ЭБУ Link: модель машины впишите ниже"
                else -> "Модель определится, когда ответит ЭБУ"
            }, 16.sp, weight = FontWeight.SemiBold)
            T(engineSource(engine), 11.sp, C.Sub)
            Spacer(Modifier.height(10.dp))
            TextBox(if (link) "Название в шапке, например WRX STI 2018" else "Своё название в шапке (необязательно)", title) {
                title = it; s.carTitle = it; s.carTitleCustom = it.isNotBlank()
            }
            T(if (link) "Пусто: в шапке SUBARU." else "Пусто: в шапке модель по ЭБУ.", 11.sp, C.Sub)
            Spacer(Modifier.height(14.dp))
            CarKindSettings()
            Spacer(Modifier.height(14.dp))
            T("Коробка", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gearboxes.forEach { (k, l) -> Chip(l, gearbox == k, { gearbox = k; s.gearbox = k }) }
            }
            if (gearbox == "at" && !otherCar) {
                Spacer(Modifier.height(10.dp))
                T("Полный привод АКПП (для оценки перед:зад)", 13.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("mpt" to "MP-T (перед, до 50:50)", "vtd" to "VTD (45:55)").forEach { (k, l) ->
                        Chip(l, awd == k, { awd = k; s.awd = k })
                    }
                }
                T("MP-T: большинство 4EAT (Forester SG, Impreza). VTD: 5EAT турбо Legacy/Outback и др.", 11.sp, C.Sub)
            }
            // the 3D cars are Subarus: another make gets a plain outline on the home screen
            if (!otherCar) {
                Spacer(Modifier.height(16.dp))
                T("3D-модель на главном экране", 15.sp)
                Spacer(Modifier.height(6.dp))
                val car3d by Repo.car3d.collectAsState()
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // many models now: wrap onto several lines instead of running off the panel
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CarModels3d.forEach { (k, l) -> Chip(l, car3d == k, { s.car3d = k; s.car3dCustom = true }) }
                }
                Spacer(Modifier.height(10.dp))
                T("Цвет кузова", 13.sp, C.Sub)
                Spacer(Modifier.height(6.dp))
                val paintSel by Repo.carPaint.collectAsState()
                val anyColour = car3dAnyColour(ctx, car3d)
                var picker by remember { mutableStateOf(false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CarPaints.forEach { cp ->
                        val on = paintSel == cp.id
                        val usable = anyColour || !cp.needsWhite
                        Box(
                            Modifier.size(34.dp).alpha(if (usable) 1f else 0.3f).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(cp.swatch)
                                .border(if (on) 3.dp else 1.dp, if (on) C.Violet else C.Border, androidx.compose.foundation.shape.CircleShape)
                                .clickable(enabled = usable) { s.carPaint = cp.id },
                        )
                    }
                    // any colour: rainbow swatch opens the picker
                    val custom = paintSel.startsWith("custom:")
                    Box(
                        Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                            .border(if (custom) 3.dp else 1.dp, if (custom) C.Violet else C.Border, androidx.compose.foundation.shape.CircleShape)
                            .clickable { picker = !picker },
                    )
                }
                T(carPaint(paintSel).name + if (!anyColour) " · у этой модели нет белого и серебра — выберите другую 3D-модель" else "", 11.sp, C.Sub)
                if (picker) {
                    Spacer(Modifier.height(8.dp))
                    ColourPicker(paintSel.removePrefix("custom:").toIntOrNull(16) ?: 0x2563EB) { rgb ->
                        s.carPaint = "custom:%06X".format(rgb and 0xFFFFFF); picker = false
                    }
                }
                if (car3dDir(ctx, car3d) == null) {
                    Spacer(Modifier.height(4.dp))
                    T("Модель $car3d ещё готовится, пока показывается SG5.", 11.sp, C.Orange)
                }
            }
            Spacer(Modifier.height(16.dp))
            T("Масштаб интерфейса", 15.sp)
            // a phone: from the whole layout on the screen up (MainActivity's ScaledDensity), text with it
            val phone = kg.forester.sg.isPhone(ctx)
            T(if (phone) "На телефоне «Авто» помещает весь экран магнитолы. Мелко: выберите больше, вырастут и шрифты. " +
                "Размер шрифта Android тоже учитывается"
                else "Если на вашей магнитоле цифры обрезаются или всё слишком крупное", 11.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (phone) listOf(0 to "Авто", 115 to "115%", 130 to "130%", 145 to "145%")
                else listOf(0 to "Авто", 80 to "80%", 90 to "90%", 100 to "100%", 110 to "110%", 120 to "120%")).forEach { (v, l) ->
                    Chip(l, scale == v || (phone && v == 0 && scale in 1..100), { s.uiScale = v })
                }
            }
            Spacer(Modifier.height(16.dp))
            T("Цвет интерфейса", 15.sp)
            T("Под подсветку приборов вашей машины, или любой свой. Кнопка с палитрой есть и в верхней строке", 11.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            ThemeChooser()
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.Usb, "Адаптер")
            Spacer(Modifier.height(10.dp))
            // the CarBerry 4.2 list is made for one ROM: nobody else gets the choice
            if (!otherCar && init?.romId.equals(kg.forester.sg.ssm.RomRaider.CARBERRY_ROM, ignoreCase = true)) {
                T("Профиль логгера SSM", 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("stock" to "Штатный", "carberry42" to "CarBerry 4.2").forEach { (key, label) ->
                        Chip(label, loggerProfile == key, { loggerProfile = key; s.loggerProfile = key })
                    }
                }
                T(if (loggerProfile == "carberry42") "SDlogger rev1.9: к штатным параметрам на экране «Датчики» добавятся каналы CarBerry. Выбор применяется во время опроса SSM."
                    else "CarBerry 4.2 (SDlogger rev1.9) добавляет к штатным параметрам свои каналы: детонация по цилиндрам, VE, этанол и др.", 12.sp, C.Sub)
                if (loggerProfile == "carberry42" && obdMode) T("В режиме OBD-II профиль SSM не применяется.", 12.sp, C.Orange)
                Spacer(Modifier.height(10.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                adapters.forEach { (k, l) -> Chip(l, adapter == k, { adapter = k; s.adapter = k }) }
            }
            Spacer(Modifier.height(6.dp))
            T(adapterNote(adapter), 12.sp, C.Sub)
            if (adapter == "elm_bt" || adapter == "mt_bt" || adapter == "auto") {
                Spacer(Modifier.height(10.dp))
                if (adapter == "auto") T("Bluetooth-адаптер (не обязательно: в «Авто» подойдёт любой сопряжённый OBD/ELM/V-LINK)", 12.sp, C.Sub)
                BluetoothPicker()
            }
            if (adapter == "elm_wifi" || adapter == "auto") {
                Spacer(Modifier.height(10.dp))
                TextBox("Адрес Wi-Fi адаптера (IP:порт)", wifiHost) { wifiHost = it; s.wifiHost = it.trim() }
            }
            Spacer(Modifier.height(14.dp))
            // the CANable's own settings: picked, or found on USB in «Авто» (a Subaru owner never sees them)
            if (adapter == "canable" || (adapter == "auto" && "CANable" in found)) CanablePanel()
            // the bus is the box's business
            if (adapter != "canable" && adapter != "mt_bt") {
                val multiBus = adapter != "kkl"
                T("Шина", 15.sp, if (multiBus) C.Text else C.Sub)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    buses.forEach { (k, l) ->
                        Chip(l, multiBus && bus == k, { if (multiBus) { bus = k; s.bus = k } })
                    }
                }
                Spacer(Modifier.height(6.dp))
                T(
                    if (multiBus) "«Авто»: сначала K-Line, если ЭБУ молчит, то CAN (Forester SH 2009+)."
                    else "KKL-кабель работает только по K-Line.",
                    12.sp, C.Sub,
                )
            }
            if (adapter == "op2" || adapter == "auto") {
                Spacer(Modifier.height(10.dp))
                var listen by remember { mutableStateOf(s.canListen) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Слушать CAN (OpenPort 2.0, экспериментально)", 15.sp)
                        T("Блоки сами рассылают по CAN обороты, скорость, температуры, передачу и круиз. Приложение берёт их без " +
                            "запросов: работает, даже когда диагностику занял ноутбук. Расшифровка по открытой базе comma.ai для " +
                            "машин примерно 2010–2018 года; на K-линии (SG и раньше) CAN нет. На некоторых машинах кадры " +
                            "с OBD-разъёма не видны: их отсекает блок кузова", 11.sp, C.Sub)
                    }
                    Switch(listen, { listen = it; s.canListen = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
                }
                if (listen) {
                    var monitor by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(6.dp))
                    Chip("Что идёт по CAN", false, { monitor = true }, Lucide.Activity)
                    if (monitor) CanMonitorDialog { monitor = false }
                }
            }
            Spacer(Modifier.height(14.dp))
            InfoLine("Связь", msg)
            if (found.isNotEmpty()) InfoLine("Адаптер", found)
            if (otherCar && init == null) InfoLine("Блок двигателя (OBD)", Repo.obdEcu.collectAsState().value.ifEmpty { "—" })
            else InfoLine("SSM ID / ROM ID", init?.let { "${it.ssmId} / ${it.romId}" } ?: "—")
            InfoLine("Параметров опрашивается", when {
                polled <= 0 -> "—"
                obdMode -> "$polled (OBD-II)"
                else -> "$polled (SSM)"
            })
            Spacer(Modifier.height(14.dp))
            // SSM only: a Link on the CANable has no RomRaider parameters
            if (adapter != "canable" && !otherCar) { EcuDefsBlock(); Spacer(Modifier.height(14.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Журнал обмена (для тестов)", 15.sp)
                    T("Последние минуты обмена с адаптером приложение помнит всегда: если что-то не работает, нажмите " +
                        "«Отправить журнал» (Telegram, почта…) или сохраните его в Загрузки. Переключатель дополнительно " +
                        "пишет всё в файл ${SsmTrace.path ?: "logs/ssm_trace.txt"}.", 11.sp, C.Sub)
                }
                Switch(trace, { trace = it; s.traceEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            fun header() = "Машина: «${s.carTitle}», коробка ${s.gearbox}; адаптер ${s.adapter}, " +
                "шина ${s.bus}; найден: ${found.ifEmpty { "—" }}; связь: $msg; ROM ${init?.romId ?: "—"}"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    ActionButton("Отправить журнал", true) {
                        runCatching { SsmTrace.share(ctx, header()) }.onFailure {
                            saved = SsmTrace.saveToDownloads(ctx, header())?.let { p -> "Отправить не вышло, сохранено: $p" } ?: "Не удалось"
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    ActionButton("Сохранить в Загрузки", true) {
                        saved = SsmTrace.saveToDownloads(ctx, header())?.let { p -> "Сохранено: $p" } ?: "Не удалось сохранить"
                    }
                }
            }
            if (saved.isNotEmpty()) { Spacer(Modifier.height(4.dp)); T(saved, 11.sp, C.Sub) }
            Spacer(Modifier.height(8.dp))
            ActionButton("Очистить журнал", true) { SsmTrace.clear(); saved = "" }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Демо-данные", 15.sp)
                    T("Показывает придуманную поездку без машины, чтобы посмотреть экраны. Поездки и расход не записываются.", 11.sp, C.Sub)
                }
                Switch(demo, { demo = it; s.demo = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            T("Адаптер и шина применяются при следующем подключении (переподключите кабель или подождите).", 11.sp, C.Sub)
        }
    }
}

/** First start: the gearbox and the adapter; the car itself comes from its ECU. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstRunSetup(onDone: () -> Unit) {
    val s = Repo.settings
    var adapter by remember { mutableStateOf(s.adapter) }
    var gearbox by remember { mutableStateOf(s.gearbox) }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6070A14))
            .clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Panel(Modifier.widthIn(max = 640.dp).padding(16.dp).verticalScroll(rememberScrollState()), padding = 22.dp) {
            T("Добро пожаловать", 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            T("Модель, год и мотор приложение узнает само у блока двигателя. Выберите только коробку и адаптер, потом это можно поменять в Настройках → «Авто и адаптер».", 13.sp, C.Sub)
            Spacer(Modifier.height(16.dp))
            T("Коробка", 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gearboxes.forEach { (k, l) -> Chip(l, gearbox == k, { gearbox = k }) }
            }
            Spacer(Modifier.height(14.dp))
            T("Адаптер", 15.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                adapters.forEach { (k, l) -> Chip(l, adapter == k, { adapter = k }) }
            }
            Spacer(Modifier.height(4.dp))
            T(if (adapter == "auto") "Не знаете, какой у вас адаптер, — оставьте «Авто»." else adapterNote(adapter), 12.sp, C.Sub)
            Spacer(Modifier.height(18.dp))
            ActionButton("Готово", true) {
                s.adapter = adapter
                s.gearbox = gearbox
                s.setupDone = true
                onDone()
            }
        }
    }
}

/** Paired Bluetooth devices to pick the OBD adapter from (pairing itself is done in the phone's settings). */
@OptIn(ExperimentalLayoutApi::class)
@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun BluetoothPicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = Repo.settings
    fun granted() = android.os.Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    var chosen by remember { mutableStateOf(s.btAddress) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok = it }
    if (!ok) {
        ActionButton("Разрешить Bluetooth", true) { ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }
        return
    }
    val bt = (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    val devices = runCatching { bt?.bondedDevices?.toList() }.getOrNull().orEmpty()
        .sortedByDescending { d -> listOf("OBD", "ELM", "V-LINK", "VLINK", "KONNWEI", "VGATE").any { (d.name ?: "").uppercase().contains(it) } }
    if (bt == null) { T("На устройстве нет Bluetooth", 12.sp, C.Orange); return }
    if (!bt.isEnabled) T("Bluetooth выключен", 12.sp, C.Orange)
    if (devices.isEmpty()) {
        T("Нет сопряжённых устройств. Сопрягите адаптер в настройках Bluetooth телефона (PIN обычно 1234 или 0000).", 12.sp, C.Sub)
        return
    }
    T("Адаптер", 13.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.forEach { d ->
            Chip(d.name ?: d.address, chosen == d.address, {
                chosen = d.address; s.btAddress = d.address; s.btName = d.name ?: d.address
            })
        }
    }
}

/** Hue / saturation / brightness sliders with a preview; [onChange] follows the sliders, [onApply] gets 0xRRGGBB. */
@Composable
fun ColourPicker(start: Int, onChange: ((Int) -> Unit)? = null, onApply: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start or 0xFF000000.toInt(), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))
    if (onChange != null) androidx.compose.runtime.LaunchedEffect(rgb) { onChange(rgb) }
    val sliderColors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
    )
    @Composable
    fun Track(brush: androidx.compose.ui.graphics.Brush, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
            androidx.compose.material3.Slider(value, set, valueRange = range, colors = sliderColors)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.CardHi).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        T("Оттенок", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(
            (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }), h, 0f..360f) { h = it }
        T("Насыщенность", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v))))),
            sat, 0f..1f) { sat = it }
        T("Яркость", 12.sp, C.Sub)
        Track(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(
            Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, 1f))))), v, 0f..1f) { v = it }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(rgb)).border(1.dp, C.Border, RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            T("#%06X".format(rgb and 0xFFFFFF), 14.sp, modifier = Modifier.weight(1f))
            ActionButton("Применить", true) { onApply(rgb) }
        }
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        T(k, 13.sp, C.Sub, modifier = Modifier.width(180.dp))
        Spacer(Modifier.width(8.dp))
        T(v, 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun TextBox(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { FieldLabel(label) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
