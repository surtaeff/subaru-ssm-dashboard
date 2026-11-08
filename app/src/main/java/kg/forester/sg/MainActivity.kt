package kg.forester.sg

import kg.forester.sg.ui.keepInView

import kg.forester.sg.ui.DriverOverlays
import kg.forester.sg.ui.dragsLikeFinger
import kg.forester.sg.ui.Icon
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import kg.forester.sg.ui.tapClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import kg.forester.sg.ui.RoundedCornerShape
import com.composables.icons.lucide.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuService
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ui.C
import kg.forester.sg.ui.activeGlow
import kg.forester.sg.ui.chromeLayer
import kg.forester.sg.ui.activeHalo
import kg.forester.sg.ui.navBarFill
import kg.forester.sg.ui.fadeEnds
import kg.forester.sg.ui.themeBackdrop
import kg.forester.sg.ui.asDp
import kg.forester.sg.ui.LinkIcon
import kg.forester.sg.ui.DtcScreen
import kg.forester.sg.ui.FuelScreen
import kg.forester.sg.ui.HomeScreen
import kg.forester.sg.ui.LogScreen
import kg.forester.sg.ui.ProgressBar
import kg.forester.sg.ui.SettingsScreen
import kg.forester.sg.ui.T
import kg.forester.sg.ui.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class Screen(val title: String, val icon: ImageVector) {
    Home("Главная", Lucide.House),
    Mine("Мой экран", Lucide.LayoutDashboard),
    Drive("Динамика", Lucide.Gauge),
    Media("Медиа", Lucide.Music),
    Fuel("Расход", Lucide.ChartColumn),
    Gearbox("АКПП", Lucide.Cog),
    Battery("АКБ", Lucide.BatteryCharging),
    Dtc("Ошибки", Lucide.TriangleAlert),
    Sensors("Датчики", Lucide.ListChecks),
    Perf("Замеры", Lucide.Timer),
    Health("Мониторинг", Lucide.Activity),
    Log("Лог", Lucide.FileText),
    Apps("Приложения", Lucide.LayoutGrid),
    Settings("Настройки", Lucide.SlidersHorizontal),
}

/** Debug builds only, to see the check-engine icon with no car: adb … --ei pretendcodes 2 (0 takes them away); … --es openscreen Sensors. */
private fun android.app.Activity.pretendCodes(i: android.content.Intent) {
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) i.getStringExtra("openscreen")?.let { Repo.openScreen.value = System.nanoTime() to it }
    if (!i.hasExtra("pretendcodes") || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    Repo.checkEngine.value = kg.forester.sg.service.CheckEngine(engine = i.getIntExtra("pretendcodes", 0))
}

/** The open screen as it is kept over a recreated activity (rememberSaveable), and back; an unknown name is dropped. */
fun screenSaved(s: Screen): String = s.name
fun screenRestored(v: String): Screen? = Screen.values().firstOrNull { it.name == v }
private var exitReasonsLogged = false
private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>({ screenSaved(it) }, { screenRestored(it) })

class MainActivity : ComponentActivity() {
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        kg.forester.sg.ui.SplitScreen.on.value = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugWatch.start(this)
        Repo.init(this)
        kg.forester.sg.service.TapSound.init(applicationContext, Repo.settings.buttonSound)
        // told apart in a sent log: a start after a dead process, a recreated activity (saved=true), a plain launch
        SsmTrace.log("start: saved=${savedInstanceState != null} sdk=${android.os.Build.VERSION.SDK_INT}", keep = true)
        lastCfg = android.content.res.Configuration(resources.configuration)
        kg.forester.sg.ui.SplitScreen.on.value = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        logExitReasons()
        // adb: am start -n kg.forester.sg/.MainActivity --ez demo true [--ez skipSetup true]
        if (intent?.hasExtra("demo") == true) Repo.settings.demo = intent.getBooleanExtra("demo", false)
        // adb: … --ez linkdemo true: a made-up Link G4X on a CANable (what a sports-ECU car would see)
        if (intent?.hasExtra("linkdemo") == true) Repo.linkDemo.value = intent.getBooleanExtra("linkdemo", false)
        intent?.let(::pretendCodes)
        if (intent?.getBooleanExtra("skipSetup", false) == true) Repo.settings.setupDone = true
        window.dragsLikeFinger()
        testVin(intent)
        testAlert(intent)
        voiceTest(intent)
        kg.forester.sg.data.Shortcuts.load(this)
        @Suppress("DEPRECATION")
        kg.forester.sg.data.DeviceTier.watchFrames(window, windowManager.defaultDisplay.refreshRate)
        // once a day: is there a newer release on GitHub?
        Thread { runCatching { kg.forester.sg.data.Updater.checkDaily(applicationContext) } }.start()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // a phone on its side: the background goes under the camera cutout too (it was a black strip there); the
        // content keeps out of it by the cutout's inset (see App)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        // Phones get the full head-unit layout too, sideways (either way round) and scaled to fit.
        if (isPhone(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
        EcuService.start(this)
        // «Выход» in the service notification closes the screen too
        lifecycleScope.launch { EcuService.exitRequested.collect { finishAndRemoveTask() } }
        setContent {
            if (!kg.forester.sg.ui.languageReady()) return@setContent
            // one gallery launcher for gauge pictures at the root, so the result survives the closed picker dialog
            val pickGauge = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { kg.forester.sg.data.GaugeImages.onPicked(this, it) }
            androidx.compose.runtime.CompositionLocalProvider(
                kg.forester.sg.ui.LocalPickGaugeImage provides { target ->
                    kg.forester.sg.data.GaugeImages.setTarget(this, target)
                    pickGauge.launch("image/*")
                },
            ) {
                ScaledDensity {
                    kg.forester.sg.ui.SmoothRoot()
                    Box(Modifier.fillMaxSize()) {
                        // the «CarPulse» picture goes up first, so it is on the screen while the app itself is being
                        // composed, which is most of a cold start; the others come over the app as they always did
                        var appOn by remember { mutableStateOf(!kg.forester.sg.ui.splashComesFirst(this@MainActivity)) }
                        if (appOn) {
                            App()
                            var setup by remember { mutableStateOf(!Repo.settings.setupDone) }
                            if (setup) kg.forester.sg.ui.FirstRunSetup { setup = false }
                        }
                        kg.forester.sg.ui.StartSplash(appOn) { appOn = true }
                    }
                }
            }
        }
    }

    private var lastCfg: android.content.res.Configuration? = null

    /** What changed when the system no longer recreates the activity for it (see configChanges in the manifest). */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = lastCfg
        val diff = if (old == null) "?" else newConfig.diff(old).let { d ->
            listOf(
                "orientation" to android.content.pm.ActivityInfo.CONFIG_ORIENTATION, "screenSize" to android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE,
                "smallestScreenSize" to android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE, "screenLayout" to android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT,
                "uiMode" to android.content.pm.ActivityInfo.CONFIG_UI_MODE, "density" to android.content.pm.ActivityInfo.CONFIG_DENSITY,
                "fontScale" to android.content.pm.ActivityInfo.CONFIG_FONT_SCALE, "locale" to android.content.pm.ActivityInfo.CONFIG_LOCALE,
                "layoutDirection" to android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION, "keyboard" to android.content.pm.ActivityInfo.CONFIG_KEYBOARD,
                "keyboardHidden" to android.content.pm.ActivityInfo.CONFIG_KEYBOARD_HIDDEN, "navigation" to android.content.pm.ActivityInfo.CONFIG_NAVIGATION,
                "touchscreen" to android.content.pm.ActivityInfo.CONFIG_TOUCHSCREEN, "mcc" to android.content.pm.ActivityInfo.CONFIG_MCC,
                "mnc" to android.content.pm.ActivityInfo.CONFIG_MNC, "colorMode" to android.content.pm.ActivityInfo.CONFIG_COLOR_MODE,
            ).filter { d and it.second != 0 }.joinToString(",") { it.first }
        }
        SsmTrace.log("cfg: changed=$diff ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp density=${newConfig.densityDpi} " +
            "orientation=${newConfig.orientation} uiMode=${newConfig.uiMode} font=${newConfig.fontScale}", keep = true)
        lastCfg = android.content.res.Configuration(newConfig)
    }

