package kg.forester.sg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide

/** What a card shows, how the app gets the number, and what is normal. */
data class InfoText(val what: String, val how: String, val norm: String? = null)

/** "i" next to a card title; does nothing for keys without a text in [InfoTexts]. */
@Composable
fun InfoButton(title: String, key: String = title, modifier: Modifier = Modifier) {
    val info = InfoTexts.byKey[key] ?: return
    var open by remember { mutableStateOf(false) }
    // the small "i" keeps its place in the 20 dp title rows; the round area that takes the tap is 48 dp, centred on
    // it and reaching past the row (requiredSize lets it overhang the 28 dp box instead of being squeezed into it)
    Box(modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(Lucide.Info, "Что это", tint = C.Sub.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
        Box(Modifier.requiredSize(48.dp).clip(CircleShape).clickable { open = true })
    }
    if (open) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val shown = remember(info) {
            // another make goes by no Subaru's numbers, not even the last connected one's
            val e = if (kg.forester.sg.data.CarKind.other.value) kg.forester.sg.ssm.EngineInfo("EJ", false, "EJ", "нет") else kg.forester.sg.ssm.EngineId.detect(ctx)
            InfoTexts.forEngine(info, e)
        }
        InfoDialog(title, shown, key) { open = false }
    }
}

@Composable
fun InfoDialog(title: String, info: InfoText, key: String = "", onClose: () -> Unit) {
    CarAlertDialog(
        onDismissRequest = onClose,
        containerColor = C.CardHi, textScrolls = false,
        title = { T(title, 18.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).cueScroll(C.DialogHi)) {
                Section("Что показывает", kg.forester.sg.data.DistanceUnits.shownProse(info.what))
                Section("Как считается", kg.forester.sg.data.DistanceUnits.shownProse(info.how))
                info.norm?.let { Section("Норма и подсказки", kg.forester.sg.data.DistanceUnits.shownProse(it)) }
                // the cards of the home and «Динамика» screens show the trip's min and max
                if ((key.startsWith("home.") || key.startsWith("drive.")) && kg.forester.sg.data.Repo.settings.feature("minmax")) {
                    T("Мин и макс за поездку", 13.sp, C.Violet, FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    MinMaxLegend()
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Понятно") } },
    )
}

@Composable
private fun Section(head: String, body: String) {
    T(head, 13.sp, C.Violet, FontWeight.SemiBold)
    Spacer(Modifier.height(3.dp))
    T(body, 14.sp)
    Spacer(Modifier.height(12.dp))
}

/** What the min / max marks on the cards and gauges mean (info pop-ups, «Функции»). */
@Composable
fun MinMaxLegend() {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.ArrowUp, null, tint = MaxMarkColor, modifier = Modifier.size(14.dp))
            T(" 89", 14.sp, MaxMarkColor, FontWeight.SemiBold)
            T("   — самое высокое значение за поездку (оранжевая метка)", 13.sp)
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.ArrowDown, null, tint = MinMarkColor, modifier = Modifier.size(14.dp))
            T(" 87", 14.sp, MinMarkColor, FontWeight.SemiBold)
            T("   — самое низкое (синяя метка)", 13.sp)
        }
        T("На полосе светлым выделен весь диапазон поездки между метками, на тахометре и спидометре — треугольники на шкале. " +
            "Считается, пока работает двигатель, и сбрасывается с новой поездкой. Выключается в Настройки → «Функции».", 12.sp, C.Sub)
    }
}
