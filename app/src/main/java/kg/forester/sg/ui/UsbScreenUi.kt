package kg.forester.sg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kg.forester.sg.data.SCREEN_FIRMWARE
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.data.UsbScreen
import kg.forester.sg.flash.EspFlasher
import kg.forester.sg.flash.FlashState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Настройки → «Датчики и экраны»: an ESP32-S3 board with an AMOLED or IPS panel on its own USB. Left: which board, its
 * firmware, how it is turned and lit. Right: the pages as the screen shows them (drawn by the same code that feeds
 * the board), what is on each and in which gauge design.
 */

@Composable
fun UsbScreenSettings() {
    val screens by UsbScreen.screens.collectAsState()
    val selId by UsbScreen.selected.collectAsState()
    val id = selId.takeIf { it in screens } ?: screens.keys.first()
    val cfg = screens.getValue(id)
    var sel by remember(id) { mutableStateOf(0) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val leftW = if (maxWidth >= 1050.dp) 420.dp else 360.dp
        Row(Modifier.fillMaxSize()) {
            Panel(Modifier.width(leftW).fillMaxHeight().verticalScroll(rememberScrollState())) {
                ScreensList(id)
                Spacer(Modifier.height(10.dp))
                ScreenHeader(id)
                Spacer(Modifier.height(10.dp))
                ModelPicker(id)
                Spacer(Modifier.height(10.dp))
                ScreenFlash(id)
                Spacer(Modifier.height(10.dp))
                ScreenLook(id)
            }
            Spacer(Modifier.width(12.dp))
            Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                val i = sel.coerceIn(0, cfg.pages.size - 1)
                PagesBar(id, i) { sel = it }
                Spacer(Modifier.height(10.dp))
                PagePreview(id, cfg.pages[i])
                Spacer(Modifier.height(12.dp))
                PageEditor(id, cfg.pages[i]) { sel = (sel - 1).coerceAtLeast(0) }
            }
        }
    }
}

/**
 * Every screen known: on line or not, each by its name; a tap picks the one the settings below are for. Two or three
 * can be on at once (a USB hub), each with its own pages.
 */
@Composable
private fun ScreensList(sel: String) {
    val screens by UsbScreen.screens.collectAsState()
    val online by UsbScreen.online.collectAsState()
    T("Экраны", 16.sp, weight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        (screens.keys + online.keys).distinct().forEach { id ->
            val on = id in online
            val picked = id == sel
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (picked) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                    .border(1.dp, if (picked) C.Purple else C.Border, RoundedCornerShape(10.dp)).clickable { UsbScreen.selected.value = id }
                    .heightIn(min = 50.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) C.Green else C.Gray))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(UsbScreen.title(id), 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T(when {
                        id.isEmpty() -> "ещё не подключали: настройки подхватит первый новый экран"
                        on -> "на связи"
                        else -> "не подключён"
                    }, 12.sp, if (on) C.Green else C.Sub, maxLines = 2)
                }
                if (!on && id.isNotEmpty()) IconTap(Lucide.Trash2, "Забыть экран", {
                    val keep = UsbScreen.cfgOf(id)
                    UsbScreen.forget(id)
                    offerUndo("Экран «${keep.name.ifEmpty { ScreenModel.of(keep.model)?.title ?: "экран" }}» забыт") { UsbScreen.put(id, keep) }
                })
            }
        }
        if ("" !in screens) AddRow("Ещё экран", "настроить заранее, до подключения", false) { UsbScreen.addTemplate() }
    }
}

