package kg.forester.sg

import kg.forester.sg.data.I18n
import kg.forester.sg.data.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class I18nTest {
    private val lines = listOf(
        "// a comment line",
        "Масло\tOil",
        "Настройки\tSettings",
        "Температура масла\tOil temperature",
        "Включено\tOn",
        "Цилиндр 2\tCylinder two",
        "# км/ч\t# km/h",
        "# дня\t# days",
        "1 день\t1 day",
        "от # до #\tfrom # to #",
        "Датчик {} не отвечает\tSensor {} does not answer",
        "{} км\t{} km",
        "Двигатель: {}, скорость #\tEngine: {}, speed #",
        "{}: обрыв\t{}: open circuit",
        "{} из {}\t{2} out of {1}",
        "Две строки\\nвторая\tTwo lines\\nsecond",
        "Горячий\tHot",
        "горячий {}\thot {}",
        " · объём не определён\t · size unknown",
        " · сейчас # л\t · now # l",
        "дроссель\tthrottle",
        "мс\tms",
        "С GPS от 10 Гц точность ±0,1 с.\tWith GPS from 10 Hz accuracy is ±0.1 s.",
        "Скорость GPS. {}\tGPS speed. {}",
        "A/F Learning ## Ranges нет\tno A/F Learning ## Ranges",
    )

    @Before
    fun on() {
        I18n.install(lines)
        I18n.english = true
        I18n.missing.clear()
    }

    @After
    fun off() {
        I18n.english = false
        I18n.install(emptyList())
        I18n.onMissing = null
    }

    @Test
    fun russianPassesTheSameObjectThrough() {
        I18n.english = false
        val s = "Масло"
        assertSame(s, tr(s))
        val unknown = "Какой-то текст 12"
        assertSame(unknown, tr(unknown))
    }

    @Test
    fun noCyrillicIsUntouched() {
        val s = "RPM 3000"
        assertSame(s, tr(s))
        assertSame("", tr(""))
        assertSame("# {}", tr("# {}"))
    }

    @Test
    fun exact() {
        assertEquals("Oil", tr("Масло"))
        assertEquals("Cylinder two", tr("Цилиндр 2"))
        assertEquals("Two lines\nsecond", tr("Две строки\nвторая"))
    }

    @Test
    fun patternWithNumbers() {
        assertEquals("120 km/h", tr("120 км/ч"))
        assertEquals("7.5 km/h", tr("7.5 км/ч"))
        assertEquals("7,5 km/h", tr("7,5 км/ч"))
        assertEquals("150 000 km/h", tr("150 000 км/ч"))
        assertEquals("from 3 to 12", tr("от 3 до 12"))
        assertEquals("-3 km/h", tr("-3 км/ч"))
    }

    @Test
    fun anExactLineBeatsThePattern() {
        assertEquals("1 day", tr("1 день"))
        assertEquals("5 days", tr("5 дня"))
    }

    @Test
    fun patternWithText() {
        assertEquals("Sensor BOSCH does not answer", tr("Датчик BOSCH не отвечает"))
        assertEquals("P0420: open circuit", tr("P0420: обрыв"))
        assertEquals("Engine: EJ20, speed 40", tr("Двигатель: EJ20, скорость 40"))
    }

    @Test
    fun whatAHoleCapturesIsTranslatedToo() {
        assertEquals("Sensor Oil does not answer", tr("Датчик Масло не отвечает"))
        assertEquals("Oil temperature km", tr("Температура масла км"))
    }

    @Test
    fun aHoleCanMoveInTheEnglishOrder() {
        assertEquals("Oil out of 12 km/h", tr("12 км/ч из Масло"))
        assertEquals("12 km/h out of Oil", tr("Масло из 12 км/ч"))
    }

    @Test
    fun aDigitInAWordIsNoNumber() {
        // F1 and 4WD are names: «# км/ч» can't catch them, and a value next to them still can
        assertEquals("F1 км/ч", tr("F1 км/ч"))
    }

    @Test
    fun punctuationAroundALineIsKept() {
        assertEquals("Oil:", tr("Масло:"))
        assertEquals("Oil: ", tr("Масло: "))
        assertEquals("  Oil…", tr("  Масло…"))
        assertEquals("(Oil)", tr("(Масло)"))
    }

    @Test
    fun partsJoinedByTheScreenAreTranslatedOneByOne() {
        assertEquals("Oil · Settings", tr("Масло · Настройки"))
        assertEquals("Oil\nSettings", tr("Масло\nНастройки"))
        assertEquals("Oil: 120 km/h", tr("Масло: 120 км/ч"))
    }

    @Test
    fun capitalsAndALowerFirstLetterFindTheirLine() {
        assertEquals("OIL TEMPERATURE", tr("ТЕМПЕРАТУРА МАСЛА"))
        assertEquals("oil temperature", tr("температура масла"))
    }

    @Test
    fun theSameFormWithOtherNumbersIsFilledRight() {
        assertEquals("from 1 to 2", tr("от 1 до 2"))
        assertEquals("from 30 to 40", tr("от 30 до 40"))
        assertEquals("Engine: EJ25, speed 9", tr("Двигатель: EJ25, скорость 9"))
        assertEquals("Engine: FA20, speed 100", tr("Двигатель: FA20, скорость 100"))
    }

    @Test
    fun notFoundIsTheOriginalAndToldOnce() {
        val told = mutableListOf<String>()
        I18n.onMissing = { told += it }
        assertEquals("Неведомая строка", tr("Неведомая строка"))
        assertEquals("Неведомая строка", tr("Неведомая строка"))
        assertEquals(listOf("Неведомая строка"), told)
    }

    @Test
    fun aBoundedCacheOutlivesManyTexts() {
        repeat(5000) { assertEquals("$it km/h", tr("$it км/ч")) }
        repeat(5000) { tr("Неведомая строка номер а$it") }
        assertTrue(I18n.cacheSize() <= I18n.CACHE_MAX)
        assertEquals("Oil", tr("Масло"))
    }

    @Test
    fun beforeTheDictionaryIsReadTheOriginalShows() {
        I18n.install(emptyList(), loaded = false)
        assertEquals("Масло", tr("Масло"))
        assertFalse(I18n.ready)
        I18n.install(lines)
        assertTrue(I18n.ready)
        assertEquals("Oil", tr("Масло"))
    }

    @Test
    fun aFragmentLineServesTheBareTextToo() {
        assertEquals("Link · size unknown", tr("Link · объём не определён"))
        assertEquals("Link · now 5 l", tr("Link · сейчас 5 л"))
    }

    @Test
    fun aKnownWordBesideANumberNeedsNoPattern() {
        assertEquals("throttle 26", tr("дроссель 26"))
        assertEquals("2.31 ms", tr("2.31 мс"))
        // word by word only when all of it is known
        assertEquals("дроссель странный 26", tr("дроссель странный 26"))
    }

    @Test
    fun aLineWithDigitsIsFoundInsideAHole() {
        assertEquals("GPS speed. With GPS from 10 Hz accuracy is ±0.1 s.", tr("Скорость GPS. С GPS от 10 Гц точность ±0,1 с."))
        // and its digits do not stick to the sentence with other numbers
        assertEquals(false, tr("Скорость GPS. С GPS от 20 Гц точность ±0,1 с.").contains("10 Hz"))
    }

    @Test
    fun aRealHashInAKeyStillFindsItself() {
        assertEquals("no A/F Learning ## Ranges", tr("A/F Learning ## Ranges нет"))
    }

    @Test
    fun whatTheDataBringsIsKnownWithoutALine() {
        I18n.learn("Неисправность датчика", "Sensor fault")
        assertEquals("Sensor fault", tr("Неисправность датчика"))
    }

    @Test
    fun aHomonymIsPickedWhereItIsWritten() {
        assertEquals("Card", I18n.pick("Карта", "Card"))
        I18n.english = false
        assertEquals("Карта", I18n.pick("Карта", "Card"))
    }

    @Test
    fun aRawTextIsLeftAlone() {
        // a song or a name of the driver's own: «Горячий» on the media screen is not the word in the dictionary
        assertEquals("Горячий", tr(I18n.raw("Горячий")))
        I18n.english = false
        assertSame("Горячий", I18n.raw("Горячий"))
    }
}