    /** Why the previous runs of the process ended (the system's own record): a kill, a crash, a plain exit. */
    private fun logExitReasons() {
        // once per process: a recreated activity would repeat it, a restore after a background kill (saved != null) must not skip it
        if (android.os.Build.VERSION.SDK_INT < 30 || exitReasonsLogged) return
        exitReasonsLogged = true
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 5).forEach { r ->
                SsmTrace.log("exit: reason=${r.reason} status=${r.status} importance=${r.importance} at=${r.timestamp} ${r.description.orEmpty()}", keep = true)
            }
        }
    }

    // adb: am start -n kg.forester.sg/.MainActivity --es alert "текст" [--es level danger|warn|info]: how a warning looks
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("demo")) Repo.settings.demo = intent.getBooleanExtra("demo", false)
        if (intent.hasExtra("linkdemo")) Repo.linkDemo.value = intent.getBooleanExtra("linkdemo", false)
        pretendCodes(intent)
        testVin(intent)
        testAlert(intent)
        voiceTest(intent)
    }

    /**
     * adb: am start -n kg.forester.sg/.MainActivity --ez demo true --es vin JTDKB20U093123456: the car as if its ECU
     * gave this VIN (another make: the header, the logo, «другая машина»); --es vin "" forgets it.
     */
    private fun testVin(i: android.content.Intent?) {
        // adb: … --es obdEcu "ECM-EngineControl · CALID 89663-12345": what OBD mode 09 would tell (Настройки → Система)
        i?.getStringExtra("obdEcu")?.let { Repo.obdEcu.value = it }
        // adb: … --es uiTheme studio: the look (neon, light, studio, midnight, nightrace, mint, noir, lagoon, showroom), as in Настройки → Внешний вид
        i?.getStringExtra("uiTheme")?.let { Repo.settings.uiTheme = it }
        // adb: … --es miniPlayer top: bottom, top, off, as in Настройки → Внешний вид
        i?.getStringExtra("miniPlayer")?.let { Repo.settings.miniPlayer = it }
        // adb: … --es distanceUnit mi: «Единицы расстояния», as in Настройки → Внешний вид
        i?.getStringExtra("distanceUnit")?.let { Repo.settings.distanceUnit = it }
        // adb: … --ei textScale 150: «Размер текста», as in Настройки → Внешний вид
        i?.takeIf { it.hasExtra("textScale") }?.let { Repo.settings.textScale = it.getIntExtra("textScale", 100) }
        val vin = i?.getStringExtra("vin") ?: return
        Repo.vin.value = vin.trim().uppercase()
        if (vin.isBlank()) Repo.settings.lastVin = ""
    }

    /**
     * The assistant without speaking (the app open or not):
     * adb: am start -n kg.forester.sg/.MainActivity --es voiceText "найди видео на ютубе мистер бист" (a command as if said)
     * adb: am start -n kg.forester.sg/.MainActivity --es orbDemo "включи музло" (the listening orb as the words come,
     * then the command itself, as when it is said)
     */
    private fun voiceTest(i: android.content.Intent?) {
        i?.getStringExtra("voiceText")?.let { kg.forester.sg.service.Voice.runText(this, it) }
        i?.getStringExtra("orbDemo")?.let { kg.forester.sg.service.Voice.rehearse(this, it) }
        // adb: … --es navHint "300 м|Поверните направо на Киевскую": a navigator's turn hint («Приборка с картой»); "" clears it
        i?.getStringExtra("navHint")?.let { h ->
            kg.forester.sg.service.NavGuidance.hint.value = if (h.isEmpty()) null
            else kg.forester.sg.service.NavHint("adb", h.substringBefore('|'), h.substringAfter('|', ""), "", null)
        }
    }

    private fun testAlert(i: android.content.Intent?) {
        val text = i?.getStringExtra("alert") ?: return
        val level = when (i.getStringExtra("level")) {
            "danger" -> kg.forester.sg.service.Alerts.Level.DANGER
            "info" -> kg.forester.sg.service.Alerts.Level.INFO
            else -> kg.forester.sg.service.Alerts.Level.WARN
        }
        kg.forester.sg.service.Alerts.raise("test" + System.currentTimeMillis(), text, level, 0L, speak = false)
    }

    override fun onStart() {
        super.onStart()
        EcuService.start(this)
        uiRecomposer()?.resumeCompositionFrameClock()
        // it can wait for a model being read: not on this thread
        Thread { runCatching { kg.forester.sg.ui.showCarFrames() } }.start()
    }

    /** What composes this window; a lifecycle-aware one, but it went on composing every card behind the launcher (seen by counting). */
    private fun uiRecomposer() =
        findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)?.findViewTreeCompositionContext() as? androidx.compose.runtime.Recomposer

    /** Memory is short (2 GB head units, next to CarPlay and a navigator), or the app has left the screen. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SsmTrace.log("memory: trim level=$level", keep = true)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) giveMemoryBack(level)
    }

    private fun giveMemoryBack(level: Int) {
        Thread {
            runCatching { kg.forester.sg.ui.trimCarFrames(level) }
            runCatching { kg.forester.sg.ui.trimBoardFrames() }
            runCatching { kg.forester.sg.ui.trimUiCaches() }
            kg.forester.sg.service.Speech.trim()
            // back on the screen before this thread got to it: the frames must not stay given up
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) kg.forester.sg.ui.showCarFrames()
        }.start()
    }

    override fun onResume() {
        super.onResume()
        kg.forester.sg.ui.SplitScreen.on.value = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode
        kg.forester.sg.service.WakeWatch.appOnScreen = true
    }

    override fun onPause() {
        super.onPause()
        kg.forester.sg.service.WakeWatch.appOnScreen = false
    }

    // with background work off, the service (polling, logging) stops once the app leaves the screen
    override fun onStop() {
        super.onStop()
        if (!Repo.settings.backgroundWork && !isChangingConfigurations) {
            stopService(android.content.Intent(this, EcuService::class.java))
        }
        // off the screen nothing composes and no frame clock runs: the values are read again when it comes back
        if (!isChangingConfigurations) uiRecomposer()?.pauseCompositionFrameClock()
        // the system says UI_HIDDEN only to a process that drops in priority, and the foreground service keeps this one up
        if (!isChangingConfigurations) giveMemoryBack(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // The head unit brings its bars back after dialogs/app switches; hide them again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Let the running service pick up GPS once location is granted.
        EcuService.start(this, EcuService.ACTION_START_GPS)
    }
}

/**
 * Every head unit draws on the same canvas of about 1180×660 dp, from its panel's pixels: what it reports about its dpi
 * says nothing (a 2000×1200 at 160 dpi came out smaller than a 7"), a window beside the navigator doesn't change it, it
 * only gets the compact layout. A scale in the settings goes on top, never so far as to lose the wide layout.
 *
 * A phone is that shrink by nature (the whole layout on a 6" screen), so there the scale goes up from it: 100 % =
 * everything on the screen, more = bigger at the cost of room, never so big that the wide layout gives way to the
 * compact one. The text follows the system font size there too (within reason: the cards have fixed room).
 *
 * The sizes of text and targets ([kg.forester.sg.ui.CarTokens]) go with the diagonal the driver told; a phone is held
 * nearer and keeps the tokens of the unknown screen.
 */
