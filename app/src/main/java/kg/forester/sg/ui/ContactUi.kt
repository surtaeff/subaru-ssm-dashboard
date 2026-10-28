package kg.forester.sg.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*

/** The app's Telegram group: questions, bugs, ideas. */
const val TG_GROUP = "carpulse_app"

/**
 * «Связь»: the Telegram group as a QR code to scan with the phone (typing a link on a head unit is no fun) and a
 * button that opens it on the unit, plus what to send with a bug report so it can be fixed without questions.
 */
@Composable
fun ContactSettings() {
    val ctx = LocalContext.current
    val link = "https://t.me/$TG_GROUP"
    val qr = remember { qrBitmap(link, 512) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(470.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PanelTitle(Lucide.MessageCircle, "Группа в Telegram")
            Spacer(Modifier.height(6.dp))
            T("Вопросы, ошибки, идеи и помощь с подключением: всё в одной группе, там же новости о версиях.", 13.sp, C.Sub)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // white quiet zone around the code: scanners need it, a dark theme would eat it
                Box(Modifier.size(210.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    qr?.let { Image(it.asImageBitmap(), "QR-код группы", Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Наведите камеру телефона", 14.sp, weight = FontWeight.Medium)
                    T("t.me/$TG_GROUP", 16.sp, C.Violet, FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton("Открыть группу на магнитоле", true) { open(link) }
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            T("Если что-то не работает", 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            listOf(
                "1. Сразу после проблемы: Настройки, Авто и адаптер, внизу Отправить журнал, и выберите группу или личку.",
                "2. Фото экрана, где видно, что не так.",
                "3. Модель магнитолы и машины, версия приложения (Настройки, Основные).",
            ).forEach { T(it, 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            Spacer(Modifier.height(16.dp))
            T("Ещё", 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("GitHub: версии и исходники", false, { open("https://github.com/surtaeff/subaru-ssm-dashboard") }, Lucide.Github)
                Chip("drive2", false, { open("https://www.drive2.ru/l/745439472769827788/") }, Lucide.Car)
            }
            Spacer(Modifier.height(10.dp))
            T("Приложение бесплатное и открытое, лучшая помощь проекту: отзывы, что работает и что нет.", 12.sp, C.Sub)
        }
    }
}

/** [text] as a black on white QR code bitmap, [size] px square; null if it could not be made. */
private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0),
    )
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
