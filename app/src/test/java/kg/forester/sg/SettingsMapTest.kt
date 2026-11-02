package kg.forester.sg

import kg.forester.sg.ui.SETTINGS_TABS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What a driver could reach from Настройки before the regrouping must stay reachable: by the code's own call graph. */
class SettingsMapTest {
    private val ui = File("src/main/java/kg/forester/sg/ui")

    /** Everything in the top-level composables reached from SettingsScreen. */
    private val reachable: String by lazy {
        val funs = HashMap<String, String>()
        val head = Regex("(?m)^(?:private |internal |public )?fun (?:[\\w.<>]+\\.)?(\\w+)\\(")
        ui.listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            val ms = head.findAll(t).toList()
            ms.forEachIndexed { i, m ->
                val end = ms.getOrNull(i + 1)?.range?.first ?: t.length
                funs[m.groupValues[1]] = (funs[m.groupValues[1]] ?: "") + t.substring(m.range.first, end)
            }
        }
        val seen = HashSet<String>()
        val todo = ArrayDeque(listOf("SettingsScreen"))
        while (todo.isNotEmpty()) {
            val n = todo.removeFirst()
            val body = funs[n]
            if (body == null || !seen.add(n)) continue
            Regex("\\b([A-Z]\\w+)\\b\\s*[({]").findAll(body).forEach { if (it.groupValues[1] in funs) todo.add(it.groupValues[1]) }
        }
        seen.joinToString("\n") { funs.getValue(it) }
    }

    @Test fun everySettingReachableBeforeStaysReachable() {
        val written = Regex("\\b(?:s|settings|Repo\\.settings)\\.(\\w+)\\s*=[^=]").findAll(reachable).map { it.groupValues[1] }.toSet()
        val missing = BEFORE_KEYS - written
        assertEquals("settings no longer reachable", emptySet<String>(), missing)
    }

    @Test fun everyFeatureSwitchStays() {
        val f = Regex("\\bF\\(\"(\\w+)\"").findAll(reachable).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), BEFORE_FEATURES - f)
    }

    @Test fun everySwitchRowStays() {
        val r = Regex("SwitchRow\\(\\s*\"([^\"]+)\"").findAll(reachable).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), BEFORE_SWITCHES - r)
    }

    @Test fun whatEachOldTabLedToIsStillThere() {
        for (f in listOf("ShortcutSettings", "VoiceSettings", "FeatureSettings", "ExtraSensorSettings", "UsbScreenSettings", "ContactSettings", "CarAdapterSettings", "BackupSettings", "BackgroundSettings", "UpdateSettings", "BrandSettings", "MapAppChooser", "ScreenInchesChooser", "LanguageRow", "UiThemeChooser", "MenuEditorDialog", "MiniPlayerSetting", "ExchangeLogSettings", "FuelSettings", "PlateSettings", "CarouselSettings")) {
            assertTrue(f, Regex("\\b$f\\b").containsMatchIn(reachable))
        }
    }

    @Test fun tabsAreFewEnoughAndEachHasItsPanel() {
        assertTrue("about 6-8", SETTINGS_TABS.size in 6..8)
        assertEquals(SETTINGS_TABS.size, SETTINGS_TABS.map { it.id }.toSet().size)
        val src = File(ui, "LogSettingsScreens.kt").readText()
        val screen = src.substring(src.indexOf("fun SettingsScreen"), src.indexOf("class SettingsTab"))
        for (t in SETTINGS_TABS.dropLast(1)) assertTrue(t.id, screen.contains("\"${t.id}\" ->"))
        // Back goes to the first tab, then out
        assertTrue(screen.contains("BackHandler(enabled = tab != 0) { tab = 0 }"))
    }

    @Test fun theTabsAreOneScrollingLine() {
        val src = File(ui, "LogSettingsScreens.kt").readText()
        val screen = src.substring(src.indexOf("fun SettingsScreen"), src.indexOf("class SettingsTab"))
        assertTrue(screen.contains("horizontalScroll(tabsScroll)"))
        assertFalse("two lines of tabs took a 7\" panel's height", screen.contains("FlowRow"))
    }

    @Test fun theSoundIsAnOrdinarySwitchInTheSoundSection() {
        val src = File(ui, "LogSettingsScreens.kt").readText()
        val sound = src.substring(src.indexOf("internal fun SoundSettings"), src.indexOf("/** Which columns the CSV log gets"))
        assertTrue(sound.contains("SwitchRow(\"Звук кнопок\""))
        assertTrue(sound.contains("s.buttonSound = it"))
        assertTrue(sound.contains("\"Голосовые предупреждения\""))
    }

    @Test fun screenSizeIsBesideTheScaleOnTheFirstTab() {
        val setup = File(ui, "CarSetup.kt").readText()
        val size = setup.substring(setup.indexOf("internal fun ScreenSizeSettings"), setup.indexOf("internal fun ExchangeLogSettings"))
        assertTrue(size.indexOf("ScreenInchesChooser()") in 0 until size.indexOf("Масштаб интерфейса"))
        val chooser = setup.substring(setup.indexOf("internal fun ScreenInchesChooser"))
        assertTrue(chooser.contains("T(\"Размер экрана\""))
        assertEquals("look", SETTINGS_TABS.first().id)
        val src = File(ui, "LogSettingsScreens.kt").readText()
        assertTrue(src.substring(src.indexOf("private fun AppearanceSettings"), src.indexOf("internal fun FuelSettings")).contains("ScreenSizeSettings()"))
        // applied at once: read from a state, no restart anywhere near
        assertTrue(File("src/main/java/kg/forester/sg/MainActivity.kt").readText().contains("val inches by Repo.screenInches.collectAsState()"))
        assertFalse(size.contains("recreate"))
    }

    @Test fun noTextSendsTheDriverToAnOldTab() {
        val old = listOf("Авто и адаптер", "Настройки, Доп датчики", "«Доп датчики»", "вкладка «Цветной экран»")
        val root = File("src/main/java/kg/forester/sg")
        val strings = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            strings.findAll(f.readText()).map { it.value }.filter { lit -> old.any { it in lit } || "Настройки, Основные" in lit || "Настройки → «Основные»" in lit }.map { f.name + ": " + it }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    companion object {
        /** Taken from the sources as they were before the regrouping. */
        val BEFORE_KEYS = setOf(
            "accentTheme", "adapter", "autoBackup", "autostartApp", "autostartService", "awd", "backgroundWork", "bottomBar", "btAddress", "btName", "bus", "buttonSound", "canKbit", "canListen", "car3d", "car3dCustom", "carKind", "carPaint", "carTitle", "carTitleCustom", "carousel", "currency", "demo", "fastPoll", "fuelAtRefillL", "fuelK", "fuelLinePoints", "gearbox", "injectorCc", "injectorDeadMs", "language", "logEndpoint", "logUploadEnabled", "loggerProfile", "logoMode", "mapApp", "navPosition", "pricePerL", "screenInches", "shiftLight", "shiftRpm", "splashMode", "tankL", "tcuAtfOnly", "tcuEvery", "tierChoice", "topBar", "tpms", "traceEnabled", "ttsVoice", "uiScale", "uiTheme", "usedSinceRefillL", "voiceAlerts", "voiceLoud", "wifiHost"
        )
        val BEFORE_FEATURES = setOf("altAlert", "atf", "awd", "battAlert", "boost", "cooling", "health", "idle", "knock", "mine", "minmax", "mixture", "motion", "perf", "sensors", "service", "turbo", "update", "warmup")
        val BEFORE_SWITCHES = setOf("Автокопия раз в сутки", "Быстрый опрос (K-Line)", "Голосовые предупреждения", "Давление в шинах (TPMS)", "Запускать при включении", "Звук кнопок", "Лампа переключения передач", "Открывать приложение при включении", "Отправлять журнал разработчику", "Работать в фоне", "Только температура ATF")
    }
}