@Composable
private fun ScaledDensity(content: @Composable () -> Unit) {
    val scale by Repo.uiScale.collectAsState()
    val inches by Repo.screenInches.collectAsState()
    val textPct by Repo.textScale.collectAsState()
    val base = androidx.compose.ui.platform.LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val phone = isPhone(ctx)
    val panel = remember(cfg.screenWidthDp, cfg.screenHeightDp, cfg.densityDpi) { panelPx(ctx) }
    val density = remember(scale, textPct, base, panel, phone, cfg.screenWidthDp, cfg.screenHeightDp, cfg.smallestScreenWidthDp) {
        if (phone) {
            val fit = minOf(cfg.screenWidthDp * base.density / 1180f, cfg.screenHeightDp * base.density / 660f)
            // only in landscape: an upright phone gets the compact panel instead
            val landscape = cfg.screenWidthDp > cfg.screenHeightDp
            val auto = if (landscape && fit < base.density && fit >= base.density * 0.5f) fit else base.density
            val d = auto * scale.coerceAtLeast(100) / 100f
            val widest = if (landscape) cfg.screenWidthDp * base.density / 920f else d
            androidx.compose.ui.unit.Density(minOf(d, widest).coerceAtLeast(auto), base.fontScale.coerceIn(0.85f, 1.3f) * textPct / 100f)
        } else androidx.compose.ui.unit.Density(kg.forester.sg.ui.carDensity(panel.first, panel.second, scale), textPct / 100f)
    }
    val tokens = remember(inches, panel, phone) {
        kg.forester.sg.ui.CarTokens(if (phone) kg.forester.sg.ui.MM_PER_DP_UNKNOWN else kg.forester.sg.ui.carMmPerDp(inches, panel.first, panel.second))
    }
    androidx.compose.runtime.SideEffect { kg.forester.sg.ui.CarScreen.small = tokens.small }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides density,
        kg.forester.sg.ui.LocalCar provides tokens,
        androidx.compose.material3.LocalTextStyle provides C.palette.fieldStyle(androidx.compose.material3.LocalTextStyle.current),
    ) {
        content()
    }
}

