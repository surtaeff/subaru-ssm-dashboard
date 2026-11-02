package kg.forester.sg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kg.forester.sg.data.EcuCaps
import kg.forester.sg.data.Repo

/**
 * Sensors the ECU's init says it lacks, each switchable to "it has it": old JECS units leave the bits off for
 * MAF, intake air or throttle they do have. Per ROM; the connection starts over with the new list.
 */
@Composable
fun EcuCapsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val raw by Repo.ecuInitRaw.collectAsState()
    Repo.capsVersion.collectAsState().value
    val rom = raw?.romId ?: return
    val hidden = remember(raw) { EcuCaps.hidden(ctx) }
    val forced = EcuCaps.forced(rom)
    CarSheet(onClose, 720.dp, 600.dp, head = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Датчики, которые ЭБУ скрывает", 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip("Готово", true, onClose)
            }
            Spacer(Modifier.height(6.dp))
    }) {
            SettingHint("ЭБУ $rom говорит, что этих датчиков у него нет, и приложение их не читает: без датчика ЭБУ отдаёт по адресу мусор. " +
                "Старые ЭБУ (JECS) так скрывают и то, что есть: MAF, воздух на впуске, дроссель. Включите то, что у машины точно есть, " +
                "и проверьте значения на «Датчиках»: если там ерунда, выключите обратно. Связь переподключится сама.")
            Spacer(Modifier.height(10.dp))
            Column {
                hidden.forEach { h ->
                    val on = (h.byte to h.bit) in forced
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.CardHi)
                            .clickable { EcuCaps.set(rom, h.byte, h.bit, !on) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(h.names.joinToString(", "), 14.sp, maxLines = 2)
                            T("бит ${h.byte}.${h.bit}", 11.sp, C.Sub)
                        }
                        Switch(on, { EcuCaps.set(rom, h.byte, h.bit, it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Purple))
                    }
                }
            }
        
    }
}
