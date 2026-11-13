package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.composables.icons.lucide.*
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kg.forester.sg.data.EcuCaps
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val e by Repo.live.collectAsState()
    val current by Repo.logFile.collectAsState()
    var enabled by remember { mutableStateOf(Repo.settings.logEnabled) }
    var files by remember { mutableStateOf(listOf<File>()) }
    // read with the listing: a file's length is a call to the disk, not for the main thread at each recomposition
    var sizes by remember { mutableStateOf(emptyMap<String, Long>()) }
    val listScope = androidx.compose.runtime.rememberCoroutineScope()
    suspend fun listFiles() {
        val (list, lengths) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val l = File(ctx.getExternalFilesDir(null), "logs").listFiles()?.sortedByDescending { it.name } ?: emptyList()
            l to l.associate { it.name to it.length() }
        }
        files = list
        sizes = lengths
    }
    LaunchedEffect(Unit) {
        while (true) {
            listFiles()
            delay(5000)
        }
    }

    var viewing by remember { mutableStateOf<File?>(null) }
    viewing?.let { f -> LogViewer(f) { viewing = null }; return }
    Row(Modifier.fillMaxSize()) {
        // the current values beside it have the longer lines
        Panel(Modifier.width(if (LocalCar.current.small) 300.dp else 420.dp).fillMaxHeight()) {
            ListWithHead({
            PanelTitle(Lucide.FileText, "Запись лога")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Писать лог при работе двигателя", 15.sp, modifier = Modifier.weight(1f))
                Switch(enabled, {
                    enabled = it; Repo.settings.logEnabled = it
                }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(8.dp))
            T("Текущий файл: ${current ?: "—"}", 13.sp, C.Sub)
            T("Папка: Android/data/kg.forester.sg/files/logs", 13.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            var hours by remember { mutableStateOf(Repo.settings.logRetentionHours) }
            T("Хранить подробные логи:", 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(24, 72, 168).forEach { h ->
                    Chip(if (h == 168) "7 дн" else "${h / 24} сут", hours == h, { hours = h; Repo.settings.logRetentionHours = h })
                    Spacer(Modifier.width(6.dp))
                }
            }
            SettingHint("Старые файлы удаляются автоматически раз в час, папка не больше 200 МБ. Графики расхода хранятся 35 дней, поездки и заправки — всегда.")
            Spacer(Modifier.height(8.dp))
            var pick by remember { mutableStateOf(false) }
            Chip("Что писать в лог…", false, { pick = true })
            if (pick) LogColumnsDialog { pick = false }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.FolderOpen, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                T("Файлы", 15.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.weight(1f))
                if (files.isNotEmpty()) T("${files.size} шт · ${fileSize(sizes.values.sum())}", 12.sp, C.Sub)
            }
            Spacer(Modifier.height(6.dp))
            T(if (files.isEmpty()) "Пока нет: файл появится, когда заведётся двигатель." else "Нажмите на файл — откроются графики. Справа: скачать в «Загрузки», поделиться, удалить.", 12.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            }, roomyAt = 620.dp) {
                items(files.size) { i ->
                    val f = files[i]
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
                    Row(Modifier.fillMaxWidth().background(C.CardHi).clickable { viewing = f }.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val now = f.name == current
                        Icon(if (now) Lucide.CirclePlay else Lucide.FileSpreadsheet, null,
                            tint = if (now) C.Green else C.Sub, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        T(f.name, 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        T(fileSize(sizes[f.name] ?: 0L), 12.sp, C.Sub)
                        Spacer(Modifier.width(2.dp))
                        LogFileActions(f, small = true) { listScope.launch { listFiles() } }
                        Icon(Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            PanelTitle(Lucide.Activity, "Текущие значения")
            Spacer(Modifier.height(10.dp))
            val g by Repo.guard.collectAsState()
            val engine = listOf(
                LiveRow(Lucide.Gauge, "Обороты", e.rpm?.toString(), "об/мин"),
                LiveRow(Lucide.Car, "Скорость", e.speedKmh?.let { kg.forester.sg.data.DistanceUnits.speed(it) }?.toString(), kg.forester.sg.data.DistanceUnits.speedUnit),
                LiveRow(Lucide.Thermometer, "Темп. ОЖ", e.coolantC?.toString(), "°C"),
                LiveRow(Lucide.Wind, "Темп. впуска", e.intakeC?.toString(), "°C"),
                LiveRow(Lucide.BatteryCharging, "Напряжение", e.batteryV?.let { fmtF(it, 2) }, "В"),
                LiveRow(Lucide.Activity, "Дроссель", e.throttlePct?.let { fmtF(it) }, "%"),
                LiveRow(Lucide.Footprints, "Педаль газа", e.pedalPct?.let { fmtF(it, 0) }, "%"),
                LiveRow(Lucide.Weight, "Нагрузка", e.loadGRev?.let { fmtF(it, 2) }, "г/об"),
                LiveRow(Lucide.RotateCw, "AVCS", e.avcsDeg?.let { fmtF(it, 0) }, "°"),
            )
            val fuel = listOf(
                LiveRow(Lucide.AirVent, "MAF", e.mafGs?.let { fmtF(it, 2) }, "г/с"),
                LiveRow(Lucide.Syringe, "Впрыск", e.injPwMs?.let { fmtF(it, 2) }, "мс"),
                LiveRow(Lucide.Percent, "Загрузка форсунок", e.injDutyPct?.let { fmtF(it, 0) }, "%"),
                LiveRow(Lucide.Crosshair, "Коррекция A/F", e.afCorrPct?.let { fmtF(it) }, "%"),
                LiveRow(Lucide.ChartLine, "Обучение A/F", e.afLearnPct?.let { fmtF(it) }, "%"),
                LiveRow(Lucide.FlaskConical, "AFR", e.afr?.let { fmtF(it) }, ""),
            )
            val ignition = listOf(
                LiveRow(Lucide.Zap, "УОЗ", e.timingDeg?.let { fmtF(it) }, "°"),
                LiveRow(Lucide.Flame, "P23: поправка УОЗ (сумма, не откат)", e.kcaDeg?.let { fmtF(it) }, "°"),
                LiveRow(Lucide.Scale, "IAM", g.iam?.let { fmtF(it, 2) }, ""),
                LiveRow(Lucide.SquareActivity, "FBKC (откат)", g.fbkc?.let { fmtF(it) }, "°"),
                LiveRow(Lucide.Waves, "FLKC (обучение)", g.flkc?.let { fmtF(it) }, "°"),
            )
            val boost = listOf(
                LiveRow(Lucide.Mountain, "Атм. давление", e.atmKpa?.let { fmtF(it, 0) }, "кПа"),
                LiveRow(Lucide.Gauge, "Абс. давление", e.mapKpa?.let { fmtF(it, 0) }, "кПа"),
                LiveRow(Lucide.CircleArrowUp, "Наддув (отн.)", e.gaugeKpa?.let { fmtF(it, 0) }, "кПа"),
                LiveRow(Lucide.Target, "Целевой наддув", g.targetBoostKpa?.let { fmtF(it, 0) }, "кПа"),
                LiveRow(Lucide.Fan, "Wastegate", e.wastegatePct?.let { fmtF(it, 0) }, "%"),
            )
            Row(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveGroup(Lucide.Gauge, "Двигатель", C.Blue, engine)
                    LiveGroup(Lucide.Zap, "Зажигание и детонация", C.Orange, ignition)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveGroup(Lucide.Fuel, "Топливо", C.Green, fuel)
                    LiveGroup(Lucide.Fan, "Наддув", C.Violet, boost)
                }
            }
        }
    }
}

private class LiveRow(val icon: androidx.compose.ui.graphics.vector.ImageVector, val name: String, val value: String?, val unit: String)

/** One block of «Текущие значения»: a header with the group's icon, then the rows divided by thin lines. */
@Composable
private fun LiveGroup(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: androidx.compose.ui.graphics.Color, rows: List<LiveRow>) {
    Column(
        Modifier.fillMaxWidth().clip(Rounded).background(C.CardHi)
            .border(1.dp, C.Border, Rounded).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            T(title, 15.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.6f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(r.icon, null, tint = C.Sub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                FitText(r.name, LocalCar.current.secondary, Modifier.weight(1f), C.Sub)
                T(r.value ?: "—", 15.sp, if (r.value == null) C.Sub else C.Text, weight = androidx.compose.ui.text.font.FontWeight.Medium)
                if (r.unit.isNotEmpty() && r.value != null) {
                    Spacer(Modifier.width(4.dp))
                    T(r.unit, 12.sp, C.Sub)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    var tab by remember { mutableStateOf(0) }
    // Back from a tab goes to the first one, then out; it was out to Home at once
    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 }
    val tabsScroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(tab) { if (tab == 0) tabsScroll.animateScrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        // one line that scrolls: on a 7" screen two lines of tabs left a panel no room
        Row(Modifier.fillMaxWidth().horizontalScroll(tabsScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SETTINGS_TABS.forEachIndexed { i, t ->
                Chip(if (t.id == "contact") kg.forester.sg.data.I18n.pick(t.title, "Contact") else t.title, tab == i, { tab = i }, t.icon, Modifier.keepInView(tab == i))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f)) {
            when (SETTINGS_TABS[tab].id) {
                "look" -> AppearanceSettings()
                "voice" -> VoiceSettings()
                "car" -> CarAdapterSettings(onOpenLook = { tab = SETTINGS_TABS.indexOfFirst { it.id == "look" } })
                "features" -> FeatureSettings()
                "shortcuts" -> ShortcutSettings()
                "sensors" -> ExtraSensorSettings()
                "system" -> SystemSettings()
                else -> ContactSettings()
            }
        }
    }
}

/** A tab of the settings, in the order the driver looks for them. */
class SettingsTab(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

val SETTINGS_TABS = listOf(
    SettingsTab("look", "Внешний вид", Lucide.Palette),
    SettingsTab("voice", "Звук и голос", Lucide.Volume2),
    SettingsTab("car", "Машина и адаптер", Lucide.Car),
    SettingsTab("features", "Функции", Lucide.ToggleRight),
    SettingsTab("shortcuts", "Кнопки запуска", Lucide.LayoutGrid),
    SettingsTab("sensors", "Датчики и экраны", Lucide.Cpu),
    SettingsTab("system", "Система", Lucide.Cog),
    SettingsTab("contact", "Связь", Lucide.MessageCircle),
)

/** A heading inside a settings panel, with the room above it. */
@Composable
internal fun SettingsSection(title: String) {
    Spacer(Modifier.height(16.dp))
    val p = C.palette
    T(
        p.titled(title), 16.sp, C.titleColor(C.Violet), androidx.compose.ui.text.font.FontWeight.SemiBold,
        Modifier.fillMaxWidth().then(p.titleBand?.let { Modifier.background(it).padding(horizontal = 8.dp, vertical = 3.dp) } ?: Modifier),
        tracking = if (p.titleTracking > 0f) androidx.compose.ui.unit.TextUnit(p.titleTracking, androidx.compose.ui.unit.TextUnitType.Em) else androidx.compose.ui.unit.TextUnit.Unspecified,
    )
    Spacer(Modifier.height(8.dp))
}

/** «Единицы расстояния»: only what is shown changes, see [kg.forester.sg.data.DistanceUnits]. */
@Composable
private fun DistanceUnitChooser() {
    val s = kg.forester.sg.data.Repo.settings
    val miles = kg.forester.sg.data.DistanceUnits.miles
    T("Единицы расстояния", 15.sp)
    SettingHint("Скорость, пробег, запас хода и расход. Топливо в литрах, давление и температура не меняются. Логи всегда в километрах")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Километры", !miles, { s.distanceUnit = kg.forester.sg.data.DistanceUnits.KM })
        Chip("Мили", miles, { s.distanceUnit = kg.forester.sg.data.DistanceUnits.MI })
    }
}

/** «Внешний вид»: what the driver sees of the app. Left: the look; right: the screen's size and what is on it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings() {
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(460.dp).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.Palette, "Язык и тема")
            Spacer(Modifier.height(12.dp))
            LanguageRow()
            Spacer(Modifier.height(12.dp))
            T("Тема", 15.sp)
            SettingHint("Оформление всего приложения. Кнопка с палитрой есть и в верхней строке")
            Spacer(Modifier.height(6.dp))
            UiThemeChooser()
            Spacer(Modifier.height(14.dp))
            AccentSection()
            SettingsSection("Логотип и заставка")
            BrandSettings()
            SettingsSection("Машина на экране")
            CarLookSettings()
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.Monitor, "Экран")
            Spacer(Modifier.height(12.dp))
            ScreenSizeSettings()
            SettingsSection("Единицы")
            DistanceUnitChooser()
            SettingsSection("Меню и панели")
            var menu by remember { mutableStateOf(false) }
            T("Меню", 15.sp)
            SettingHint("Порядок экранов, что скрыть. Это же открывает долгое нажатие на меню")
            Spacer(Modifier.height(6.dp))
            Chip("Настроить меню", false, { menu = true }, Lucide.ListChecks)
            if (menu) MenuEditorDialog { menu = false }
            Spacer(Modifier.height(14.dp))
            val navPos by Repo.navPosition.collectAsState()
            T("Панель навигации", 15.sp)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("left" to "Слева", "right" to "Справа", "top" to "Сверху", "bottom" to "Снизу").forEach { (k, l) ->
                    Chip(l, navPos == k, { Repo.settings.navPosition = k })
                }
            }
            Spacer(Modifier.height(14.dp))
            MiniPlayerSetting()
            Spacer(Modifier.height(14.dp))
            // the bars are also edited by a long press on them; here without having to find that out
            var barEditor by remember { mutableStateOf("") }
            T("Верхняя и нижняя панели", 15.sp)
            SettingHint("Какие показатели выводить. Это же открывает долгое нажатие на панель")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Верхняя панель", false, { barEditor = "top" })
                Chip("Нижняя панель", false, { barEditor = "bottom" })
            }
            if (barEditor == "top") TopBarEditorDialog { barEditor = "" }
            if (barEditor == "bottom") BottomBarEditorDialog { barEditor = "" }
            Spacer(Modifier.height(14.dp))
            CarouselSettings()
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** What the fuel count goes by: its own section of «Машина и адаптер». */
@Composable
internal fun FuelSettings() {
    val s = Repo.settings
    var tank by remember { mutableStateOf(fmt(s.tankL, 0)) }
    var price by remember { mutableStateOf(fmt(s.pricePerL, 1)) }
    var currency by remember { mutableStateOf(s.currency) }
    var k by remember { mutableStateOf(fmt(s.fuelK, 3)) }
    var cc by remember { mutableStateOf(s.injectorCc.takeIf { it > 0 }?.toString() ?: "") }
    var dead by remember { mutableStateOf(s.injectorDeadMs.takeIf { it > 0f }?.let { fmt(it.toDouble(), 2) } ?: "") }
    var left by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Объём бака, л (0 — не учитывать)", tank) { tank = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field("Цена топлива за литр", price) { price = it } }
            Box(Modifier.width(120.dp)) {
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it.take(6) }, singleLine = true,
                    label = { FieldLabel("Валюта") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                        focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                    ),
                )
            }
        }
        Field("Коэффициент расхода (калибровка)", k) { k = it }
        // the fuel count without a MAF (an old ECU that doesn't give it) goes by the injection time
        Field(
            // 380/260 are the Subaru's own; another car's size the driver has to know
            if (kg.forester.sg.data.CarKind.other.collectAsState().value) "Форсунки, cc/мин (для расхода без MAF; впишите свои)"
            else "Форсунки, cc/мин (пусто — штатные 380 у турбо, 260 у атмо)", cc,
        ) { t -> cc = t.filter { it.isDigit() }.take(4) }
        // as in the Link's injector setup: the time at 14 V, the app follows the battery voltage by itself
        Field("Время открытия форсунок, мс (пусто — 0,6 при 14 В)", dead) { t -> dead = t.filter { it.isDigit() || it == '.' || it == ',' }.take(5) }
        Field("Сейчас в баке, л (если знаете точно)", left) { left = it }
    }
    Spacer(Modifier.height(14.dp))
    ActionButton("Сохранить", true) {
        tank.toD()?.let { s.tankL = it }
        price.toD()?.let { s.pricePerL = it }
        s.currency = currency
        k.toD()?.let { if (it in 0.7..1.4) s.fuelK = it }
        s.injectorCc = cc.toIntOrNull()?.takeIf { it in 100..3000 } ?: 0
        s.injectorDeadMs = dead.toD()?.toFloat()?.takeIf { it in kg.forester.sg.service.FuelCalc.DEAD_RANGE } ?: 0f
        left.toD()?.let { s.fuelAtRefillL = it; s.usedSinceRefillL = 0.0; left = "" }
        Repo.touch()
        saved = "Сохранено"
    }
    Spacer(Modifier.height(8.dp))
    T(saved, 13.sp, C.Green)
}

/** «Система»: how the app lives on the unit, its copy and version, the exchange log for reports. */
@Composable
private fun SystemSettings() {
    val s = Repo.settings
    val init by Repo.ecuInit.collectAsState()
    val msg by Repo.linkMessage.collectAsState()
    var caps by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(460.dp).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.Cog, "Система")
            Spacer(Modifier.height(12.dp))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var home by remember { mutableStateOf(isDefaultHome(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Главный экран магнитолы", 15.sp)
                    T("Открывать CarPulse кнопкой «Домой» вместо штатного лаунчера", 11.sp, C.Sub)
                }
                Switch(home, {
                    setDefaultHome(ctx, it); home = isDefaultHome(ctx)
                }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(14.dp))
            BackgroundSettings()
            Spacer(Modifier.height(14.dp))
            T("Мини-карта открывает", 15.sp)
            T("Приложение, которое запускается по нажатию на мини-карту", 11.sp, C.Sub)
            Spacer(Modifier.height(6.dp))
            MapAppChooser()
            Spacer(Modifier.height(14.dp))
            BackupSettings()
        }
        Spacer(Modifier.width(12.dp))
        // scrolls like the left one: at a large UI scale «Проверить обновления» is below the edge
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            UpdateSettings()
            SettingsSection("Журнал обмена")
            ExchangeLogSettings()
            SettingsSection("Автомобиль и ЭБУ")
            val (carT, carS) = rememberCarHeader()
            InfoRow("Автомобиль", "$carT · $carS")
            if (kg.forester.sg.data.CarKind.other.collectAsState().value && init == null) {
                // another car has no SSM ids: what OBD-II mode 09 told instead
                InfoRow("VIN", Repo.vin.collectAsState().value.ifEmpty { "—" })
                InfoRow("Блок двигателя (OBD)", Repo.obdEcu.collectAsState().value.ifEmpty { "—" })
            } else {
                InfoRow("SSM ID", init?.ssmId ?: "—")
                InfoRow("ROM ID", init?.romId ?: "—")
            }
            if (init != null && EcuCaps.hidden(LocalContext.current).isNotEmpty()) {
                Chip("ЭБУ скрывает датчики? (старые JECS)", false, { caps = true }, Lucide.EyeOff)
                if (caps) EcuCapsDialog { caps = false }
            }
            InfoRow("Связь", msg)
            InfoRow("Коррекция спидометра (GPS)", fmt(s.speedK, 3))
            InfoRow("Коэффициент расхода", fmt(s.fuelK, 3))
            Spacer(Modifier.height(14.dp))
            T(
                "Расход считается по MAF с учётом топливных коррекций. Коэффициент уточняется автоматически " +
                    "при заправках до полного бака. Если ЭБУ не передаёт уровень топлива, остаток считается от последней заправки.",
                13.sp, C.Sub,
            )
        }
    }
}

/** Where the mini player goes, or none. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MiniPlayerSetting() {
    val where by Repo.miniPlayer.collectAsState()
    T("Мини-плеер", 15.sp)
    SettingHint("Что играет и кнопки на всех экранах, кроме «Медиа»")
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("bottom" to "Снизу", "top" to "Сверху", "off" to "Выключен").forEach { (k, l) ->
            Chip(l, where == k, { Repo.settings.miniPlayer = k })
        }
    }
}

/** Check GitHub for a newer release, show what changed, download and install it over this one. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UpdateSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = remember { kg.forester.sg.data.Updater.current(ctx) }
    var status by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<kg.forester.sg.data.Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<java.io.File?>(null) }
    T("Обновление приложения", 16.sp)
    Spacer(Modifier.height(6.dp))
    InfoRow("Установлена версия", current)
    Spacer(Modifier.height(6.dp))
    // wraps: side by side they didn't fit the panel with a bigger system font, and the button was cut off
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Проверить обновления", false, {
            status = "Проверяю…"; release = null; apk = null
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kg.forester.sg.data.Updater.latest() }
                release = r?.takeIf { kg.forester.sg.data.Updater.isNewer(it.tag, current) }
                status = when {
                    r == null -> "Не удалось проверить: нет интернета или GitHub недоступен"
                    // the installed one, not the release found: a build newer than the last release says so
                    release == null && kg.forester.sg.data.Updater.isNewer(current, r.tag) ->
                        "У вас $current, новее опубликованной (${r.tag.removePrefix("v")})"
                    release == null -> "У вас последняя версия ($current)"
                    else -> "Доступна версия ${r.tag.removePrefix("v")}"
                }
            }
        })
        release?.let { r ->
            Chip(if (progress != null) "Скачиваю…" else "Скачать и установить", false, {
                if (progress != null) return@Chip
                apk?.let { if (kg.forester.sg.data.Updater.install(ctx, it)) return@Chip }
                progress = 0f
                scope.launch {
                    val f = runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            kg.forester.sg.data.Updater.download(ctx, r) { p -> progress = p }
                        }
                    }
                    progress = null
                    f.onSuccess { file ->
                        apk = file
                        status = if (kg.forester.sg.data.Updater.install(ctx, file))
                            "Установщик открыт: подтвердите обновление. Если он пишет «конфликт пакетов» — у вас версия с другой " +
                                "подписью (0.8 или собранная самостоятельно): сохраните резервную копию (слева), удалите приложение и поставьте заново"
                        else "Разрешите установку из этого приложения и нажмите «Скачать и установить» ещё раз"
                    }.onFailure { kg.forester.sg.ssm.SsmTrace.log("update: $it"); status = "Не скачалось: ${kg.forester.sg.data.Failure.text(it)}" }
                }
            })
        }
    }
    progress?.let {
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = C.Purple)
    }
    if (status.isNotEmpty()) { Spacer(Modifier.height(6.dp)); T(status, 13.sp, if (status.startsWith("Не ")) C.Orange else if (release != null) C.Green else C.Sub) }
    release?.let { r ->
        Spacer(Modifier.height(6.dp))
        T(r.name, 13.sp)
        T(r.notes.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "").replace("**", "").trim().take(700), 11.sp, C.Sub, maxLines = 12)
    }
    Spacer(Modifier.height(4.dp))
    SettingHint("Данные сохраняются: версии подписаны одним ключом. Приложение раз в день само проверяет, нет ли новой версии.")
}

/** Manual backup to a file the user picks, restore from one, and the daily automatic copy. */
@Composable
private fun BackupSettings() {
    val s = Repo.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var auto by remember { mutableStateOf(s.autoBackup) }
    var msg by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        failed = false
        msg = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { kg.forester.sg.data.Backup.write(ctx, it) }
            "Копия сохранена"
        }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("backup: $it"); failed = true; "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> restoreUri = uri }

    T("Резервная копия", 15.sp)
    SettingHint("Поездки, заправки, калибровка, раскладки экранов, свои дизайны и настройки в одном файле.")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Сохранить копию…", false, { save.launch(kg.forester.sg.data.Backup.suggestedName()) })
        Chip("Восстановить из файла…", false, { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
    }
    if (msg.isNotEmpty()) T(msg, 12.sp, if (failed) C.Orange else C.Green)
    SwitchRow("Автокопия раз в сутки", "В папку Download/CarPulse, хранятся последние 7", auto, status = "Последняя: ${kg.forester.sg.data.Backup.lastAutoText()}") {
        auto = it; s.autoBackup = it
    }
    restoreUri?.let { uri ->
        CarAlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Восстановить из копии?") },
            text = { Text("Текущие поездки, заправки и настройки заменятся данными из файла. Приложение перезапустится.") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    restoreUri = null
                    failed = false
                    msg = runCatching {
                        ctx.contentResolver.openInputStream(uri)!!.use { kg.forester.sg.data.Backup.restore(ctx, it) } ?: ""
                    }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("restore: $it"); failed = true; "Не удалось восстановить: ${kg.forester.sg.data.Failure.text(it)}" }
                }) { Text("Восстановить") }
            },
            dismissButton = { androidx.compose.material3.TextButton({ restoreUri = null }) { Text("Отмена") } },
        )
    }
}

