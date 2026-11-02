package kg.forester.sg.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Usb
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kg.forester.sg.data.*
import kg.forester.sg.service.EcuService
import kg.forester.sg.service.SensorBoxLink
import kg.forester.sg.service.UsbDeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ExtraSensorSettings() {
    var part by remember { mutableStateOf("box") }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("SensorBox", part == "box", { part = "box" })
            Chip("ШДК по USB", part == "wideband", { part = "wideband" })
            Chip("Цветной экран", part == "screen", { part = "screen" }, Lucide.Monitor)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when (part) {
                "wideband" -> WidebandSettings()
                "screen" -> UsbScreenSettings()
                else -> SensorBoxSettings()
            }
        }
    }
}

@Composable
fun WidebandSettings() {
    val ctx = LocalContext.current
    val usb = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }
    val cfg by Wideband.config.collectAsState()
    val state by Wideband.state.collectAsState()
    var devices by remember { mutableStateOf<List<UsbSerialDriver>>(emptyList()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var preview by remember { mutableStateOf(Wideband.preview) }
    LaunchedEffect(Unit) {
        EcuService.start(ctx)
        while (true) {
            devices = withContext(Dispatchers.IO) { runCatching { UsbDeviceRegistry.drivers(usb) }.getOrDefault(emptyList()) }
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { Wideband.preview = false } }
    fun save(c: WidebandConfig) {
        preview = false
        Wideband.save(c)
    }
    @Composable
    fun title(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            T(text, 17.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            title(Lucide.Flame, "Innovate LC-2 / LC / MTX-L")
            Spacer(Modifier.height(6.dp))
            SettingHint("Цифровой выход контроллера → совместимый USB–serial адаптер → магнитола. Один контроллер, 19200 бод. Аналоговый вход Arduino настраивается в SensorBox.")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                T("Читать ШДК по USB", 15.sp, modifier = Modifier.weight(1f))
                Switch(cfg.enabled, { save(cfg.copy(enabled = it)) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Purple))
            }
            Spacer(Modifier.height(6.dp))
            T(state.message, 13.sp, if (state.link == WidebandLinkState.RECEIVING) C.Green else C.Orange)
            Spacer(Modifier.height(12.dp))
            val afr = state.afr(now)?.let { "%.2f".format(Locale.US, it) } ?: "—"
            val lambda = state.lambda(now)?.let { "%.3f".format(Locale.US, it) } ?: "—"
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("AFR" to afr, "λ" to lambda).forEach { (k, v) ->
                    Column(Modifier.weight(1f).clip(Rounded).background(C.CardHi).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        T(k, 12.sp, C.Sub)
                        T(v, 30.sp, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingHint("λ настоящая, (L + 500) / 1000, AFR по стехиометрии контроллера. Прогрев, ошибки и отсутствие свежих данных не заменяются числом.")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (preview) "Остановить тест" else "Тест статусов", preview, {
                    preview = !preview
                    Wideband.preview = preview
                })
            }
            Spacer(Modifier.height(6.dp))
            SettingHint("Тест циклически показывает измерение, прогрев и ошибки на синтетических данных; на это время чтение USB приостанавливается. Показатели «AFR ШДК» и «λ ШДК» доступны в выборе приборов. В CSV они отдельные от AFR ЭБУ.")
        }
        Panel(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            title(Lucide.Usb, "USB-адаптер")
            Spacer(Modifier.height(6.dp))
            if (cfg.vendor >= 0) T("Выбрано: %04X:%04X · порт %d%s".format(cfg.vendor, cfg.product, cfg.port + 1,
                cfg.serial.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: " · без серийного номера"), 13.sp)
            if (devices.isEmpty()) T("Совместимые USB–serial адаптеры не найдены", 13.sp, C.Sub)
            devices.forEach { driver ->
                val d = driver.device
                val serial = UsbDeviceRegistry.serial(d)
                val role = when (d.deviceName) {
                    Wideband.device -> " · ШДК"
                    SensorBoxLink.ecuDevice -> " · кабель ЭБУ"
                    else -> ""
                }
                Spacer(Modifier.height(8.dp))
                T("%04X:%04X · %s · %s%s".format(d.vendorId, d.productId,
                    runCatching { d.productName }.getOrNull().orEmpty(), d.deviceName, role), 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    driver.ports.indices.forEach { port ->
                        val chosen = cfg.vendor == d.vendorId && cfg.product == d.productId && cfg.port == port &&
                            (Wideband.device == d.deviceName || (cfg.serial.isNotEmpty() && cfg.serial == serial) ||
                                (cfg.serial.isEmpty() && cfg.path == d.deviceName))
                        Chip("Выбрать порт ${port + 1}", chosen, {
                            save(WidebandConfig(true, d.vendorId, d.productId, serial.orEmpty(), port, d.deviceName))
                            if (!usb.hasPermission(d)) {
                                val intent = Intent(EcuService.ACTION_USB_PERMISSION).setPackage(ctx.packageName)
                                usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingHint("Адаптер ШДК узнаётся по серийному номеру, а без него (CH340) по пакетам Innovate: приложение пару секунд слушает адаптер, ничего не отправляя. Только узнанный адаптер исключается из поиска ЭБУ и SensorBox. Два одинаковых адаптера без серийного номера различаются, когда контроллер включён; не получилось — кабель ЭБУ остаётся кабелю ЭБУ, а здесь и в предупреждениях будет сказано.")
            if (cfg.vendor >= 0) {
                Spacer(Modifier.height(10.dp))
                Chip("Забыть адаптер", false, { save(WidebandConfig()) })
            }
        }
    }
}
