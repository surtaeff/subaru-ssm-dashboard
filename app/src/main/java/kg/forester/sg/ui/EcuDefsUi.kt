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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Tag
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import kg.forester.sg.data.CustomParam
import kg.forester.sg.data.EcuDefs
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.RomRaider
import kg.forester.sg.ssm.RrParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file's name as the picker shows it. */
private fun displayName(ctx: android.content.Context, uri: android.net.Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "logger.xml"

/**
 * Settings, «Авто и адаптер»: the driver's own RomRaider logger definitions (see [EcuDefs]). The loaded files with
 * what each one added or replaced and how much of it the connected ROM has, and roles for parameters under ids of
 * their own (RomRaider's ids are picked by the engine guard by themselves).
 */
@Composable
fun EcuDefsBlock() {
    val ctx = LocalContext.current
    val version by Repo.defsVersion.collectAsState()
    val init by Repo.ecuInit.collectAsState()
    val avail by Repo.rrAvailable.collectAsState()
    var files by remember { mutableStateOf<List<EcuDefs.Loaded>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var roleDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // a whole logger.xml takes a moment to read: off the main thread
    LaunchedEffect(version) { files = withContext(Dispatchers.IO) { RomRaider.load(ctx); RomRaider.userFiles } }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(ctx, uri)
        scope.launch {
            val why = withContext(Dispatchers.IO) { EcuDefs.import(ctx, uri, name) }
            if (why != null) { msg = "$name: $why"; bad = true; return@launch }
            val st = withContext(Dispatchers.IO) { RomRaider.load(ctx); RomRaider.userFiles.firstOrNull { it.entry.name == name }?.stats }
            msg = "$name загружен" + (st?.let { ": добавлено ${it.added}, заменено ${it.replaced}" } ?: "")
            bad = false
        }
    }
    val rom = init?.romId?.uppercase()
    val availIds = remember(avail) { avail.map { it.id }.toSet() }

    T("Свои определения RomRaider (logger.xml)", 15.sp)
    T("Для ЭБУ, которых нет в базе или где она ошибается: старые JECS, 16-битные, SF5 со своими формулами, прошивки без " +
        "прямого адреса давления (наддув упирается в 1,53 бар). Подойдёт logger.xml от RomRaider целиком или кусок с parameter " +
        "и ecuparam. Определение прошивки ECUFlash (.xml с <rom>) задаёт оси таблицы «Обучение» для своего ECU ID, в том числе у " +
        "кастомной прошивки со сдвинутыми таблицами. Параметр с номером из базы заменяет её. Номера RomRaider (E51, P90, E39…) приложение само берёт для " +
        "давления, IAM и откатов, своим номерам назначьте роль. Новые параметры отметьте на «Датчиках».", 11.sp, C.Sub)
    Spacer(Modifier.height(6.dp))
    Chip("Загрузить файл", false, { pick.launch(arrayOf("text/xml", "application/xml", "*/*")) }, Lucide.Upload)
    // the open folder: files put there by a file manager apply by themselves
    val folder = remember { EcuDefs.folder(ctx)?.absolutePath?.replace(Regex("^/storage/emulated/0"), "")?.trimStart('/') }
    if (folder != null) {
        Spacer(Modifier.height(4.dp))
        T("Или положите .xml в папку $folder (через любой файловый менеджер): файлы оттуда читаются сами, по порядку имён. " +
            "Вся база приложения в том же формате лежит там же в base/carpulse_base.xml: копируйте из неё то, что надо поправить", 11.sp, C.Sub)
    }
    if (msg.isNotEmpty()) { Spacer(Modifier.height(4.dp)); T(msg, 12.sp, if (bad) C.Red else C.Green) }
    files.forEach { f ->
        val st = f.stats
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T(f.entry.name + if (f.entry.inFolder) "  ·  из папки" else "", 14.sp, maxLines = 1)
                val r = f.rom
                if (r != null) {
                    // an ECUFlash ROM definition: the axes of «Обучение»
                    T("ECUFlash ${r.cal}, ECU ID ${r.ecuId}: оси обучения " + listOfNotNull(r.af?.let { "A/F" }, r.rpm?.let { "обороты" },
                        r.load?.let { "нагрузка" }).joinToString(", "), 12.sp, C.Sub)
                    if (rom != null) T(if (rom == r.ecuId) "Это ROM подключённого ЭБУ" else "К ROM $rom не относится", 12.sp,
                        if (rom == r.ecuId) C.Green else C.Orange)
                } else if (st == null) T("Не читается: ${f.error}", 12.sp, C.Red)
                else {
                    T(listOfNotNull(
                        "добавлено ${st.added}", "заменено ${st.replaced}",
                        st.same.takeIf { it > 0 }?.let { "совпадает с базой $it" },
                        st.roms.takeIf { it > 0 }?.let { "прошивок $it" },
                    ).joinToString(" · "), 12.sp, C.Sub)
                    if (rom != null) {
                        val ids = st.ids(rom)
                        // what the ECU can really give, once the list for it is made
                        val n = if (availIds.isEmpty()) ids.size else ids.count { it in availIds }
                        T(if (n > 0) "К ROM $rom подходит: $n" else "К ROM $rom ничего из файла не относится", 12.sp, if (n > 0) C.Green else C.Orange)
                    }
                }
            }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(C.Card).clickable { EcuDefs.remove(ctx, f.entry); msg = "" },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Trash2, "Удалить", tint = C.Red, modifier = Modifier.size(16.dp)) }
        }
    }
    val roles = remember(version) { EcuDefs.roles() }
    if (files.isEmpty() && roles.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    roles.forEach { (role, id) ->
        val label = CustomParam.ROLES.firstOrNull { it.first == role }?.second ?: role
        val p = files.firstNotNullOfOrNull { f -> f.defs?.let { d -> d.params.firstOrNull { it.id == id } ?: d.ext.firstOrNull { it.param.id == id }?.param } }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            T("$label: $id ${p?.name ?: "(нет в файлах)"}", 12.sp, if (p != null) C.Text else C.Orange, modifier = Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable { EcuDefs.setRole(role, null) },
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Trash2, "Убрать роль", tint = C.Sub, modifier = Modifier.size(14.dp)) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Chip("Назначить роль параметру из файла", false, { roleDialog = true }, Lucide.Tag)
    if (roleDialog) EcuDefRoleDialog(files, rom) { roleDialog = false }
}

/** A role (IAM, FBKC, manifold pressure…) for a parameter of the driver's files: the role first, then the parameter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EcuDefRoleDialog(files: List<EcuDefs.Loaded>, rom: String?, onClose: () -> Unit) {
    var role by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // later files win, like in the merge; an extended one only where it lists the connected ROM
    val all = remember(files, rom) {
        val m = LinkedHashMap<String, RrParam>()
        files.mapNotNull { it.defs }.forEach { d ->
            d.params.forEach { m[it.id] = it }
            d.ext.filter { rom == null || rom in it.roms }.forEach { m[it.param.id] = it.param }
        }
        m.values.toList()
    }
    val q = query.trim()
    val shown = all.filter { q.isEmpty() || it.id.contains(q, true) || it.name.contains(q, true) }.take(80)
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(720.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Роль параметра из файла", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Отмена", false, onClose)
            }
            Spacer(Modifier.height(6.dp))
            T("Нужна, когда в файле давление, IAM, FBKC или FLKC под своим номером: приложение узнаёт их только по номерам RomRaider. " +
                "Свой адрес из «Своих параметров» с той же ролью главнее.", 12.sp, C.Sub)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CustomParam.ROLES.filter { it.first.isNotEmpty() }.forEach { (k, l) -> Chip(l, role == k, { role = k }) }
            }
            if (role.isEmpty()) return@Column
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { FieldLabel("Поиск по номеру или названию") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) T(if (rom != null) "Для ROM $rom в файлах ничего не нашлось" else "Ничего не нашлось", 13.sp, C.Sub)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shown.forEach { p ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                            .clickable { EcuDefs.setRole(role, p.id); onClose() }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        T("${p.id} · ${p.name}", 14.sp, maxLines = 1)
                        T(listOf(p.unitsRu, if (p.kind == RrParam.Kind.EXT) "свой адрес у прошивки" else if (p.addr >= 0) "0x%06X".format(p.addr) else "формула")
                            .filter { it.isNotBlank() }.joinToString(" · "), 11.sp, C.Sub, maxLines = 1)
                    }
                }
            }
        }
    }
}