/** Background work and autostart, with the system permissions they depend on. */
@Composable
private fun BackgroundSettings() {
    val s = Repo.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bg by remember { mutableStateOf(s.backgroundWork) }
    var auto by remember { mutableStateOf(s.autostartService) }
    var app by remember { mutableStateOf(s.autostartApp) }
    var music by remember { mutableStateOf(s.musicOnStart) }
    // re-read the system permissions when coming back from the system settings screens
    var resumed by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val overlay = remember(resumed) { android.provider.Settings.canDrawOverlays(ctx) }
    val unrestricted = remember(resumed) {
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    T("Фон и автозапуск", 15.sp)
    Spacer(Modifier.height(6.dp))
    SwitchRow("Работать в фоне", "Опрос ЭБУ, расход, поездки, логи и голосовой ассистент продолжаются, когда приложение свёрнуто или открыто другое", bg) {
        bg = it; s.backgroundWork = it
    }
    SwitchRow("Запускать при включении", "Служба стартует при включении магнитолы или телефона и после сна (ACC)", auto) {
        auto = it; s.autostartService = it
    }
    SwitchRow("Открывать приложение при включении", "Показывать приборы сразу после включения", app) {
        app = it; s.autostartApp = it
    }
    SwitchRow("Включать музыку при запуске", "При включении магнитолы и при открытии приложения играет то, что выбрано в Медиа: USB, Bluetooth, радио или плеер", music) {
        music = it; s.musicOnStart = it
    }
    if (app && !overlay) {
        T("Для автооткрытия Android требует разрешение «Поверх других приложений».", 12.sp, C.Orange)
        Spacer(Modifier.height(4.dp))
        Chip("Разрешить", false, {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
            )
        })
        Spacer(Modifier.height(8.dp))
    }
    if ((auto || app) && kg.forester.sg.service.Fyt.present(ctx)) {
        // FYT firmware (Teyes and others) stops third-party apps when it goes to sleep: nothing wakes them but the
        // driver, the launcher, or the firmware's own autostart list
        SettingHint("Если после сна магнитолы приложение не запускается само: в настройках магнитолы «Батарея» → «Управление " +
            "автозапуском» включите для CarPulse «автозапуск» и «вторичный запуск», а «Закрыть приложение после " +
            "блокировки экрана» выключите. Самый надёжный вариант — включить выше «Главный экран магнитолы»: " +
            "после сна магнитола всегда открывает главный экран.")
        Spacer(Modifier.height(8.dp))
    }
    if (bg && !unrestricted) {
        SettingHint("Система может останавливать приложение в фоне для экономии батареи (актуально для телефонов).")
        Spacer(Modifier.height(4.dp))
        Chip("Не ограничивать", false, {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
                )
            }
        })
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        T(k, 14.sp, C.Sub, modifier = Modifier.weight(1f))
        T(v, 14.sp)
    }
}

