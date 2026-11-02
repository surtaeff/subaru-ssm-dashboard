package kg.forester.sg.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.*
import kg.forester.sg.data.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App drawer, so the dashboard can be used as the home screen. */
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    // the packages' labels and icons: off the main thread, an empty grid until then
    val apps by produceState(emptyList<kg.forester.sg.data.InstalledApp>()) {
        value = withContext(Dispatchers.IO) { Shortcuts.installedApps(ctx) }
    }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { T("Поиск приложения", 14.sp, C.Sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                    focusedBorderColor = C.Purple, unfocusedBorderColor = C.Border,
                ),
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.weight(1f))
            Chip("Штатный лаунчер", false, { openStockLauncher(ctx) }, Lucide.LayoutGrid)
        }
        Spacer(Modifier.height(12.dp))
        val list = apps.filter { query.isBlank() || it.label.contains(query, true) }
        LazyVerticalGrid(GridCells.Adaptive(roomFor(118.dp, 13f)), Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.pkg }) { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                        ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { ctx.startActivity(it) }
                        }
                    }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bmp by produceState(AppIcons.cache.get(a.pkg), a.pkg) {
                        if (value == null) value = withContext(Dispatchers.Default) {
                            runCatching { a.icon?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()?.also { AppIcons.cache.put(a.pkg, it) }
                        }
                    }
                    bmp.let { if (it != null) Image(it, a.label, Modifier.size(64.dp)) else Spacer(Modifier.size(64.dp)) }
                    Spacer(Modifier.height(6.dp))
                    T(a.label, 13.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Icons of the launcher grid: scrolling back shows them at once. Given up with the screen's other caches, see [trimUiCaches]. */
internal object AppIcons {
    val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48)
}

/**
 * The head unit's own launcher: any other home-screen app (Teyes' com.android.launcher8 first when present),
 * skipping us and the system's FallbackHome.
 */
fun stockLauncher(ctx: Context): android.content.ComponentName? {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val homes = ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo }
        .filter { it.packageName != ctx.packageName && !it.name.endsWith("FallbackHome") }
    val pick = homes.firstOrNull { it.packageName == "com.android.launcher8" } ?: homes.firstOrNull()
    return pick?.let { android.content.ComponentName(it.packageName, it.name) }
}

/** Opens the stock launcher even when our app is the default home screen. */
fun openStockLauncher(ctx: Context) {
    val c = stockLauncher(ctx) ?: return
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(c)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

/** True when this app is the default home screen. */
fun isDefaultHome(ctx: Context): Boolean {
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return ctx.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName == ctx.packageName
}

/**
 * Makes this app (or the stock launcher) the default home screen. Needs root; a flag file lets the boot script
 * re-apply it in case the firmware resets the preferred home after a reboot.
 */
fun setDefaultHome(ctx: Context, ours: Boolean) {
    val comp = if (ours) "${ctx.packageName}/.MainActivity"
    else stockLauncher(ctx)?.flattenToShortString() ?: "com.android.launcher8/.Launcher"
    val flag = if (ours) "touch /data/adb/forester_home" else "rm -f /data/adb/forester_home"
    val boot = "/data/adb/service.d/forester_home.sh"
    val script = """
        $flag
        cat > $boot <<'EOS'
#!/system/bin/sh
until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 3
[ -f /data/adb/forester_home ] && pm path ${ctx.packageName} >/dev/null && cmd package set-home-activity ${ctx.packageName}/.MainActivity
EOS
        chmod 755 $boot
        cmd package set-home-activity $comp
    """.trimIndent()
    val ok = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", script)).waitFor() == 0 }.getOrDefault(false)
    if (!ok) runCatching {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
