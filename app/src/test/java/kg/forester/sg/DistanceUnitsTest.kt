package kg.forester.sg

import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.data.DistanceUnits.Kind
import kg.forester.sg.service.Interval
import kg.forester.sg.service.PerfCalc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DistanceUnitsTest {
    private val locale = java.util.Locale.getDefault()

    @org.junit.Before fun fixedFigures() { java.util.Locale.setDefault(java.util.Locale.US) }

    @After fun back() {
        DistanceUnits.miles = false
        java.util.Locale.setDefault(locale)
    }

    private fun miles() { DistanceUnits.miles = true }

    @Test fun kilometresAreTheDefaultAndChangeNothing() {
        assertFalse(DistanceUnits.miles)
        assertEquals(100.0, DistanceUnits.speed(100.0), 0.0)
        assertEquals(800.0, DistanceUnits.distance(800.0), 0.0)
        assertEquals(9.4, DistanceUnits.consumption(9.4), 0.0)
        assertEquals("км/ч", DistanceUnits.speedUnit)
        assertEquals("км", DistanceUnits.distanceUnit)
        assertEquals("л/100", DistanceUnits.consumptionUnit)
        assertEquals("л/100 км", DistanceUnits.consumptionUnitLong)
        assertEquals(200f, DistanceUnits.top(Kind.SPEED, 200f), 0f)
        assertEquals("Сейчас 100 км/ч, 12 км", DistanceUnits.prose("Сейчас 100 км/ч, 12 км"))
    }

    @Test fun conversionsInMiles() {
        miles()
        assertEquals(62.137, DistanceUnits.speed(100.0), 0.001)
        assertEquals(1.0, DistanceUnits.distance(1.609344), 1e-9)
        // the usual pairs: 10 l/100 km is 23.5 mpg (US), 235.2 / x both ways
        assertEquals(23.52, DistanceUnits.consumption(10.0), 0.01)
        assertEquals(10.0, DistanceUnits.consumptionToL100(DistanceUnits.consumption(10.0)), 1e-9)
    }

    @Test fun noFuelBurnedHasACeilingNotInfinity() {
        miles()
        assertEquals(DistanceUnits.MPG_TOP, DistanceUnits.consumption(0.0), 0.0)
        assertEquals(DistanceUnits.MPG_TOP, DistanceUnits.consumption(0.5), 0.0)
    }

    @Test fun formatterTexts() {
        miles()
        assertEquals("62 миль/ч", DistanceUnits.speedText(100.0))
        assertEquals("31 миль", DistanceUnits.distanceText(50.0))
        assertEquals("3.1 миль", DistanceUnits.distanceText(5.0, 1))
        assertEquals("23.5 миль/гал", DistanceUnits.consumptionText(10.0))
        DistanceUnits.miles = false
        assertEquals("100 км/ч", DistanceUnits.speedText(100.0))
        assertEquals("50 км", DistanceUnits.distanceText(50.0))
        assertEquals("10.0 л/100 км", DistanceUnits.consumptionText(10.0))
    }

    @Test fun scalesAreRound() {
        miles()
        assertEquals(120f, DistanceUnits.top(Kind.SPEED, 200f), 0f)
        assertEquals(20f, DistanceUnits.speedStep(), 0f)
        assertEquals(500f, DistanceUnits.top(Kind.DISTANCE, 800f), 0f)
        assertEquals(60f, DistanceUnits.top(Kind.CONSUMPTION, 30f), 0f)
    }

    @Test fun aTypedValueIsConvertedWhenSaved() {
        miles()
        assertEquals("4660", DistanceUnits.distanceField(7500.0))
        assertEquals(8046.72, DistanceUnits.distanceFromField("5000", 7500.0)!!, 0.001)
        assertEquals(96.56, DistanceUnits.speedToKmh(60.0), 0.01)
        assertNull(DistanceUnits.distanceFromField("abc", 7500.0))
    }

    @Test fun aFieldLeftAloneKeepsTheExactStoredValue() {
        miles()
        // 7500 km shows as 4660 mi; saved as it is it must not become 7499.5 km and drift with each save
        assertEquals(7500.0, DistanceUnits.distanceFromField(DistanceUnits.distanceField(7500.0), 7500.0)!!, 0.0)
        DistanceUnits.miles = false
        assertEquals("7500", DistanceUnits.distanceField(7500.0))
        assertEquals(7600.0, DistanceUnits.distanceFromField("7600", 7500.0)!!, 0.0)
    }

    @Test fun unitsOfParametersAreRecognised() {
        assertEquals(Kind.SPEED, DistanceUnits.kindOf("км/ч"))
        assertEquals(Kind.DISTANCE, DistanceUnits.kindOf("км"))
        assertEquals(Kind.CONSUMPTION, DistanceUnits.kindOf("л/100 км"))
        assertEquals(Kind.CONSUMPTION, DistanceUnits.kindOf("л/100"))
        assertNull(DistanceUnits.kindOf("кПа"))
        assertNull(DistanceUnits.kindOf("м"))
    }

    @Test fun proseConvertsNumbersWithTheirUnit() {
        miles()
        assertEquals("до 28 миль/ч", DistanceUnits.prose("до 45 км/ч"))
        assertEquals("Первые 31–62 миль", DistanceUnits.prose("Первые 50–100 км"))
        assertEquals("at 28 mph", DistanceUnits.prose("at 45 km/h"))
        assertEquals("every 62 mi", DistanceUnits.prose("every 100 km"))
        assertEquals("~62 тыс. миль", DistanceUnits.prose("~100 тыс. км"))
        assertEquals("every 62,137 mi", DistanceUnits.prose("every 100,000 km"))
        // consumption turns over: the lower litres are the higher mpg, the range stays low to high
        assertEquals("17–21 миль/гал", DistanceUnits.prose("11–14 л/100 км"))
        assertEquals("17-21 mpg", DistanceUnits.prose("11-14 l/100 km"))
        // words that merely contain the letters stay
        assertEquals("15 кмоль и 5 кмх", DistanceUnits.prose("15 кмоль и 5 кмх"))
        assertEquals("в 5 мин", DistanceUnits.prose("в 5 мин"))
    }

    @Test fun proseNamesTheUnitWithoutANumberToo() {
        miles()
        assertEquals("Сколько миль можно проехать", DistanceUnits.prose("Сколько километров можно проехать"))
        assertEquals("How many miles", DistanceUnits.prose("How many kilometres"))
    }

    @Test fun theVoiceSaysMiles() {
        val said = kg.forester.sg.service.SpeechText.prepare("Осталось 62 миль/ч, 21 миль")
        assertTrue(said, "мили в час" in said)
        assertTrue(said, "двадцать одна миля" in said)
    }

    @Test fun aSpeedRuleIsSaidInTheShownUnitAndKeptInKmh() {
        miles()
        assertEquals("62 миль/ч", kg.forester.sg.service.RuleRunner.num("speed", 100f))
        DistanceUnits.miles = false
        assertEquals("100 км/ч", kg.forester.sg.service.RuleRunner.num("speed", 100f))
    }

        @Test fun whatANavigatorSaysIsConvertedOnlyWhenItIsJustADistance() {
        miles()
        assertEquals("0.2 миль", DistanceUnits.navText("350 м"))
        assertEquals("1.2 миль", DistanceUnits.navText("2,0 км"))
        assertEquals("100 футов", DistanceUnits.navText("30 м"))
        assertEquals("Поверните через 300 м налево", DistanceUnits.navText("Поверните через 300 м налево"))
        DistanceUnits.miles = false
        assertEquals("350 м", DistanceUnits.navText("350 м"))
    }

    @Test fun routeDistances() {
        miles()
        assertEquals("100 футов", DistanceUnits.navDistance(30.0))
        assertEquals("0.2 миль", DistanceUnits.navDistance(350.0))
        assertEquals("12 миль", DistanceUnits.navDistance(19312.0))
    }

    @Test fun mileIntervalsKeepTheirOwnKeyAndAreTimedInKmh() {
        val iv = Interval.parse("0-60mi")!!
        assertEquals("0-60mi", iv.key)
        assertEquals(96.56, iv.toKmh, 0.01)
        assertEquals(0.0, iv.fromKmh, 0.0)
        assertTrue(iv.mi)
        val km = Interval.parse("0-100")!!
        assertEquals(100.0, km.toKmh, 0.0)
        assertFalse(km.mi)
        assertEquals(listOf("0-100", "0-60mi"), Interval.list("0-60mi,0-100").map { it.key }.sortedBy { it.length })
    }

    @Test fun theHeadlineRunFollowsTheUnit() {
        assertEquals("0-100", PerfCalc.headline)
        miles()
        assertEquals("0-60mi", PerfCalc.headline)
    }

    @Test fun brakingTextNamesTheRealSpeed() {
        assertTrue(PerfCalc.brakeText(2.94, 41.0).startsWith("Торможение со ста:"))
        miles()
        assertTrue(PerfCalc.brakeText(2.94, 41.0).startsWith("Торможение с 62 миль/ч:"))
    }

    /** Nothing in ui/ may print a speed literal beside a value: it goes through [DistanceUnits]. */
    @Test fun noStrayMetricUnitsNextToValuesInUi() {
        val ui = File("src/main/java/kg/forester/sg/ui")
        val stray = Regex("""(\$\{?[\w.?!]+\}?|%[\d.]*[df])\s*(км/ч|км\b|л/100)""")
        val literal = Regex(""""(км/ч|км|л/100|л/100 км)"""")
        val allowed = setOf("InfoTexts.kt")
        val found = ui.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                // the catalogue's own metric names, with the kind that converts them
                val declared = f.name == "Metrics.kt" && "kind = DistanceUnits" in line
                if (!declared && (stray.containsMatchIn(line) || literal.containsMatchIn(line))) "${f.name}:${i + 1}: ${line.trim().take(100)}" else null
            }
        }.toList()
        assertEquals(found.joinToString("\n"), 0, found.size)
    }
}