/** The whole panel in pixels, also with this window beside another one. */
private fun panelPx(ctx: android.content.Context): Pair<Int, Int> {
    val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    if (android.os.Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    val p = android.graphics.Point()
    @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
    return p.x to p.y
}

private val phoneKnown = kg.forester.sg.ui.OnceKnown<Boolean>()

/**
 * See [kg.forester.sg.ui.isPhoneBy]; once per process, called on the main thread at start. The cheap signs go first:
 * only what looks like a phone by all of them is asked about the head-unit families' packages, directly, not through
 * [kg.forester.sg.service.audio.AudioPlatforms], whose lock the detection thread holds for its probes.
 */
fun isPhone(ctx: android.content.Context): Boolean = phoneKnown.get {
    val app = ctx.applicationContext
    val cfg = app.resources.configuration
    val display = app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
    val size = android.graphics.Point()
    @Suppress("DEPRECATION") display.getRealSize(size)
    val turned = display.rotation.let { it == android.view.Surface.ROTATION_90 || it == android.view.Surface.ROTATION_270 }
    val small = cfg.smallestScreenWidthDp < 600
    val telephony = app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    val naturalLandscape = (size.x > size.y) != turned
    val family = if (small && telephony && !naturalLandscape) kg.forester.sg.service.audio.quickFamily(app) else kg.forester.sg.service.audio.GENERIC
    kg.forester.sg.ui.isPhoneBy(family, telephony, cfg.smallestScreenWidthDp, naturalLandscape)
}

/** Phone menu: all screens in a pop-up list (the compact panel has no side rail). */
@Composable
private fun PhoneMenu(onClose: () -> Unit, onSelect: (Screen) -> Unit) {
    var exit by remember { mutableStateOf(false) }
    if (exit) kg.forester.sg.ui.ExitDialog { exit = false; onClose() }
    val car = kg.forester.sg.ui.LocalCar.current
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onClose),
    ) {
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 10.dp)
                .width(car.label.value.dp * 8f + 60.dp).clip(RoundedCornerShape(16.dp)).background(C.Sheet)
                .border(1.dp, C.Border, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            visibleScreens().forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = car.tap).tapClickable { onSelect(s) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    T(s.title, car.label, maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(1.dp).background(C.Border))
            Row(
                Modifier.fillMaxWidth().heightIn(min = car.tap).tapClickable { exit = true }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Power, null, tint = C.Sub, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                T(kg.forester.sg.data.I18n.pick("Выход", "Exit"), car.label, maxLines = 1)
            }
        }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var screen by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(if (kg.forester.sg.ui.isDefaultHome(ctx)) Screen.Home else Screen.Fuel)
    }
    // Back goes to the Home screen first; as the home app we never close ourselves.
    val menuShown = visibleScreens()
    // home, or the first in the menu with Главная hidden
    val backTo = Screen.valueOf(kg.forester.sg.data.NavMenu.backTarget(Screen.Home.name, menuShown.map { it.name }))
    androidx.activity.compose.BackHandler(enabled = screen != backTo || kg.forester.sg.ui.isDefaultHome(ctx)) {
        screen = backTo
    }
    // hidden in the editor while it was showing, or at the start: to the first visible. Voice and links still go to a hidden one
    val navMenu by Repo.navMenu.collectAsState()
    LaunchedEffect(navMenu) {
        kg.forester.sg.data.NavMenu.afterHiding(screen.name, menuShown.map { it.name })?.let { screen = Screen.valueOf(it) }
    }
    val voiceNav by kg.forester.sg.service.Voice.navigate.collectAsState()
    LaunchedEffect(voiceNav) {
        voiceNav?.second?.let {
            screen = when (it) {
                kg.forester.sg.service.VoiceTarget.HOME -> Screen.Home
                kg.forester.sg.service.VoiceTarget.DRIVE -> Screen.Drive
                kg.forester.sg.service.VoiceTarget.FUEL -> Screen.Fuel
                kg.forester.sg.service.VoiceTarget.GEARBOX ->
                    if (Repo.settings.gearbox == "mt" || kg.forester.sg.data.CarKind.other.value) screen else Screen.Gearbox
                kg.forester.sg.service.VoiceTarget.BATTERY -> Screen.Battery
                kg.forester.sg.service.VoiceTarget.DTC -> Screen.Dtc
                kg.forester.sg.service.VoiceTarget.MEDIA -> Screen.Media
            }
        }
    }
    val opened by Repo.openScreen.collectAsState()
    LaunchedEffect(opened) { opened?.let { (_, name) -> screenRestored(name)?.let { screen = it } } }
    kg.forester.sg.ui.ThemeSizes()
    // one for the app's life on the screen: only its "known" is read out here, the strip reads the rest
    val mini = remember { kg.forester.sg.ui.MiniPlayerState() }
    // a look at the other players' sessions every 2 s is for the strip on the screen: none of it behind the launcher
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(mini, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { mini.run(ctx) }
    }
    val miniWhere by Repo.miniPlayer.collectAsState()
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().themeBackdrop(C.palette, C.flat).windowInsetsPadding(WindowInsets.displayCutout) // the background under the cutout, the content beside it
    ) {
        // Split screen next to the navigator: switch to the compact driver layout instead of squeezing.
        // Narrow: split screen next to the navigator on the head unit, or a phone held upright.
        if (maxWidth < 900.dp) {
            var menu by remember { mutableStateOf(false) }
            val phone = isPhone(ctx)
            // the driver's panel first, whatever was open in the wide layout; a screen only when asked for from the menu
            var picked by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(Screen.Home) }
            // Back from a screen picked in a narrow window goes to the driver's panel; the App's own handler would only set
            // `screen`, which nothing here shows, and leave this one on the screen
            androidx.activity.compose.BackHandler(enabled = picked != Screen.Home) { picked = Screen.Home; screen = Screen.Home }
            LaunchedEffect(opened) { opened?.let { (_, name) -> screenRestored(name)?.let { picked = it } } }
            val narrowSlot = kg.forester.sg.service.miniSlot(miniWhere, onMedia = !phone && picked == Screen.Media, editingFree = false, shown = mini.known)
            Column(Modifier.fillMaxSize()) {
                if (narrowSlot == kg.forester.sg.service.MiniSlot.TOP) kg.forester.sg.ui.MiniPlayerStrip(mini) { picked = Screen.Media; screen = Screen.Media }
                Box(Modifier.weight(1f)) {
                    if (!phone && picked != Screen.Home) NarrowScreen(picked) { menu = true }
                    else kg.forester.sg.ui.CompactScreen(kg.forester.sg.ui.compactChrome(phone)) { menu = true }
                }
                if (narrowSlot == kg.forester.sg.service.MiniSlot.BOTTOM) kg.forester.sg.ui.MiniPlayerStrip(mini) { picked = Screen.Media; screen = Screen.Media }
            }
            if (menu) PhoneMenu(onClose = { menu = false }) { s ->
                menu = false
                screen = s
                // a phone turns on its side for a full screen, which is laid out for landscape; a head unit's window stays
                if (phone && s != Screen.Home) (ctx as? android.app.Activity)?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else picked = s
            }
            kg.forester.sg.ui.VoiceToast(Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
            DriverOverlays()
            return@BoxWithConstraints
        }
        val navPos by Repo.navPosition.collectAsState()
        // the tap shows at once: this frame has the button lit, the next one composes the new screen, whatever it weighs
        var shownScreen by remember { mutableStateOf(screen) }
        LaunchedEffect(screen) {
            if (shownScreen != screen) {
                androidx.compose.runtime.withFrameNanos { }
                shownScreen = screen
            }
        }
        val content: @Composable (Modifier) -> Unit = { m ->
            Box(m.carouselSwipe(screen) { screen = it }) {
                ScreenBody(shownScreen)
            }
        }
        // editing a free layout: the bottom bar's room goes to the area, so the grid keeps its scale under the edit bar
        val freeEditing by Repo.freeEditing.collectAsState()
        val slot = kg.forester.sg.service.miniSlot(miniWhere, screen == Screen.Media, freeEditing, mini.known)
        val openMedia = { screen = Screen.Media }
        when (navPos) {
            "top", "bottom" -> Column(Modifier.fillMaxSize().padding(10.dp)) {
                TopBar(screen, logo = true)
                Spacer(Modifier.height(8.dp))
                if (navPos == "top") { NavBar(screen) { screen = it }; Spacer(Modifier.height(8.dp)) }
                if (slot == kg.forester.sg.service.MiniSlot.TOP) kg.forester.sg.ui.MiniPlayerSlot(mini, 8.dp, above = false, openMedia)
                content(Modifier.weight(1f))
                if (slot == kg.forester.sg.service.MiniSlot.BOTTOM) kg.forester.sg.ui.MiniPlayerSlot(mini, 8.dp, above = true, openMedia)
                if (!freeEditing) { Spacer(Modifier.height(8.dp)); BottomBar() }
                if (navPos == "bottom") { Spacer(Modifier.height(8.dp)); NavBar(screen) { screen = it } }
            }
            // a slanted rail's straight edge lies against the screen's side, so on that side there's no margin
            else -> Row(Modifier.fillMaxSize().padding(start = if (C.palette.navSlant && navPos != "right") 0.dp else 10.dp, top = 10.dp, end = if (C.palette.navSlant && navPos == "right") 0.dp else 10.dp, bottom = 10.dp)) {
                if (navPos != "right") { NavRail(screen) { screen = it }; Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(screen, logo = navPos == "right")
                    Spacer(Modifier.height(10.dp))
                    if (slot == kg.forester.sg.service.MiniSlot.TOP) kg.forester.sg.ui.MiniPlayerSlot(mini, 10.dp, above = false, openMedia)
                    content(Modifier.weight(1f))
                    if (slot == kg.forester.sg.service.MiniSlot.BOTTOM) kg.forester.sg.ui.MiniPlayerSlot(mini, 10.dp, above = true, openMedia)
                    if (!freeEditing) { Spacer(Modifier.height(10.dp)); BottomBar() }
                }
                if (navPos == "right") { Spacer(Modifier.width(10.dp)); NavRail(screen) { screen = it } }
            }
        }
        kg.forester.sg.ui.VoiceToast(Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        kg.forester.sg.ui.VoiceOrb()
        DriverOverlays(floating = false)
    }
}