@Composable
private fun Field(label: String, value: String, text: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { FieldLabel(label) },
        // numbers by default; the plate needs letters (capitals, as on a real plate)
        keyboardOptions = if (text) KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters)
        else KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.toD(): Double? = replace(',', '.').trim().toDoubleOrNull()

/** Licence plate shown on the 3D car: text or an uploaded picture (e.g. with your country's flag). */
@Composable
internal fun PlateSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val plate by kg.forester.sg.data.Plate.bitmap.collectAsState()
    LaunchedEffect(Unit) { if (plate == null) kg.forester.sg.data.Plate.load(ctx) }
    var text by remember { mutableStateOf(kg.forester.sg.data.Plate.text(ctx)) }
    var msg by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) msg = if (kg.forester.sg.data.Plate.setImage(ctx, uri)) "Картинка номера загружена" else "Не удалось открыть картинку" }

    T("Номер на 3D-модели", 15.sp)
    Spacer(Modifier.height(6.dp))
    plate?.let {
        androidx.compose.foundation.Image(
            it.asImageBitmap(), "номер",
            Modifier.fillMaxWidth().height(70.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
    Spacer(Modifier.height(6.dp))
    Field("Текст номера", text, text = true) { text = it }
    SettingHint("Номер с флагом или регионом можно загрузить картинкой (фото или скан своего номера).")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { ActionButton("Применить текст", text.isNotBlank()) { kg.forester.sg.data.Plate.setText(ctx, text); msg = "Номер обновлён" } }
        Box(Modifier.weight(1f)) { ActionButton("Загрузить картинку", true) { picker.launch("image/*") } }
    }
    if (msg.isNotEmpty()) T(msg, 12.sp, C.Green)
}

/** Every optional feature can be switched off by whoever does not need it. */
@Composable
private fun FeatureSettings() {
    val s = Repo.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Repo.featureVersion.collectAsState().value
    @Composable
    fun F(id: String, title: String, sub: String) = SwitchRow(title, sub, s.feature(id)) { s.setFeature(id, it) }
    var shift by remember { mutableStateOf(s.shiftLight) }
    var rpm by remember { mutableStateOf(s.shiftRpm.toString()) }
    var tpms by remember { mutableStateOf(s.tpms) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.HeartPulse, "Защита мотора и предупреждения")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                F("knock", "IAM и детонация", "Следить за IAM, FBKC, FLKC (читаются дополнительно, если есть в прошивке) и предупреждать о плохом бензине. После сброса ЭБУ и на малой нагрузке не пугает зря")
                F("turbo", "Турботаймер", "Сколько подождать на холостых перед выключением после нагрузки (только турбо)")
                F("warmup", "Прогрев", "Предупреждать о высоких оборотах, полном газе или наддуве на холодном масле")
                F("boost", "Наддув", "Перенаддув, отсечка топлива, недодув против обычного для машины, бедная смесь под наддувом (только турбо)")
                F("mixture", "Смесь и коррекции", "Подсос воздуха, богатая или бедная смесь, датчик состава смеси не работает, форсунки на пределе")
                F("idle", "Холостой ход", "Плавающие, высокие или низкие обороты прогретого мотора, AVCS не возвращается, слабое разрежение")
                F("cooling", "Охлаждение", "Горячо от 102°, перегрев от 105°, горячо на скорости, не включаются вентиляторы, резкий рост ОЖ, термостат")
                F("atf", "АКПП: ATF и гидротрансформатор", "Перегрев ATF, странный датчик, проскальзывание блокировки, аварийный режим на третьей")
                F("awd", "Полный привод и шины", "Передние и задние колёса крутятся с разной скоростью (разный износ или давление)")
                F("battAlert", "Аккумулятор", "Разряжен на стоянке (ниже 12,2 В), слабый пуск (ниже 9,6 В), долго не восстанавливается после пуска")
                F("altAlert", "Генератор", "Не заряжает (ниже 12,6 В, или с пуска ни разу не было 13,6 В) или перезаряд (выше 15 В). 12,5–13 В на ходу у Subaru 2001–2008 норма: умная зарядка")
                F("service", "Напоминания о ТО", "Раз в день при поездке, если подошёл срок по пробегу или дате")
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PanelTitle(Lucide.Timer, "Замеры, датчики, экраны")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                F("perf", "Замеры разгона и круги", "Экран «Замеры»: разгон, диностенд, секундомер кругов")
                F("motion", "G-метр и крен", "Перегрузки и наклон (акселерометр магнитолы, VDC или GPS)")
                F("sensors", "Экран «Датчики»", "Все параметры ЭБУ из базы RomRaider")
                F("health", "Экран «Мониторинг»", "Мотор, ТО, шины и журнал предупреждений (сами предупреждения настраиваются слева)")
                F("mine", "Экран «Мой экран»", "Свой экран из любых карточек; выключите, если хватает главного")
                // SSM only (the fast poll, the transmission unit): another make has neither
                if (!kg.forester.sg.data.CarKind.other.collectAsState().value) TcuRateSetting()
                F("minmax", "Мин/макс за поездку на виджетах", "Справа от значения на карточках и метками на полосах и шкалах тахометра/спидометра")
                if (s.feature("minmax")) Box(Modifier.fillMaxWidth().clip(Rounded).background(C.CardHi).padding(10.dp)) { MinMaxLegend() }
                F("update", "Проверять обновления", "Раз в день смотреть, нет ли новой версии на GitHub, и сообщить")
                SwitchRow("Давление в шинах (TPMS)", "Слушать Bluetooth-датчики давления. Разрешение и порог — в «Мониторинг» → «Шины»", tpms) {
                    tpms = it; s.tpms = it
                    if (it) kg.forester.sg.service.Tpms.start(ctx) else kg.forester.sg.service.Tpms.stop(ctx)
                }
                SwitchRow("Лампа переключения передач", "Края экрана мигают, когда обороты выше порога", shift) { shift = it; s.shiftLight = it }
                if (shift) NumberField("Порог, об/мин (EJ20 турбо ~6300, EJ25 атмо ~5800)", rpm) {
                    rpm = it; it.toIntOrNull()?.takeIf { v -> v in 2000..9000 }?.let { v -> s.shiftRpm = v }
                }
            }
        }
    }
}

