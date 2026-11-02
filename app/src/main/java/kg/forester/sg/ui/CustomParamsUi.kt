package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.*
import kg.forester.sg.data.CustomParam
import kg.forester.sg.data.CustomParams
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.RrExpr

/**
 * The driver's own ECU addresses: the list with edit and delete, and the form. Same fields as BtSSM's
 * "Edit custom field", so a working BtSSM setup can be copied over as is.
 */
@Composable
fun CustomParamsDialog(onClose: () -> Unit) {
    Repo.customVersion.collectAsState().value
    val list = CustomParams.list()
    var editing by remember { mutableStateOf<CustomParam?>(null) }
    val e = editing
    if (e != null) {
        CarSheet(onClose, 820.dp, 600.dp) { CustomParamForm(e, onDone = { editing = null }) }
        return
    }
    CarSheet(onClose, 820.dp, 600.dp, head = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Свои параметры ЭБУ", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Добавить", false, {
                    editing = CustomParam(CustomParams.newId(), "", 0, 4, "float", "x", "°", 2, "")
                }, Lucide.Plus)
                Spacer(Modifier.width(8.dp))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(6.dp))
    }) {
            SettingHint("Для прошивок, которых нет в базе RomRaider: адрес в памяти ЭБУ, как в поле «Custom field» у BtSSM, или формула " +
                "из других значений. Параметр появится на «Датчиках», в логе и в выборе виджетов. С ролью FBKC / FLKC / IAM / целевой наддув " +
                "его использует и экран «Мониторинг» с предупреждениями о детонации, с ролью MAF, AFR, ОЖ и т. п. он заменит значение ЭБУ везде " +
                "(старые ЭБУ JECS отдают их не все или не так).")
            Spacer(Modifier.height(10.dp))
            if (list.isEmpty()) T("Пока ничего не добавлено.", 14.sp, C.Sub)
            Column {
                list.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                            .clickable { editing = p }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(p.name.ifBlank { p.id }, 15.sp)
                            T(listOfNotNull(
                                if (p.calculated) "формула" else "0x%06X".format(p.addr), if (p.calculated) null else "${p.len} байт",
                                if (p.calculated) null else CustomParam.STORAGES.firstOrNull { it.first == p.storage }?.second,
                                p.formula.takeIf { it != "x" }, p.units.ifBlank { null },
                                CustomParam.ROLES.firstOrNull { it.first == p.role && p.role.isNotEmpty() }?.let { "роль: ${it.second}" },
                            ).joinToString(" · "), 12.sp, C.Sub, maxLines = 1)
                        }
                        var ask by remember(p.id) { mutableStateOf(false) }
                        IconTap(Lucide.Trash2, "Удалить", { ask = true }, tint = C.Red, iconSize = 20.dp, background = C.Card)
                        if (ask) ConfirmDialog(
                            "Удалить параметр?", "«${p.name.ifBlank { p.id }}» уйдёт из списка, графиков и записи логов.",
                            "Удалить", { CustomParams.remove(p.id) }, { ask = false },
                        )
                    }
                }
            }
        
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomParamForm(start: CustomParam, onDone: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var addr by remember { mutableStateOf(if (start.addr == 0) "" else "%06X".format(start.addr)) }
    var len by remember { mutableStateOf(start.len) }
    var storage by remember { mutableStateOf(start.storage) }
    var formula by remember { mutableStateOf(start.formula) }
    var units by remember { mutableStateOf(start.units) }
    var dec by remember { mutableStateOf(start.decimals) }
    var role by remember { mutableStateOf(start.role) }
    var calc by remember { mutableStateOf(start.calculated) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // what a formula may refer to: the base's parameters and the other own ones (not itself: no loops)
    val known = remember { kg.forester.sg.ssm.RomRaider.load(ctx).map { it.id }.toSet() + CustomParams.list().map { it.id } - start.id }
    val addrInt = addr.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
    val expr = RrExpr.parse(formula.ifBlank { "x" })
    val unknown = expr?.refs?.firstOrNull { it !in known }
    val error = when {
        name.isBlank() -> "Введите название"
        !calc && (addrInt == null || addrInt !in 0..0xFFFFFF) -> "Адрес — до 6 шестнадцатеричных цифр, например FFB040"
        !calc && storage == "float" && len != 4 -> "Float занимает 4 байта"
        expr == null -> "Формула: x, числа, + − * /, скобки и другие значения (P8, [P9:mph], U1)"
        unknown != null -> "Нет значения $unknown: номера P… как на «Датчиках», свои U1, U2…"
        calc && expr.refs.isEmpty() -> "Формула без адреса должна использовать другие значения, например P12/P8*60"
        else -> null
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (start.name.isEmpty()) "Новый параметр" else "Параметр ${start.id}", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip("Отмена", false, onDone)
            Spacer(Modifier.width(8.dp))
            Chip("Сохранить", error == null, {
                if (error == null) {
                    CustomParams.put(start.copy(name = name.trim(), addr = if (calc) -1 else addrInt!!, len = len, storage = storage,
                        formula = formula.trim().ifBlank { "x" }, units = units.trim(), decimals = dec, role = role))
                    onDone()
                }
            })
        }
        // the title and its buttons stay in sight, the form scrolls
        Column(Modifier.weight(1f, fill = false).cueScroll()) {
        Spacer(Modifier.height(10.dp))
        Labeled("Откуда значение") {
            Chip("Адрес в ЭБУ", !calc, { calc = false })
            Chip("Формула из других значений", calc, { calc = true; if (formula.isBlank() || formula == "x") formula = "" })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField("Название", name, Modifier.weight(1.4f)) { name = it }
            if (!calc) FormField("Адрес (hex)", addr, Modifier.weight(1f), KeyboardType.Ascii, KeyboardCapitalization.Characters) { addr = it }
            FormField("Формула", formula, Modifier.weight(if (calc) 2f else 1f), KeyboardType.Ascii) { formula = it }
            FormField("Единицы", units, Modifier.weight(0.6f)) { units = it }
        }
        Spacer(Modifier.height(10.dp))
        if (!calc) {
            Labeled("Длина") { listOf(1, 2, 4).forEach { n -> Chip("$n байт", len == n, { len = n }) } }
            Labeled("Тип") { CustomParam.STORAGES.forEach { (k, l) -> Chip(l, storage == k, { storage = k; if (k == "float") len = 4 }) } }
        }
        Labeled("Знаков после запятой") { (0..3).forEach { n -> Chip("$n", dec == n, { dec = n }) } }
        Labeled("Роль") { CustomParam.ROLES.forEach { (k, l) -> Chip(l, role == k, { role = k }) } }
        Spacer(Modifier.height(6.dp))
        T(error ?: if (calc) "В формуле можно брать уже посчитанные значения: P8 — обороты, P12 — MAF г/с, P21 — впрыск мс, " +
            "P11 — воздух на впуске, свои U1, U2… Например, виртуальный ШДК (AFR по MAF и впрыску, форсунки 380 cc): " +
            "P12 / (P8 * (P21 - 0.6) * 380 * 0.000002 * 745 / 3600) и роль AFR. Оценка грубая: точность зависит от форсунок и их задержки."
            else "Пример из BtSSM для ЭБУ «572»: FLKC — адрес FFB040, 4 байта, float, формула x; FBKC — FFB01C так же. " +
                "В формуле можно использовать и другие значения: x*P8/1000.",
            12.sp, if (error != null) C.Orange else C.Sub)
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Labeled(label: String, content: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 13.sp, C.Sub, modifier = Modifier.width(170.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
internal fun FormField(
    label: String, value: String, modifier: Modifier, type: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Sentences, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { FieldLabel(label) },
        keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = type),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
        ),
        modifier = modifier,
    )
}
