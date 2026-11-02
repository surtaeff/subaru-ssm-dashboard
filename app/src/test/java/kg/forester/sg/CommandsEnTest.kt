package kg.forester.sg

import kg.forester.sg.data.DistanceUnits
import kg.forester.sg.data.I18n
import kg.forester.sg.data.tr
import kg.forester.sg.service.Commands
import kg.forester.sg.service.CommandsEn
import kg.forester.sg.service.MediaFocus
import kg.forester.sg.service.SpeechText
import kg.forester.sg.service.VoiceIntent
import kg.forester.sg.service.VoiceIntent.*
import kg.forester.sg.service.VoiceModel
import kg.forester.sg.service.VoiceTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandsEnTest {
    @After
    fun metric() {
        DistanceUnits.miles = false
        I18n.english = false
        I18n.install(emptyList())
    }

    private fun check(text: String, intent: VoiceIntent, target: String? = null, query: String? = null, number: Int? = null, freq: Double? = null, focus: MediaFocus? = null, app: String? = null, via: String? = null) {
        val r = CommandsEn.parse(text)
        val where = "“$text” → $r"
        assertEquals(where, intent, r?.intent)
        if (target != null) assertEquals(where, target, r?.target)
        if (query != null) assertEquals(where, query, r?.query)
        if (number != null) assertEquals(where, number, r?.number)
        if (freq != null) assertEquals(where, freq, r?.freq)
        if (focus != null) assertEquals(where, focus, r?.focus)
        if (app != null) assertEquals(where, app, r?.app)
        if (via != null) assertEquals(where, via, r?.via)
    }

    @Test fun thePhrasesTheOwnerNamed() {
        check("show boost", SCREEN, "DRIVE")
        check("what's the coolant temperature", READ_VALUE, "COOLANT")
        check("next track", NEXT)
        check("open fuel", SCREEN, "FUEL")
    }

    @Test fun navigationAsPeopleSayIt() {
        check("can you take me to the train station", NAV_ROUTE, query = "train station")
        check("i want to go to five hundred elm street", NAV_ROUTE, query = "500 elm street")
        check("directions to the stadium", NAV_ROUTE, query = "stadium")
        check("head home", NAV_HOME)
        check("take me to the office", NAV_WORK)
        check("navigate to the mall through downtown", NAV_ROUTE, query = "mall", via = "downtown")
        check("drive to the airport on waze", NAV_ROUTE, query = "airport", app = "com.waze")
        check("how do we get to the beach", NAV_ROUTE, query = "beach")
        check("to the airport let's go", NAV_ROUTE, query = "airport")
        check("is there a hospital nearby", NAV_NEARBY, target = "Hospital")
        check("i need a coffee", NAV_NEARBY, target = "Coffee shop")
        check("where's the nearest atm", NAV_NEARBY, target = "ATM")
        check("find a mcdonald's", NAV_NEARBY, target = "Fast food")
        check("are we there yet", NAV_ETA)
        check("what's my eta", NAV_ETA)
        check("stop navigation", NAV_CANCEL)
        check("what street is this", NAV_WHERE)
        check("save this place as work", NAV_SAVE_PLACE, "work")
        check("turn on the navigation voice", NAV_SOUND_ON)
        check("open google maps", NAV_OPEN, app = "com.google.android.apps.maps")
        check("maps", NAV_OPEN)
    }

    @Test fun volumeAndRadio() {
        check("turn it down", VOLUME_DOWN)
        check("much louder", VOLUME_UP, number = 4)
        check("turn it up by three", VOLUME_UP, number = 3)
        check("turn it down to five", VOLUME_SET, number = 5)
        check("set it to twenty", VOLUME_SET, number = 20)
        check("volume seventy five percent", VOLUME_SET, number = 75)
        check("half volume", VOLUME_SET, number = 50)
        check("sound off", MUTE)
        check("unmute", UNMUTE)
        check("what's the volume", READ_VALUE, "VOLUME")
        check("radio", RADIO)
        check("tune to one oh two point five", RADIO_FREQ, freq = 102.5)
        check("a hundred and four point three fm", RADIO_FREQ, freq = 104.3)
        check("radio 101.7", RADIO_FREQ, freq = 101.7)
        check("preset five", RADIO_PRESET, number = 5)
        check("previous station", RADIO_PREV)
        check("seek back on the radio", RADIO_SEEK, number = -1)
        check("scan for stations", RADIO_SEEK, number = 1)
        check("what station is this", WHAT_PLAYING, "radio")
        check("turn off the radio", PAUSE, "radio")
        check("switch band to fm", RADIO_BAND, "FM")
    }

    @Test fun music() {
        check("skip this one", NEXT)
        check("play something else", NEXT)
        check("last song", PREV)
        check("hold on", PAUSE)
        check("keep playing", PLAY)
        check("play it again", RESTART)
        check("from the beginning", RESTART)
        check("mix it up", SHUFFLE_ON)
        check("stop shuffling", SHUFFLE_OFF)
        check("repeat all", REPEAT_ON, "all")
        check("stop repeating", REPEAT_OFF)
        check("rewind one minute", REWIND, number = 60)
        check("skip ahead twenty seconds", FORWARD, number = 20)
        check("thumbs down", DISLIKE)
        check("what song is this", WHAT_PLAYING)
        check("play something by daft punk", PLAY_QUERY, query = "daft punk", focus = MediaFocus.ARTIST)
        check("play the album thriller", PLAY_QUERY, query = "thriller", focus = MediaFocus.ALBUM)
        check("put on some jazz", PLAY_QUERY, query = "jazz", focus = MediaFocus.GENRE)
        check("play music from the eighties", PLAY_QUERY, focus = MediaFocus.GENRE)
        check("show video cute cats", PLAY_QUERY, query = "cute cats", app = "com.google.android.youtube")
        check("search youtube for car reviews", PLAY_QUERY, query = "car reviews", app = "com.google.android.youtube")
        check("play stop this train", PLAY_QUERY, query = "stop this train")
        check("listen to the podcast serial", PLAY_QUERY, query = "serial", focus = MediaFocus.PODCAST)
        check("play music from my phone", SOURCE_BT)
        check("play from usb", SOURCE_USB)
        check("spotify", MEDIA_APP, app = "com.spotify.music")
    }

    @Test fun valuesAndScreens() {
        check("how hot is the engine", READ_VALUE, "COOLANT")
        check("oil temp", READ_VALUE, "OIL")
        check("how cold is it outside", READ_VALUE, "OUTSIDE")
        check("transmission temperature", READ_VALUE, "ATF")
        check("what's the fuel level", READ_VALUE, "FUEL")
        check("how many miles left", READ_VALUE, "RANGE")
        check("how much further can i go", READ_VALUE, "RANGE")
        check("what's my mpg", READ_VALUE, "CONSUMPTION")
        check("how fast am i going", READ_VALUE, "SPEED")
        check("what are the revs", READ_VALUE, "RPM")
        check("boost pressure", READ_VALUE, "BOOST")
        check("do i have any trouble codes", READ_VALUE, "DTC")
        check("how far have we driven", READ_VALUE, "TRIP")
        check("show me the dashboard", SCREEN, "HOME")
        check("go to media", SCREEN, "MEDIA")
        check("go to fuel use", SCREEN, "FUEL")
        check("open dynamics", SCREEN, "DRIVE")
        check("open the battery screen", SCREEN, "BATTERY")
        check("open whatsapp", APP_OPEN, query = "whatsapp")
    }

    @Test fun whatMustNotFireAndWhatCannotBeDone() {
        check("roll down the window", UNSUPPORTED, "body")
        check("call mom", UNSUPPORTED, "phone")
        check("what's the weather", UNSUPPORTED, "weather")
        check("tell me a joke", UNSUPPORTED, "talk")
        check("go to the call center", NAV_ROUTE, query = "call center")
        check("no thanks", CANCEL)
        check("that's all", CANCEL)
        assertNull(CommandsEn.parse("the quick brown fox"))
        assertNull(CommandsEn.parse("please"))
        assertNull(CommandsEn.parse("it"))
    }

    /** What vosk-model-small-en-us-0.15 really printed for these commands said by synthetic voices (see the report). */
    @Test fun whatTheModelHears() {
        check("once the oil temperature", READ_VALUE, "OIL")
        check("wants the boost", READ_VALUE, "BOOST")
        check("wants to boost", READ_VALUE, "BOOST")
        check("whoops my speed", READ_VALUE, "SPEED")
        check("what's the cool and temperature", READ_VALUE, "COOLANT")
        check("cause", PAUSE)
        check("open feel", SCREEN, "FUEL")
        check("cancel the road", NAV_CANCEL)
        check("cancel the rude", NAV_CANCEL)
        check("find in a dm", NAV_NEARBY, target = "ATM")
        check("find and eight am", NAV_NEARBY, target = "ATM")
        check("soon to ninety eight point eight", RADIO_FREQ, freq = 98.8)
        check("two to ninety eight point eight", RADIO_FREQ, freq = 98.8)
        check("switch them to fm", RADIO_BAND, "FM")
        check("play queen on you tube", PLAY_QUERY, query = "queen", app = "com.google.android.youtube")
        check("play queen on you to", PLAY_QUERY, query = "queen", app = "com.google.android.youtube")
        check("play queen and you tube music", PLAY_QUERY, query = "queen", app = "com.google.android.apps.youtube.music")
        check("play queen and you to music", PLAY_QUERY, query = "queen", app = "com.google.android.apps.youtube.music")
        check("play queen on spot if i", PLAY_QUERY, query = "queen", app = "com.spotify.music")
        check("open spot if i", MEDIA_APP, app = "com.spotify.music")
        check("play from u s b", SOURCE_USB)
        check("play from u s be", SOURCE_USB)
        check("turn on the sand", UNMUTE)
        check("turn off the sand", MUTE)
        check("turn off the sounds", MUTE)
        check("remember this places home", NAV_SAVE_PLACE, "home")
        check("like this", LIKE)
    }

    @Test fun screensAreOnesTheAppHas() {
        val screens = CommandsEn.OWN_GROUPS.flatMap { it.second }.mapNotNull { CommandsEn.parse(it.second.replace("*", "x")) }
            .filter { it.intent == SCREEN }.map { it.target!! }
        assertEquals(VoiceTarget.entries.map { it.name }.toSet(), screens.toSet())
    }

    @Test fun everyDescriptionIsEnglish() {
        val all = CommandsEn.OWN_GROUPS.flatMap { it.second }.mapNotNull { CommandsEn.parse(it.second.replace("*", CommandsEn.STAR_WORD)) }
        for (c in all) assertFalse(CommandsEn.describe(c), CommandsEn.describe(c).any { it in 'Ѐ'..'ӿ' })
    }

    @Test fun hintsAndExamplesHaveNoRussian() {
        val texts = CommandsEn.EXAMPLES.flatMap { listOf(it.first, it.second) } + CommandsEn.OWN_TEMPLATES.flatMap { listOf(it.first, it.second, it.third) } +
            CommandsEn.OWN_GROUPS.flatMap { g -> listOf(g.first) + g.second.flatMap { listOf(it.first, it.second) } } + CommandsEn.OWN_POPULAR.flatMap { listOf(it.first, it.second) }
        for (t in texts) assertFalse(t, t.any { it in 'Ѐ'..'ӿ' })
    }

    @Test fun theRussianGrammarDoesNotKnowEnglishPhrases() {
        // the two don't leak: Russian mode behaves as before
        assertEquals(VoiceIntent.NEXT, Commands.parse("следующий трек")?.intent)
        assertNull(CommandsEn.parse("следующий трек"))
    }

    @Test fun modelsSideBySideWithTheirExactFileNames() {
        assertEquals("vosk-ru", VoiceModel.RU.dir)
        assertEquals("vosk-en", VoiceModel.EN.dir)
        assertEquals("vosk.zip", VoiceModel.RU.cacheName)
        assertEquals(
            listOf("https://github.com/surtaeff/subaru-ssm-dashboard/releases/download/voice-model/vosk-model-small-ru-0.22.zip", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
            VoiceModel.RU.urls,
        )
        assertEquals(
            listOf("https://github.com/surtaeff/subaru-ssm-dashboard/releases/download/voice-model/vosk-model-small-en-us-0.15.zip", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            VoiceModel.EN.urls,
        )
    }

    @Test fun theSuggestedName() {
        assertEquals("assistant", CommandsEn.WAKE_WORD)
        // as vosk-model-small-en-us-0.15 printed it from four synthetic voices
        for (p in listOf("assistant next track", "assistance next track", "assistants next track")) {
            val w = p.split(' ')
            assertEquals(p, listOf("next", "track"), w.drop(Commands.wakeEnd(w, CommandsEn.WAKE_WORD)))
        }
        assertEquals(-1, Commands.wakeEnd("the car park is full".split(' '), CommandsEn.WAKE_WORD))
        // another name, as ever
        assertEquals(1, Commands.wakeEnd("jarvis louder".split(' '), "jarvis"))
    }

    // ---- what is said back

    private val facts = CommandsEn.Facts(
        coolantC = 91, intakeC = 1, atfC = 80.4f, oilC = 100, oilEstimated = true, oilBar = 3.25, batteryV = 13.84f, speedKmh = 100.0, rpm = 2500, boostBar = 0.856f,
        fuelLeftL = 42.3, rangeKm = 160.0, per100 = 9.5, tripKm = 1.609344, volume = 12, time = "14:05",
    )

    private fun say(t: String, f: CommandsEn.Facts = facts) = SpeechText.prepareEnglish(CommandsEn.valueAnswer(t, f))

    @Test fun answersInKilometers() {
        assertEquals("Engine 91 degrees", say("COOLANT"))
        assertEquals("Intake air 1 degree", say("INTAKE"))
        assertEquals("Transmission 80 degrees", say("ATF"))
        assertEquals("Oil about 100 degrees", say("OIL"))
        assertEquals("Oil pressure 3.3 bar", SpeechText.prepareEnglish(CommandsEn.valueAnswer("OILP", CommandsEn.Facts(oilBar = 3.3))))
        assertEquals("Voltage 13.8 volts", say("BATTERY"))
        assertEquals("Speed 100 kilometers per hour", say("SPEED"))
        assertEquals("Engine at 2500 r p m", say("RPM"))
        assertEquals("Boost 0.86 bar", say("BOOST"))
        assertEquals("42 liters in the tank", say("FUEL"))
        assertEquals("Enough for 160 kilometers", say("RANGE"))
        assertEquals("Consumption 9.5 liters per 100 kilometers", say("CONSUMPTION"))
        assertEquals("Driven 2 kilometers", say("TRIP"))
        assertEquals("14:05", say("TIME"))
        assertEquals("Volume 12", say("VOLUME"))
    }

    @Test fun answersInMiles() {
        DistanceUnits.miles = true
        assertEquals("Speed 62 miles per hour", say("SPEED"))
        assertEquals("Enough for 99 miles", say("RANGE"))
        assertEquals("Consumption 24.8 miles per gallon", say("CONSUMPTION"))
        assertEquals("Driven 1 mile", say("TRIP"))
    }

    @Test fun answersWithNothingToSay() {
        val none = CommandsEn.Facts()
        assertEquals("No oil data", say("OIL", none))
        assertEquals("No oil pressure sensor", say("OILP", none))
        assertEquals("Engine no data", say("COOLANT", none))
        assertEquals("No fuel data: mark a refill", say("FUEL", none))
        assertEquals("Range no data", say("RANGE", none))
    }

    @Test fun theRepliesOfTheVoiceReadAsEnglish() {
        I18n.install(File("src/main/assets/i18n/en.tsv").readLines())
        I18n.english = true
        val heard = mapOf(
            "Например: поехали домой, громче, следующая, найди заправку, какая температура" to "For example: go home, louder, next track, find a gas station, what's the coolant temperature",
            "Сначала скажите «запомни это место как дом», когда будете там" to "First say remember this place as home when you are there",
            "Скажите навигатору: «Слушай, Алиса, отмени маршрут»" to "Ask the navigator's own assistant: cancel the route",
            "Скажите навигатору: «Слушай, Алиса, сколько ехать»" to "Ask the navigator's own assistant: how long to go",
            "Ищу: main street 10" to "Searching: main street 10",
            "Громкость 20" to "Volume 20",
            "Не понял: foo bar" to "Didn't understand: foo bar",
            "скачиваю модель распознавания (40 МБ)…" to "downloading the recognition model (40 MB).",
            "скачиваю модель распознавания (45 МБ)…" to "downloading the recognition model (45 MB).",
        )
        for ((ru, en) in heard) assertEquals(en, SpeechText.prepareEnglish(tr(ru)))
    }

    @Test fun theDictionaryQuotesNoRussianCommand() {
        val lines = File("src/main/assets/i18n/en.tsv").readLines().filter { !it.startsWith("//") && it.contains('\t') }
        val kept = lines.filter { l -> l.substringAfter('\t').any { it in 'Ѐ'..'ӿ' } }.map { it.substringBefore('\t') }
        assertEquals(listOf("Европа Плюс", "Русское Радио", "Ретро FM", "Авторадио", "Радио Рекорд", "Язык / Language", "Русский", "форестер"), kept)
        assertTrue(lines.none { it.contains("Russian only") })
    }
}