@Composable
private fun ScreenBody(screen: Screen) {
    when (screen) {
        Screen.Home -> Box {
            HomeScreen()
            kg.forester.sg.ui.DiagonalPrompt()
        }
        Screen.Mine -> kg.forester.sg.ui.MyScreen()
        Screen.Drive -> kg.forester.sg.ui.DriveScreen()
        // the text's floor, and the player's own controls, which keep their sizes and places at a glance
        Screen.Media -> kg.forester.sg.ui.Parked(tap = 48.dp) { kg.forester.sg.ui.MediaScreen() }
        Screen.Fuel -> kg.forester.sg.ui.Parked { FuelScreen() }
        Screen.Gearbox -> kg.forester.sg.ui.Parked { kg.forester.sg.ui.TransmissionScreen() }
        Screen.Battery -> kg.forester.sg.ui.Parked { kg.forester.sg.ui.BatteryScreen() }
        Screen.Dtc -> kg.forester.sg.ui.Parked { DtcScreen() }
        Screen.Sensors -> kg.forester.sg.ui.Parked { kg.forester.sg.ui.SensorsScreen() }
        Screen.Perf -> kg.forester.sg.ui.PerfScreen()
        Screen.Health -> kg.forester.sg.ui.Parked { kg.forester.sg.ui.HealthScreen() }
        Screen.Log -> kg.forester.sg.ui.Parked { LogScreen() }
        Screen.Apps -> kg.forester.sg.ui.Parked { kg.forester.sg.ui.AppsScreen() }
        Screen.Settings -> kg.forester.sg.ui.Parked { SettingsScreen() }
    }
}

/** A screen picked from the menu in a window too narrow for the wide layout (next to the navigator, or held upright). */
@Composable
private fun NarrowScreen(screen: Screen, onMenu: () -> Unit) {
    val car = kg.forester.sg.ui.LocalCar.current
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(car.tap).clip(RoundedCornerShape(10.dp)).background(C.CardHi).tapClickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Menu, "Меню", tint = C.Text, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            T(screen.title, car.label, weight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        // the screens are laid out for the wide canvas: smaller here, but whole, until the window is wide again
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val base = androidx.compose.ui.platform.LocalDensity.current
            val narrow = androidx.compose.ui.unit.Density(base.density * maxWidth.value / 980f, base.fontScale)
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides narrow) {
                ScreenBody(screen)
            }
        }
    }
}