/** Is this screen there, which board, what firmware, how fast it draws; its name. */
@Composable
private fun ScreenHeader(id: String) {
    val online by UsbScreen.online.collectAsState()
    val status by UsbScreen.status.collectAsState()
    val fpsAll by UsbScreen.fps.collectAsState()
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    val info = online[id]
    val fps = fpsAll[id] ?: 0f
    val (icon, color, text) = when {
        info != null -> Triple(Lucide.CircleCheck, C.Green,
            "На связи · ${ScreenModel.of(info.board)?.title ?: info.board} · прошивка ${info.fw} · ${"%.0f".format(fps)} кадр/с")
        id.isEmpty() -> Triple(Lucide.Usb, C.Violet, "Подключите экран: эти настройки станут его")
        online.isEmpty() -> Triple(Lucide.Usb, C.Orange, status.ifEmpty { "Ищу экран на USB…" })
        else -> Triple(Lucide.Unplug, C.Orange, "Этот экран не подключён (на связи: ${online.size})")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        T(text, 14.sp, C.Text, FontWeight.SemiBold, maxLines = 3, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    var name by remember(id) { mutableStateOf(cfg.name) }
    androidx.compose.material3.OutlinedTextField(
        value = name, onValueChange = { name = it; UsbScreen.put(id, UsbScreen.cfgOf(id).copy(name = it)) }, singleLine = true,
        label = { FieldLabel("Где стоит (стойка, торпедо…)") }, modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
    )
    info?.let { i ->
        if (cfg.model.isNotEmpty() && i.board != cfg.model) Hint(Lucide.TriangleAlert,
            "На USB ${ScreenModel.of(i.board)?.title ?: i.board}, а выбрана ${ScreenModel.of(cfg.model)?.title}. Рисую под ту, что ответила", C.Orange)
        if (i.fw < SCREEN_FIRMWARE) Hint(Lucide.TriangleAlert, "На экране прошивка ${i.fw}, в приложении $SCREEN_FIRMWARE: обновите ниже", C.Orange)
    }
    Hint(Lucide.Info, "Экран идёт отдельным USB-устройством, рядом с кабелем ЭБУ и платой датчиков. Портов мало: возьмите USB-хаб с питанием.")
}

@Composable
private fun ModelPicker(id: String) {
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    var open by remember { mutableStateOf(cfg.model.isEmpty()) }
    val m = ScreenModel.of(cfg.model)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Плата экрана", 16.sp, weight = FontWeight.SemiBold)
            T(m?.title ?: "не выбрана", 13.sp, if (m == null) C.Orange else C.Sub, maxLines = 2)
        }
        Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, tint = C.Sub, modifier = Modifier.size(20.dp))
    }
    m?.note?.takeIf { it.isNotEmpty() }?.let { Hint(Lucide.Info, it) }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenModel.entries.forEach { s ->
            val on = cfg.model == s.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.Purple.copy(alpha = 0.22f) else C.CardHi)
                    .border(1.dp, if (on) C.activeEdge(C.Purple) else C.Border, RoundedCornerShape(10.dp))
                    .clickable {
                        // a tall panel lies on its side in a car: start turned
                        UsbScreen.put(id, cfg.copy(model = s.id, rotation = if (s.h > s.w * 1.4f) 1 else 0))
                        open = false
                    }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the panel's shape, to scale
                val k = 34f / maxOf(s.w, s.h)
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size((s.w * k).dp, (s.h * k).dp).clip(if (s.round) CircleShape else RoundedCornerShape(3.dp))
                        .background(if (s.amoled) Color(0xFF05070D) else Color(0xFF12203A)).border(1.5.dp, C.Violet, if (s.round) CircleShape else RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(s.title, 14.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    T(s.sub, 12.sp, C.Sub, maxLines = 2)
                }
            }
        }
    }
}

