package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.DeviceId
import kg.forester.sg.data.Repo
import kg.forester.sg.service.LogUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The id of this unit, where the person can read it to the owner; a tap copies it. */
@Composable
fun DeviceIdLine(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var id by remember { mutableStateOf("…") }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { id = withContext(Dispatchers.IO) { DeviceId.get(ctx) } }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    Row(
        modifier.fillMaxWidth().heightIn(min = LocalMinTap.current).clip(RoundedCornerShape(10.dp))
            .clickable { clip.setText(AnnotatedString(id)); copied = true }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("ID устройства", 13.sp, C.Sub, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        T(if (copied) "Скопировано" else id, 15.sp, if (copied) C.Green else C.Text, FontWeight.Medium, maxLines = 1)
    }
}

/** The switch of the upload, the id, and the button that shows the exact text before it goes. */
@Composable
fun LogUploadBlock() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Repo.settings.logUploadEnabled) }
    var endpoint by remember { mutableStateOf(Repo.settings.logEndpoint) }
    var preview by remember { mutableStateOf<LogUpload.Prepared?>(null) }
    var result by remember { mutableStateOf("") }
    // the address of the receiver is looked up when this opens, whatever the switch says: a one-off send needs no consent to the automatic one
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { kg.forester.sg.data.Updater.logUrl() }?.let { u ->
            val e = LogUpload.endpointOf(u).orEmpty()
            Repo.settings.logEndpoint = e; endpoint = e
        }
        looked = true
    }
    val known = LogUpload.canManual(endpoint)
    SwitchRow(
        "Отправлять журнал разработчику", "Если связь с ЭБУ пропала при работающем двигателе, журнал уйдёт сам: не чаще раза в час и 5 раз в сутки. " +
            "Номер машины (VIN), адреса адаптера и датчиков, название машины, место, голос и названия треков из него убираются.",
        on, if (on && !known) "адрес приёма пока не задан" else null,
    ) {
        on = it; Repo.settings.logUploadEnabled = it
        // off: nothing waits to be sent later
        if (!it) LogUpload.forget(ctx)
    }
    DeviceIdLine()
    SettingHint("Назовите этот ID разработчику, и он найдёт ваши журналы. Нажмите на него, чтобы скопировать.")
    Spacer(Modifier.height(6.dp))
    ActionButton("Показать и отправить разработчику", known) {
        scope.launch { result = ""; preview = withContext(Dispatchers.IO) { LogUpload.prepareNow(ctx) } }
    }
    if (!known) SettingHint(if (looked) "Отправка пока не включена: адрес приёма не получен (нет сети или разработчик его ещё не открыл)." else "Ищу адрес приёма…")
    if (result.isNotEmpty()) T(result, 12.sp, C.Sub)
    preview?.let { p ->
        CarSheet({ preview = null }, width = 640.dp, maxHeight = 560.dp) {
            T("Вот что уйдёт", 18.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            SettingHint("Это точный текст, другого не отправляется. ID: ${DeviceId.get(ctx)}")
            Spacer(Modifier.height(6.dp))
            val lines = remember(p) { p.text.lines() }
            val listed = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().scrollCue(listed, C.CardHi).clip(RoundedCornerShape(10.dp)).background(C.CardHi).padding(8.dp), state = listed) {
                items(lines.size) { i -> T(lines[i], 11.sp, C.Text) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ActionButton("Отмена", true) { preview = null } }
                Box(Modifier.weight(1f)) {
                    ActionButton("Отправить", true) {
                        preview = null; result = "Отправляю…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { LogUpload.sendNow(ctx, p) }
                            result = if (r.ok) "Отправлено"
                            else "Не вышло: ${r.reason}. " + if (on) "Повторю при следующем запуске." else "Нажмите ещё раз позже."
                        }
                    }
                }
            }
        }
    }
}
