package kg.forester.sg

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** «Не гасить экран»: on unless turned off, the window follows the switch at once, and the switch is in «Экран». */
class KeepScreenOnTest {
    private fun src(path: String) = File("src/main/java/kg/forester/sg/$path").readText()

    @Test fun onByDefaultAndKeptInRepo() {
        val repo = src("data/Repo.kt")
        assertTrue(repo.contains("p.getBoolean(\"keepScreenOn\", true)"))
        assertTrue(repo.contains("Repo.keepScreenOn.value = v"))
        assertTrue(repo.contains("keepScreenOn.value = settings.keepScreenOn"))
    }

    @Test fun windowFollowsTheSetting() {
        val act = src("MainActivity.kt")
        assertTrue(Regex("""Repo\.keepScreenOn\.collect \{ on ->\s+if \(on\) window\.addFlags\([\w.]*FLAG_KEEP_SCREEN_ON\)\s+else window\.clearFlags\([\w.]*FLAG_KEEP_SCREEN_ON\)""")
            .containsMatchIn(act))
    }

    @Test fun switchIsInTheScreenPanel() {
        val ui = src("ui/LogSettingsScreens.kt")
        val panel = ui.substring(ui.indexOf("PanelTitle(Lucide.Monitor, \"Экран\")"), ui.indexOf("SettingsSection(\"Единицы\")"))
        assertTrue(panel.contains("SwitchRow(\"Не гасить экран\""))
        assertTrue(panel.contains("Repo.settings.keepScreenOn = it"))
    }
}