/** «Прошить экран»: the firmware for the chosen board, or a .bin of one's own, with what happened. */
@Composable
private fun ScreenFlash(id: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    val st by EspFlasher.state.collectAsState()
    val m = ScreenModel.of(cfg.model)
    var msg by remember { mutableStateOf("") }
    val working = st is FlashState.Working
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(working) { view.keepScreenOn = working; onDispose { view.keepScreenOn = false } }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || m == null) return@rememberLauncherForActivityResult
        val b = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        val why = if (b == null) "Не удалось прочитать файл" else EspFlasher.checkImage(b)
        msg = why ?: ""
        if (why == null) EspFlasher.start(ctx, m, id, b)
    }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || m == null) return@rememberLauncherForActivityResult
        msg = runCatching {
            ctx.assets.open(m.asset).use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { i.copyTo(it) } }
            "Сохранено"
        }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("save: $it"); "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    val savePre3 = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val asset = m?.assetPre3
        if (uri == null || asset == null) return@rememberLauncherForActivityResult
        msg = runCatching {
            ctx.assets.open(asset).use { i -> ctx.contentResolver.openOutputStream(uri)!!.use { i.copyTo(it) } }
            "Сохранено"
        }.getOrElse { kg.forester.sg.ssm.SsmTrace.log("save: $it"); "Не удалось сохранить: ${kg.forester.sg.data.Failure.text(it)}" }
    }
    T("Прошивка экрана", 16.sp, weight = FontWeight.SemiBold)
    if (m?.selfFlash != false) SettingHint("Один раз для новой платы и после обновлений приложения. Прошивка лежит в приложении, компьютер не нужен.")
    Spacer(Modifier.height(6.dp))
    if (m != null && !m.selfFlash) {
        // a P4: its loader needs what only a computer's esptool does; the images are here to take there
        Hint(Lucide.Info, "Эта плата прошивается с компьютера: сохраните файл .bin, подключите порт USB TO UART, в esptool: write-flash 0 файл.bin. Прошивка лежит здесь в двух версиях: для чипа ревизии 3.00 и новее и для более старых. Ревизию покажет esptool при подключении.")
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Сохранить .bin (ревизия 3+)", false, { save.launch(m.asset.substringAfterLast('/')) }, Lucide.FileDown)
            Chip("Сохранить .bin (до ревизии 3)", false, { savePre3.launch(m.assetPre3!!.substringAfterLast('/')) }, Lucide.FileDown)
        }
        if (msg.isNotEmpty()) T(msg, 12.sp, if (msg == "Сохранено") C.Green else C.Orange)
        return
    }
    BigButton(if (working) "Прошиваю…" else if (m == null) "Сначала выберите плату" else "Прошить экран", Lucide.Download,
        { if (m != null) EspFlasher.start(ctx, m, id) }, enabled = m != null && !working)
    when (val s = st) {
        is FlashState.Working -> {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = C.Violet)
            T(s.text, 13.sp)
        }
        is FlashState.Done -> Hint(if (s.warn) Lucide.TriangleAlert else Lucide.CircleCheck, s.text, if (s.warn) C.Orange else C.Green)
        is FlashState.Failed -> Hint(Lucide.TriangleAlert, s.text, C.Orange)
        else -> Unit
    }
    if (msg.isNotEmpty()) T(msg, 12.sp, if (msg == "Сохранено") C.Green else C.Orange)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Своя прошивка (.bin)…", false, { if (m != null && !working) pick.launch(arrayOf("*/*")) }, Lucide.Upload, Modifier.alpha(if (m != null) 1f else 0.4f))
        Chip("Сохранить .bin", false, { if (m != null) save.launch(m.asset.substringAfterLast('/')) }, Lucide.FileDown, Modifier.alpha(if (m != null) 1f else 0.4f))
    }
    Hint(Lucide.Info, "Новая плата может не перейти в режим прошивки сама: зажмите на ней BOOT, вставьте USB (или нажмите RST), отпустите BOOT и нажмите «Прошить экран».")
    Hint(Lucide.Info, "Своя .bin: склеенный образ для адреса 0 (Arduino IDE: Скетч, Экспорт бинарного файла, файл merged.bin). Приложение проверит его по MD5 после записи.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenLook(id: String) {
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    T("Вид", 16.sp, weight = FontWeight.SemiBold)
    T("Поворот", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("0°", "90°", "180°", "270°").forEachIndexed { i, t -> Chip(t, cfg.rotation == i, { UsbScreen.put(id, cfg.copy(rotation = i)) }, Lucide.RotateCw) }
    }
    Spacer(Modifier.height(6.dp))
    T("Яркость", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(25 to "10 %", 64 to "25 %", 128 to "50 %", 200 to "80 %", 255 to "100 %").forEach { (b, t) ->
            Chip(t, cfg.brightness == b, { UsbScreen.put(id, cfg.copy(brightness = b)) })
        }
    }
    Spacer(Modifier.height(6.dp))
    T("Страницы меняются", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0 to if (ScreenModel.of(cfg.model)?.big == true) "не менять" else "кнопкой BOOT", 5 to "каждые 5 с", 10 to "10 с", 30 to "30 с").forEach { (s, t) ->
            Chip(t, cfg.switchS == s, { UsbScreen.put(id, cfg.copy(switchS = s)) })
        }
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Показывать тревогу", 14.sp)
            SettingHint("Значение вышло за норму (как красное на карточках): экран сам откроет страницу с ним")
        }
        Switch(cfg.alert, { UsbScreen.put(id, cfg.copy(alert = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
    }
    T("Кадров в секунду", 13.sp, C.Sub)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(5, 10, 15, 20).forEach { f -> Chip("$f", cfg.fps == f, { UsbScreen.put(id, cfg.copy(fps = f)) }) }
    }
    T("Больше кадров: плавнее стрелка, но больше работы магнитоле", 12.sp, C.Sub)
    if (ScreenModel.of(cfg.model)?.big == true) {
        Spacer(Modifier.height(6.dp))
        T("Оформление на этом экране", 13.sp, C.Sub)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(ScreenLook.current()) + ScreenLook.chosen).forEach { l -> Chip(l.name, cfg.theme == l.id, { UsbScreen.put(id, cfg.copy(theme = l.id)) }) }
        }
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Сжимать крупные изменения (JPEG)", 14.sp)
                SettingHint("Смена страницы приходит быстрее, потом уточняется без потерь. Если цвета на экране странные, выключите")
            }
            Switch(cfg.jpeg, { UsbScreen.put(id, cfg.copy(jpeg = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
        }
    }
}

@Composable
private fun PagesBar(id: String, sel: Int, onSel: (Int) -> Unit) {
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    val shown = UsbScreen.pages.collectAsState().value[id] ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Страницы", 16.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cfg.pages.forEachIndexed { i, p -> Chip("${i + 1}. ${p.layout.title}" + if (i == shown) " ·" else "", i == sel, { onSel(i) }) }
            Chip("+ страница", false, { UsbScreen.addPage(id, ScreenLayout.TILES4); onSel(UsbScreen.cfgOf(id).pages.size - 1) }, Lucide.Plus)
        }
    }
}

/** The page as the screen will show it: the very bitmap the board gets, upright, three times a second. */
@Composable
private fun PagePreview(id: String, page: ScreenPage) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    val (w, h, round) = ScreenRenderer.canvas(cfg)
    val big = ScreenModel.of(cfg.model)?.big == true
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(page, w, h, round, cfg.theme, big) {
        while (true) {
            bmp = withContext(Dispatchers.Default) { runCatching { ScreenRenderer.render(ctx, page, w, h, round, look = ScreenLook.of(cfg.theme, big)) }.getOrNull() }
            kotlinx.coroutines.delay(330)
        }
    }
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val k = minOf(520f / w, 360f / h)
        Box(Modifier.size((w * k).dp, (h * k).dp).clip(shape).background(Color.Black).border(3.dp, Color(0xFF2A2F3A), shape)) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
    }
    T((if (ScreenModel.of(cfg.model) == null) "Пример на круглом 466×466: выберите свою плату слева. " else "") +
        "Так будет на экране ${w}×${h}: те же дизайны стрелок, что на карточках, значения вживую", 12.sp, C.Sub)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageEditor(id: String, page: ScreenPage, onRemoved: () -> Unit) {
    val cfg = UsbScreen.screens.collectAsState().value[id] ?: UsbScreen.cfgOf(id)
    var picking by remember { mutableStateOf(false) }
    T("Как расположить", 15.sp, weight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenLayout.forBoard(ScreenModel.of(cfg.model)?.big == true).forEach { l ->
            Chip(l.title, page.layout == l, {
                val items = if (page.items.size >= l.slots) page.items else page.items + UsbScreen.defaultItems(8).filter { it !in page.items }
                UsbScreen.putPage(id, page.copy(layout = l, items = items.take(maxOf(l.slots, page.items.size))))
            })
        }
    }
    Spacer(Modifier.height(10.dp))
    if (page.layout.own) Hint(Lucide.Lightbulb, "Экран показывает ваше расположение из приложения: что поменяете там, изменится и здесь. Графики идут числом, 3D, карта и другие особые карточки остаются пустыми")
    else T("Что показывать", 15.sp, weight = FontWeight.SemiBold)
    if (!page.layout.own) Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        (0 until page.layout.slots).forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("${i + 1}.", 14.sp, C.Sub, modifier = Modifier.width(28.dp))
                ValuePicker(page.items.getOrNull(i)) { id ->
                    val items = (0 until maxOf(page.layout.slots, page.items.size)).map { j -> if (j == i) id else page.items.getOrNull(j) ?: "" }
                    UsbScreen.putPage(id, page.copy(items = items.dropLastWhile { it.isEmpty() }))
                }
            }
        }
    }
    if (page.layout in setOf(ScreenLayout.GAUGE, ScreenLayout.GAUGE2, ScreenLayout.GAUGE_TILES)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Дизайн стрелки: ", 15.sp, weight = FontWeight.SemiBold)
            Chip(dialStyle(page.style).name, true, { picking = true }, Lucide.Palette)
        }
    }
    if (picking) {
        // the number in the middle can be left out, as on the cards; it rides along with whatever design is picked
        val o = DialOptions.parse(page.style)
        val hidden = o.noNumber
        StylePicker(
            "Дизайн стрелки на цветном экране", o.base, plain = null, Modifier, target = "usbscreen",
            preview = { st, m -> Dial(3600f, 7000f, 1000f, 1000f, "об/мин", null, 6000f, m, st.copy(hideValue = hidden)) },
            onPick = { UsbScreen.putPage(id, page.copy(style = o.copy(base = it).format())) }, onClose = { picking = false },
            numberShown = !hidden, onNumber = { show -> UsbScreen.putPage(id, page.copy(style = o.copy(noNumber = !show).format())) },
        )
    }
    Spacer(Modifier.height(12.dp))
    if (cfg.pages.size > 1) Chip("Убрать страницу", false, {
        UsbScreen.removePage(id, page.id)
        offerUndo("Страница убрана") { UsbScreen.cfgOf(id).let { c -> UsbScreen.put(id, c.copy(pages = (c.pages + page).sortedBy { it.id })) } }
        onRemoved()
    }, Lucide.Trash2)
    Spacer(Modifier.height(6.dp))
    Hint(Lucide.Lightbulb, "Значения те же, что на «Моём экране»: ЭБУ, коробка, поездка, GPS, датчики платы, параметры с вкладки «Датчики»")
}

/** One value: a chip with its name, a menu of the whole catalogue by group. */
@Composable
private fun ValuePicker(id: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = remember(open) { ScreenRenderer.catalogue() }
    val ind = id?.takeIf { it.isNotEmpty() }?.let { ScreenRenderer.indicator(it) }
    Box {
        Chip(ind?.title ?: "пусто", ind != null, { open = true }, Lucide.ChevronDown)
        CarDropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(text = { Text("Ничего") }, onClick = { open = false; onPick("") })
            all.groupBy { it.group }.forEach { (g, list) ->
                DropdownMenuItem(text = { Text(g, color = C.Violet, fontWeight = FontWeight.SemiBold) }, onClick = {}, enabled = false)
                list.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { open = false; onPick(m.id) }) }
            }
        }
    }
}