/**
 * The top of «Звук и голос»: the buttons' click, the player's sound, the voice of the warnings. Each takes effect at once.
 */
@Composable
internal fun SoundSettings() {
    val s = Repo.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sound by remember { mutableStateOf(s.buttonSound) }
    var voice by remember { mutableStateOf(s.voiceAlerts) }
    var eq by remember { mutableStateOf(false) }
    PanelTitle(Lucide.Volume2, "Звук")
    Spacer(Modifier.height(8.dp))
    SwitchRow("Звук кнопок", "Щелчок при нажатии кнопок, музыка не приглушается", sound) { sound = it; s.buttonSound = it }
    Spacer(Modifier.height(10.dp))
    var perfShown by remember { mutableStateOf(s.perfResults) }
    SwitchRow("Показывать результаты замеров", "Сообщение и голос после разгона и торможения, пойманных сами собой. Выключено: они только на экране «Замеры»", perfShown) {
        perfShown = it; s.perfResults = it
    }
    Spacer(Modifier.height(10.dp))
    Chip("Звук плеера (эквалайзер)", false, { eq = true }, Lucide.SlidersHorizontal)
    if (eq) SoundDialog { eq = false }
    Spacer(Modifier.height(10.dp))
    val speech by kg.forester.sg.service.Speech.status.collectAsState()
    SwitchRow("Голосовые предупреждения",
        "Встроенный русский голос: скачивается один раз при включении (" +
            (kg.forester.sg.service.Speech.current.mb.takeIf { it > 0 }?.let { "$it МБ" } ?: "у голоса Android ничего") +
            "), дальше работает без интернета. " +
            "Пока его нет, говорит голос магнитолы или звучит короткий сигнал.", voice,
        status = kg.forester.sg.service.Speech.shown(voice, speech), statusColor = speechColor()) {
        voice = it; s.voiceAlerts = it
        if (it) kg.forester.sg.service.Speech.prepare(ctx)
    }
    if (voice) {
        SpeechVoicePicker()
        var loud by remember { mutableStateOf(s.voiceLoud) }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Громкость:", 13.sp, C.Sub, modifier = Modifier.align(Alignment.CenterVertically))
            listOf(0 to "Обычная", 1 to "Громче", 2 to "Максимум").forEach { (v, l) ->
                Chip(l, loud == v, {
                    loud = v; s.voiceLoud = v
                    kg.forester.sg.service.Speech.say("Так звучат предупреждения", preview = true)
                })
            }
        }
        SettingHint("На максимуме музыка встаёт на паузу, пока голос говорит. Общую громкость голоса задаёт громкость навигации магнитолы.")
    }
}