/**
 * «Карусель»: a sideways swipe on the screen goes to the next (left) or previous (right) of the screens picked in the
 * settings, round and round. Only a swipe nothing on the screen took for itself (sliders, 3D models, a scrolled
 * row, cards being dragged) counts; dots at the bottom show where in the round the driver is.
 */
@Composable
private fun Modifier.carouselSwipe(screen: Screen, go: (Screen) -> Unit): Modifier {
    val names by Repo.carousel.collectAsState()
    // the screens there are: no АКПП on a manual or on another make, no switched-off ones. Hiding from the menu leaves it in the round picked
    val visible = availableScreens()
    val round = names.mapNotNull { n -> Screen.entries.firstOrNull { it.name == n } }.filter { it in visible }
    if (round.size < 2 || screen !in round) return this
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val i = round.indexOf(screen)
    return this
        .pointerInput(round, screen) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
                var dx = 0f
                var dy = 0f
                var taken = false
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                    if (ev.changes.size > 1) taken = true // two fingers: a pinch, not a swipe
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (c.isConsumed) taken = true
                    dx += c.position.x - c.previousPosition.x
                    dy += c.position.y - c.previousPosition.y
                    if (!c.pressed) break
                }
                if (!taken && kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > 2 * kotlin.math.abs(dy)) {
                    go(round[(i + if (dx < 0) 1 else round.size - 1) % round.size])
                }
            }
        }
        .drawWithContent {
            drawContent()
            // where in the round we are: small dots at the bottom middle
            val r = 3.5.dp.toPx()
            val gap = 14.dp.toPx()
            val x0 = size.width / 2 - gap * (round.size - 1) / 2
            round.indices.forEach { k ->
                drawCircle(if (k == i) C.Hair.copy(alpha = 0.85f) else C.Hair.copy(alpha = 0.25f), r,
                    androidx.compose.ui.geometry.Offset(x0 + k * gap, size.height - r - 2.dp.toPx()))
            }
        }
}

/** The menu: what there is, in the driver's order, without what he hid. */
@Composable
private fun visibleScreens(): List<Screen> {
    val there = availableScreens()
    val menu by Repo.navMenu.collectAsState()
    return kg.forester.sg.data.NavMenu.visible(menu, Screen.entries.map { it.name }).mapNotNull { n -> there.firstOrNull { it.name == n } }
}

/** A manual gearbox has no transmission unit, so no АКПП screen. */
@Composable
internal fun availableScreens(): List<Screen> {
    Repo.carVersion.collectAsState().value
    Repo.featureVersion.collectAsState().value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list = Screen.entries.toList()
    if (Repo.settings.gearbox == "mt") list = list - Screen.Gearbox
    // another make: the АКПП screen reads Subaru's transmission unit (SSM), which it doesn't have
    if (kg.forester.sg.data.CarKind.other.collectAsState().value) list = list - Screen.Gearbox
    // Media drives any player (Yandex Music, VK…) through its media session, radio only on FYT head units
    if (isPhone(ctx)) list = list - Screen.Apps // the app launcher is for head units
    val s = Repo.settings
    if (!s.feature("sensors")) list = list - Screen.Sensors
    if (!s.feature("perf")) list = list - Screen.Perf
    if (!s.feature("health")) list = list - Screen.Health
    if (!s.feature("mine")) list = list - Screen.Mine
    return list
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shortcuts by kg.forester.sg.data.Shortcuts.list.collectAsState()
    val scroll = androidx.compose.foundation.rememberScrollState()
    // The logo in the corner, the scrolling area below it; fades hint that more buttons are above/below.
    val car = kg.forester.sg.ui.LocalCar.current
    var editor by remember { mutableStateOf(false) }
    if (editor) kg.forester.sg.ui.MenuEditorDialog { editor = false }
    val openEditor = { editor = true }
    val navPos by Repo.navPosition.collectAsState()
    val side = if (navPos == "right") kg.forester.sg.ui.SlantSide.RIGHT else kg.forester.sg.ui.SlantSide.LEFT
    Box(Modifier.width(railWidth(car)).fillMaxHeight().chromeLayer()) {
        val screens = visibleScreens()
        // «Настройки» stays at the foot of the rail whatever the scroll: the way out of the layout is never lost
        val pinned = Screen.Settings.takeIf { it in screens }
        val scrolled = screens.filter { it != pinned }
        // the tap token stays whatever the height: 13 buttons fit no real screen, so the list scrolls under the pinned one
        val itemH = car.tap
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(headerHeight(car)), contentAlignment = Alignment.Center) {
                kg.forester.sg.ui.BrandLogo(Modifier.size(width = 86.dp, height = 46.dp))
            }
            Spacer(Modifier.height(10.dp))
            // over a drawn backdrop the ends fade out by mask, no colour would match it; on a plain one as they always did
            val masked = C.palette.backdrop != kg.forester.sg.ui.Palette.Backdrop.PLAIN && !C.flat
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(Modifier.fillMaxWidth().weight(1f).navBarFill().then(
                if (masked) with(density) { Modifier.fadeEnds(scroll.value > 0, scroll.value < scroll.maxValue, 28.dp.toPx(), 36.dp.toPx()) } else Modifier,
            )) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(RAIL_GAP.dp),
                ) {
                    scrolled.forEach { s ->
                        RailItem(s.icon, s.title, s == current, minHeight = itemH, onLong = openEditor, inScroll = true, side = side) { onSelect(s) }
                    }
                    if (shortcuts.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).height(1.dp).background(C.Border))
                        shortcuts.forEach { sc ->
                            RailItem(kg.forester.sg.data.Shortcuts.icon(sc.icon), sc.label, false, accent = true, minHeight = itemH, side = side) {
                                kg.forester.sg.data.Shortcuts.launch(ctx, sc)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (!masked && scroll.value > 0) {
                    Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(C.palette.railTop, Color.Transparent))))
                }
                if (!masked && scroll.value < scroll.maxValue) {
                    Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, C.palette.railFoot))))
                }
            }
            if (pinned != null) Column(Modifier.navBarFill()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp).height(1.dp).background(C.Border))
                RailItem(pinned.icon, pinned.title, pinned == current, minHeight = itemH, onLong = openEditor, side = side) { onSelect(pinned) }
            }
        }
    }
}

