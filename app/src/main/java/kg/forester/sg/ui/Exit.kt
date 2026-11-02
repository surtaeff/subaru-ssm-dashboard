package kg.forester.sg.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Power
import kg.forester.sg.service.EcuService

/** Stops the service (polling, fuel, logs) and closes the app, so nothing keeps running on a phone. */
fun exitApp(ctx: Context) {
    ctx.stopService(Intent(ctx, EcuService::class.java))
    ctx.stopService(Intent(ctx, kg.forester.sg.service.PlayerService::class.java))
    (ctx as? Activity)?.finishAndRemoveTask()
}

/** Power icon for the top bar; asks first, a stray tap while driving would stop the logging. */
@Composable
fun ExitButton(size: Dp) {
    var ask by remember { mutableStateOf(false) }
    IconTap(Lucide.Power, kg.forester.sg.data.I18n.pick("Выход", "Exit"), { ask = true }, tint = C.Sub, iconSize = size)
    if (ask) ExitDialog { ask = false }
}

@Composable
fun ExitDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    CarAlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Text,
        icon = { Icon(Lucide.Power, null, tint = C.Red, modifier = Modifier.size(26.dp)) },
        title = { T("Закрыть приложение?", 19.sp, weight = FontWeight.SemiBold) },
        text = { T("Опрос ЭБУ, подсчёт расхода, запись логов и музыка остановятся до следующего запуска.", 14.sp) },
        confirmButton = {
            androidx.compose.material3.TextButton({ onClose(); exitApp(ctx) }) {
                Text("Выйти", color = C.Red)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Отмена", color = C.Sub) } },
    )
}