/** Which columns the CSV log gets; takes effect at once (a new file is started). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LogColumnsDialog(onClose: () -> Unit) {
    var off by remember { mutableStateOf(Repo.settings.logOff) }
    val groups = kg.forester.sg.service.LogColumns.offered().groupBy { it.group }
    CarAlertDialog(
        onDismissRequest = onClose, textScrolls = false,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.FileText, null, tint = C.Violet, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                T("Что писать в лог", 20.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        },
        text = {
            Column(Modifier.cueScroll(C.DialogHi), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingHint("Время пишется всегда. Отмеченные параметры ЭБУ с экрана «Датчики» добавляются в конец. Новый выбор начинает новый файл.")
                groups.forEach { (g, cols) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (g) {
                            "Двигатель" -> Lucide.Gauge
                            "Топливо" -> Lucide.Fuel
                            "Зажигание и детонация" -> Lucide.Zap
                            "Наддув" -> Lucide.Fan
                            "GPS" -> Lucide.Satellite
                            "АКПП" -> Lucide.Cog
                            else -> Lucide.Droplets
                        }
                        Icon(icon, null, tint = C.Violet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        T(g, 15.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.weight(1f))
                        val allOn = cols.none { it.key in off }
                        T(if (allOn) "убрать все" else "все", 12.sp, C.Violet, modifier = Modifier.clickable {
                            off = if (allOn) off + cols.map { it.key } else off - cols.map { it.key }.toSet()
                            Repo.settings.logOff = off
                        })
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cols.forEach { c ->
                            val on = c.key !in off
                            Chip(c.title, on, { off = if (on) off + c.key else off - c.key; Repo.settings.logOff = off },
                                icon = if (on) Lucide.Check else Lucide.Plus)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text("Готово", color = C.Violet) } },
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
    )
}

/** How often the transmission unit is read, against the engine: the K-line is slow, the engine data matter more. */
@Composable
private fun TcuRateSetting() {
    val s = Repo.settings
    var every by remember { mutableStateOf(s.tcuEvery) }
    var atf by remember { mutableStateOf(s.tcuAtfOnly) }
    var fast by remember { mutableStateOf(s.fastPoll) }
    Column {
        SwitchRow("Быстрый опрос (K-Line)", "ЭБУ сам шлёт данные без запросов, как fast poll в RomRaider: в 3 раза чаще. " +
            "Если ЭБУ так не умеет, приложение само вернётся к обычному опросу", fast) {
            fast = it; s.fastPoll = it
        }
        Spacer(Modifier.height(10.dp))
        T("Опрос АКПП", 15.sp)
        SettingHint("Каждый N-й опрос двигателя (при быстром опросе: раз в N × 0,25 с). Реже — быстрее обновляются данные ЭБУ (K-Line медленная).")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3 to "каждый 3-й", 10 to "10-й", 30 to "30-й").forEach { (n, l) -> Chip(l, every == n, { every = n; s.tcuEvery = n }) }
        }
        Spacer(Modifier.height(6.dp))
        SwitchRow("Только температура ATF", "Читать с АКПП лишь ATF и селектор: запрос короче, двигатель опрашивается чаще", atf) {
            atf = it; s.tcuAtfOnly = it
        }
    }
}

/**
 * «Карусель»: the screens a sideways swipe goes through, so a driver without buttons on the wheel can flick from
 * the dashboard to the music and back instead of aiming at the menu. Up to four, in the order picked.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CarouselSettings() {
    val list by Repo.carousel.collectAsState()
    T("Карусель экранов", 15.sp)
    SettingHint("Свайп влево или вправо по экрану листает выбранные экраны по кругу. Меню слева остаётся. " +
        "Отметьте от двух до четырёх экранов в нужном порядке; ничего не отмечено, карусель выключена")
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Выключена", list.size < 2, { Repo.settings.carousel = emptyList() })
        kg.forester.sg.Screen.entries.filter { it != kg.forester.sg.Screen.Settings }.forEach { sc ->
            val i = list.indexOf(sc.name)
            Chip(if (i >= 0) "${i + 1}. ${sc.title}" else sc.title, i >= 0, {
                Repo.settings.carousel = if (i >= 0) list - sc.name else if (list.size < 4) list + sc.name else list
            }, sc.icon)
        }
    }
    if (list.size == 1) T("Отметьте ещё хотя бы один экран", 11.sp, C.Orange)
}