private fun railShape(side: kg.forester.sg.ui.SlantSide) = if (C.palette.navSlant) kg.forester.sg.ui.slantShape(RAIL_LEAN, side) else RoundedCornerShape(14.dp)

/** How far a slanted item leans: its text stays inside. */
private val RAIL_LEAN = 6.dp

/** The air a slanted item leaves between its label and its edges. */
private val SLANT_AIR = 3.dp

/** The gap between the rail's buttons. */
private const val RAIL_GAP = 3

/** How far the rail's fade reaches at its ends: the selected item stays clear of it. */
private val RAIL_FADE = 36.dp

/** Horizontal navigation for the top/bottom positions; scrolls when there are many buttons. */
@Composable
private fun NavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shortcuts by kg.forester.sg.data.Shortcuts.list.collectAsState()
    val scroll = androidx.compose.foundation.rememberScrollState()
    val car = kg.forester.sg.ui.LocalCar.current
    var editor by remember { mutableStateOf(false) }
    if (editor) kg.forester.sg.ui.MenuEditorDialog { editor = false }
    Box(
        Modifier.fillMaxWidth().height(maxOf(62.dp, car.tap)).clip(kg.forester.sg.ui.Rounded).background(C.palette.navBar ?: C.Card)
            .border(1.dp, C.Border, kg.forester.sg.ui.Rounded)
    ) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleScreens().forEach { s ->
                Box(Modifier.width(railWidth(car))) { RailItem(s.icon, s.title, s == current, minHeight = maxOf(56.dp, car.tap - 6.dp), onLong = { editor = true }) { onSelect(s) } }
            }
            if (shortcuts.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(34.dp).background(C.Border))
                shortcuts.forEach { sc ->
                    Box(Modifier.width(railWidth(car))) {
                        RailItem(kg.forester.sg.data.Shortcuts.icon(sc.icon), sc.label, false, accent = true, minHeight = maxOf(56.dp, car.tap - 6.dp)) {
                            kg.forester.sg.data.Shortcuts.launch(ctx, sc)
                        }
                    }
                }
            }
        }
        if (scroll.value > 0) {
            Box(Modifier.fillMaxHeight().width(28.dp).align(Alignment.CenterStart)
                .background(Brush.horizontalGradient(listOf(C.palette.navBar ?: C.Card, Color.Transparent))))
        }
        if (scroll.value < scroll.maxValue) {
            Box(Modifier.fillMaxHeight().width(36.dp).align(Alignment.CenterEnd)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, C.palette.navBar ?: C.Card))))
        }
    }
}

/** The side rail: as wide as the longest screen's name («Приложения») in the secondary size. */
private fun railWidth(car: kg.forester.sg.ui.CarTokens) =
    // a slanted item's face is wider and its shape takes more of the room: the longest name must not reach the least size and go to «…»
    if (C.palette.navSlant) maxOf(96.dp, car.secondary.value.dp * 6.1f * Repo.textScale.value / 100f) + 22.dp
    else maxOf(96.dp, car.secondary.value.dp * 5.6f * Repo.textScale.value / 100f) + if (C.palette.race) 12.dp else 0.dp

/** The top bar: the car's name in the label size over its line in the secondary one; 60 dp was enough on 10". */
private fun headerHeight(car: kg.forester.sg.ui.CarTokens) = maxOf(60.dp, car.tap, (car.label.value + car.secondary.value).dp * 1.2f + 4.dp)

