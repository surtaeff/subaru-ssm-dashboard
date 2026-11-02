package kg.forester.sg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuLink
import kg.forester.sg.ssm.Obdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Расширенные параметры OBDb (по марке)» on «Датчики» in the OBD-II mode: the switch, the model (its signal set is
 * downloaded from OBDb on a tap, the make comes from the VIN) and the source's licence, which asks to be named.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObdbPanel() {
    val ctx = LocalContext.current
    val s = Repo.settings
    val scope = rememberCoroutineScope()
    val vin by Repo.vin.collectAsState()
    val changed by Obdb.changed.collectAsState()
    var on by remember { mutableStateOf(s.obdbOn) }
    val vinMake by androidx.compose.runtime.produceState<String?>(null, vin) { value = withContext(Dispatchers.IO) { DtcRepo.make(ctx)?.name } }
    var make by remember(vinMake) { mutableStateOf(vinMake ?: "") }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf("") }
    val repo = remember(changed) { s.obdbRepo }
    val year = remember(vin) { vin?.takeIf { it.isNotEmpty() }?.let { Obdb.modelYear(it) } }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Расширенные параметры OBDb (по марке)", 14.sp)
                T("Заводские параметры (запросы 21/22) для вашей модели из открытой базы OBDb: скачиваются по кнопке, " +
                    "работают на машинах с CAN", 11.sp, C.Sub)
            }
            Switch(on, { on = it; s.obdbOn = it; Obdb.changed.value++ }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
        }
        if (!on) return@Column
        if (EcuLink.obd?.link?.isCan == false) T("Сейчас связь не по CAN (K-Line): параметры OBDb не опрашиваются", 11.sp, C.Orange)
        if (repo.isNotEmpty()) {
            val have = Obdb.file(ctx.filesDir, repo) != null
            T("Модель: ${repo.replace('-', ' ')}" + if (have) "" else " (не скачана)", 12.sp, if (have) C.Green else C.Orange)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = make, onValueChange = { make = it }, singleLine = true,
                label = { FieldLabel(if (vinMake != null) "Марка (по VIN)" else "Марка") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(4.dp))
            Chip("Модели", false, {
                if (make.isBlank()) { status = "Введите марку"; return@Chip }
                status = "Ищу модели ${make.trim()} в OBDb…"
                scope.launch {
                    val m = withContext(Dispatchers.IO) { Obdb.models(make.trim()) }
                    models = m
                    status = if (m.isEmpty()) "Ничего не нашлось (нет интернета или марки нет в OBDb)" else "Выберите модель"
                }
            })
        }
        models?.takeIf { it.isNotEmpty() }?.let { list ->
            FlowRow(
                Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                list.forEach { r ->
                    Chip(r.substringAfter('-').replace('-', ' '), r == repo, {
                        status = "Скачиваю $r…"
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Obdb.download(ctx.filesDir, r, year) }
                            if (f != null) {
                                s.obdbRepo = r
                                Obdb.changed.value++
                                status = "Готово: отметьте параметры в списке (их названия на английском, как в OBDb)"
                            } else status = "Не удалось скачать $r"
                        }
                    })
                }
            }
        }
        if (status.isNotEmpty()) T(status, 11.sp, C.Sub)
        T("Данные: OBDb, github.com/OBDb (сообщество Sidecar), лицензия CC BY-SA 4.0. Значения не проверены производителем", 10.sp, C.Sub)
    }
}