/**
 * A screen's button: icon over its name, [minHeight] tall (the tap token in the side rail, about the size of a
 * fingertip pressed on a moving car; a list taller than the screen scrolls).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RailItem(
    icon: ImageVector, title: String, selected: Boolean, accent: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = kg.forester.sg.ui.LocalCar.current.tap, onLong: (() -> Unit)? = null, inScroll: Boolean = false,
    side: kg.forester.sg.ui.SlantSide = kg.forester.sg.ui.SlantSide.BAR, onClick: () -> Unit,
) {
    val car = kg.forester.sg.ui.LocalCar.current
    val shape = railShape(side)
    val slant = C.palette.navSlant
    val lean = RAIL_LEAN.value * 2f
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        (if (inScroll) Modifier.keepInView(selected, RAIL_FADE) else Modifier).fillMaxWidth().heightIn(min = minHeight)
            .then(if (selected) Modifier.activeHalo(12.dp) else Modifier)
            .clip(shape)
            .then(if (selected) Modifier.activeGlow() else Modifier)
            .background(if (selected) C.activeFill(C.Purple.copy(alpha = 0.3f)) else C.palette.navItem ?: Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, C.activeEdge(C.Purple), shape) else Modifier)
            .then(
                if (onLong == null) Modifier.tapClickable(onClick = onClick)
                else Modifier.combinedClickable(
                    onClick = { kg.forester.sg.service.TapSound.tap(); onClick() },
                    // the menu's editor; a screen's button also says so with the finger
                    onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLong() },
                ),
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (selected) C.activeText(C.Violet) else C.navText(if (accent) C.Blue else C.Sub),
            modifier = (if (slant) Modifier.offset(x = kg.forester.sg.ui.slantIconShift(side, lean).dp) else Modifier).size(24.dp))
        Spacer(Modifier.height(2.dp))
        kg.forester.sg.ui.FitText(
            title, car.secondary,
            // the label sits low in the item, where the slant has moved it sideways: its room is what the shape leaves there
            if (slant) kg.forester.sg.ui.slantLabelInsets(side, lean).let { (start, end) -> Modifier.fillMaxWidth().padding(start = start.dp + SLANT_AIR, end = end.dp + SLANT_AIR) }
            else Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            if (selected) C.activeText(C.Text) else C.navText(C.Sub),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun TopBar(screen: Screen, logo: Boolean) {
    // the text, not the moment: the clock costs a frame once a minute, not every look at it
    var now by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).format(Date())) }
    LaunchedEffect(Unit) { while (true) { now = SimpleDateFormat("HH:mm", Locale.US).format(Date()); delay(5000) } }

    val car = kg.forester.sg.ui.LocalCar.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val homeApp = remember { kg.forester.sg.ui.isDefaultHome(ctx) }
    val demoOn by Repo.demoOn.collectAsState()
    val pill = kg.forester.sg.ui.rememberStatusPill()
    val topCfg by Repo.topBar.collectAsState()
    val wanted = remember(topCfg) { kg.forester.sg.ui.BottomBarCfg.parse(topCfg).take(kg.forester.sg.ui.BottomBarCfg.TOP_MAX).size }
    val showLogo = logo && kg.forester.sg.ui.logoShown()
    // in a wider face the same name takes more of the row
    // a theme's own wider type needs the room for the caption under the name too, at its smaller size
    val carHeader = kg.forester.sg.ui.rememberCarHeader()
    val titleChars = kotlin.math.ceil(maxOf(carHeader.first.length.toFloat(), if (C.palette.font != null) carHeader.second.length * 0.72f else 0f) * maxOf(C.palette.charEm, 0.62f) / 0.62f).toInt()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(headerHeight(car)).chromeLayer()) {
        // the room is given out by priority (see headerPlan): the buttons, the clock and the exit keep theirs, «нет связи»
        // is read whole, and what gives way when it runs out is the top values first, then the car's title
        val plan = kg.forester.sg.ui.headerPlan(
            maxWidth.value, car.tap.value, car.label.asDp().value, car.secondary.asDp().value,
            showLogo, demoOn, pill != null, wanted, titleChars, kg.forester.sg.ui.rememberCheckEngineShown(),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
          // the left part is the one that gives way: a Row measures what has no weight first, so the buttons, the clock and
          // the exit on the right always have their room, and what doesn't fit is cut here
          Row(Modifier.weight(1f).fillMaxHeight().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            // with the side rail on the left the logo sits in the corner above it (see NavRail)
            if (showLogo) {
                kg.forester.sg.ui.BrandLogo(Modifier.size(width = 96.dp, height = 50.dp))
                Spacer(Modifier.width(16.dp))
            } else Spacer(Modifier.width(4.dp))
            // a long model from the ECU ("IMPREZA STI SPEC C") must not push the values aside: a smaller font, then «…»
            Column(Modifier.width(plan.nameDp.dp)) {
                val (title, sub) = kg.forester.sg.ui.rememberCarHeader()
                val noLink = kg.forester.sg.ui.rememberNoLink()
                kg.forester.sg.ui.FitText(title, car.label, Modifier.fillMaxWidth(), weight = FontWeight.Bold)
                // two seconds without the ECU: the values are wiped (see LinkLoss), and the screen says why
                kg.forester.sg.ui.FitText(kg.forester.sg.data.noLinkText(noLink) ?: sub, car.secondary, Modifier.fillMaxWidth(), if (noLink) C.Orange else C.Sub,
                    weight = if (noLink) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.width(14.dp))
            kg.forester.sg.ui.TopValues(if (plan.values > 0) Modifier.width(plan.valuesDp.dp) else Modifier.weight(1f), limit = plan.values)
            if (plan.demoDp > 0f) kg.forester.sg.ui.DemoPill(short = true, Modifier.width(plan.demoDp.dp)) // the caption under the name says «демо-данные» already
            if (pill != null && plan.statusDp > 0f) kg.forester.sg.ui.StatusPill(pill, Modifier.width(plan.statusDp.dp))
          }
            if (plan.checkDp > 0f) kg.forester.sg.ui.CheckEngineIcon()
            kg.forester.sg.ui.ThemeButton(22.dp)
            Spacer(Modifier.width(16.dp))
            if (plan.readings) {
                TopReadings()
                Spacer(Modifier.width(18.dp))
            }
            kg.forester.sg.ui.GpsIcon(22.dp)
            Spacer(Modifier.width(12.dp))
            LinkIcon(24.dp)
            Spacer(Modifier.width(22.dp))
            T(now, car.label, weight = FontWeight.SemiBold, maxLines = 1)
            // at the far right, away from the status icons; as the head unit's home screen the app is never closed
            if (!homeApp) {
                Spacer(Modifier.width(14.dp))
                kg.forester.sg.ui.ExitButton(22.dp)
            } else Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Battery voltage and coolant in the header, each read on its own: a reading recomposes these two numbers,
 * not the whole header with the car's name, the logo and the icons.
 */
@Composable
private fun TopReadings() {
    val live = Repo.live.collectAsState()
    val volts by kg.forester.sg.ui.rememberDigits { fmt(live.value.batteryV?.toDouble()) }
    val coolant by kg.forester.sg.ui.rememberDigits { live.value.coolantC?.toString() ?: "—" }
    val size = kg.forester.sg.ui.LocalCar.current.label
    // slots as wide as the longest reading («14.1 В», «105°C»): a digit more doesn't move the buttons beside them
    Icon(Lucide.BatteryCharging, null, tint = C.Text, modifier = Modifier.size(22.dp))
    Box(Modifier.width(size.asDp() * kg.forester.sg.ui.READING_SLOT_EM)) {
        T(" $volts В", size, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    Spacer(Modifier.width(22.dp))
    Icon(Lucide.Thermometer, null, tint = C.Text, modifier = Modifier.size(22.dp))
    Box(Modifier.width(size.asDp() * kg.forester.sg.ui.READING_SLOT_EM)) {
        T(" $coolant°C", size, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun BottomBar() = kg.forester.sg.ui.BottomStatusBar()
