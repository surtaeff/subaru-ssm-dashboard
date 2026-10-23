package kg.forester.sg.data

import android.content.Context
import android.content.SharedPreferences
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.EcuSnapshot
import kg.forester.sg.ssm.TcuSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkState { NO_CABLE, NO_PERMISSION, CONNECTING, CONNECTED, NO_ECU, ERROR }

data class GpsFix(
    val speedKmh: Float,
    val altitudeM: Double,
    val accuracyM: Float,
    val satellites: Int,
    val timeMs: Long,
    /** Course over ground, degrees clockwise from north; null when GPS has none (standing still). */
    val bearing: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Signal of the 4 strongest satellites in the fix, mean C/N0 dB-Hz (Dragy qualifies its runs on it); null unknown. */
    val cn0: Float? = null,
)

data class BatteryState(
    val restingV: Float? = null,
    val restingTimeMs: Long = 0,
    val crankMinV: Float? = null,
    val crankTimeMs: Long = 0,
    val crankMs: Long? = null,
    val chargingV: Float? = null,
    val chargingMinV: Float? = null,
    val chargingMaxV: Float? = null,
    /** Hours the car stood before the resting reading (under ~2 h the battery still has surface charge). */
    val restingParkedH: Float? = null,
    /** After the start: seconds until the voltage came back to 13.2 V (null = not yet / not known, -1 = not within a minute). */
    val crankRecoveryS: Float? = null,
    /** Intake air temperature at the start (≈ outside air on a cold engine) and cranking speed. */
    val crankTempC: Int? = null,
    val crankRpm: Int? = null,
    /** Alternator output at idle and at 1800–3500 rpm. */
    val chargeIdleV: Float? = null,
    val chargeRevV: Float? = null,
)

/** Live trip accumulators, updated by the service on every sample. */
data class TripState(
    val trip: Trip? = null,
    val instLh: Double = 0.0,
    val instPer100: Double? = null,
    val fuelLeftL: Double? = null,
    /** [fuelLeftL] comes from the ECU's fuel sender, not from counting since the refill. */
    val fuelFromSensor: Boolean = false,
    val rangeKm: Double? = null,
)

class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** A phone leaves the car with its owner: there the service should not start on its own. */
    private val phone = ctx.resources.configuration.smallestScreenWidthDp < 600

    init {
        // installs from before the currency setting priced fuel in KGS: keep that for them
        if (!p.contains("currency") && p.contains("price")) p.edit().putString("currency", "KGS").apply()
    }

    var tankL: Double
        get() = p.getFloat("tank", 60f).toDouble() // 0 = unknown
        set(v) = p.edit().putFloat("tank", v.toFloat()).apply()

    var pricePerL: Double
        get() = p.getFloat("price", 82f).toDouble()
        set(v) = p.edit().putFloat("price", v.toFloat()).apply()

    /** Currency shown next to prices (₽, ₸, $, KGS…). */
    var currency: String
        get() = p.getString("currency", null) ?: localCurrency()
        set(v) = p.edit().putString("currency", v.trim().ifEmpty { localCurrency() }).apply()

    /** Currency of the device's country, as people write it on fuel prices. */
    private fun localCurrency(): String {
        val country = java.util.Locale.getDefault().country
        return when (country) {
            "RU" -> "₽"
            "KZ" -> "₸"
            "KG" -> "KGS"
            "BY" -> "BYN"
            "UZ" -> "сум"
            "UA" -> "₴"
            "AM" -> "֏"
            "GE" -> "₾"
            "AZ" -> "₼"
            "TJ" -> "TJS"
            else -> runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).symbol }.getOrDefault("")
        }
    }

    /** Fuel calculation correction, learned from full-to-full refills. */
    var fuelK: Double
        get() {
            val v = p.getFloat("fuelK", 1f).toDouble()
            // the calibration of older versions could run far off: start over instead of sitting at a limit
            if (v !in 0.7..1.4) { p.edit().putFloat("fuelK", 1f).apply(); return 1.0 }
            return v
        }
        set(v) = p.edit().putFloat("fuelK", v.toFloat()).apply()

    /** Real speed / ECU speed, learned from GPS. */
    var speedK: Double
        get() = p.getFloat("speedK", 1f).toDouble()
        set(v) = p.edit().putFloat("speedK", v.toFloat()).apply()

    /** Litres in the tank at the moment of the last refill. */
    var fuelAtRefillL: Double
        get() = p.getFloat("fuelAtRefill", -1f).toDouble()
        set(v) = p.edit().putFloat("fuelAtRefill", v.toFloat()).apply()

    /** Computed fuel used since the last refill. */
    var usedSinceRefillL: Double
        get() = p.getFloat("usedSinceRefill", 0f).toDouble()
        set(v) = p.edit().putFloat("usedSinceRefill", v.toFloat()).apply()

    /** Computed fuel used since the last FULL refill (for calibration). */
    var usedSinceFullL: Double
        get() = p.getFloat("usedSinceFull", 0f).toDouble()
        set(v) = p.edit().putFloat("usedSinceFull", v.toFloat()).apply()

    var hadFullRefill: Boolean
        get() = p.getBoolean("hadFull", false)
        set(v) = p.edit().putBoolean("hadFull", v).apply()

    /** The litres in the tank are known (a full tank since, partial refills on top of it), not guessed from an unknown level. */
    var fuelLevelKnown: Boolean
        get() = p.getBoolean("fuelKnown", false)
        set(v) = p.edit().putBoolean("fuelKnown", v).apply()

    var logEnabled: Boolean
        get() = p.getBoolean("log", true)
        set(v) = p.edit().putBoolean("log", v).apply()

    /** How long detailed CSV logs are kept. */
    var logRetentionHours: Int
        get() = p.getInt("logHours", 24)
        set(v) = p.edit().putInt("logHours", v).apply()

    /** Navigation panel position: left, right, top, bottom. */
    var navPosition: String
        get() = p.getString("navPos", "left")!!
        set(v) { p.edit().putString("navPos", v).apply(); Repo.navPosition.value = v }

    /** Screens a sideways swipe goes through, in order (Screen names); empty: no carousel. */
    var carousel: List<String>
        get() = p.getString("carousel", "")!!.split(',').filter { it.isNotBlank() }
        set(v) { p.edit().putString("carousel", v.joinToString(",")).apply(); Repo.carousel.value = v }

    /** Cells the free layout snaps cards to: "10x6", "8x5", "12x8" or "" (half-percent steps). */
    var freeGrid: String
        get() = p.getString("freeGrid", "10x6")!!
        set(v) { p.edit().putString("freeGrid", v).apply(); Repo.freeGrid.value = v }

    /** Value shown by the «Датчик k» card ("" = the k-th parameter ticked on «Датчики»). */
    fun slot(k: Int): String = p.getString("slot$k", "")!!
    fun setSlot(k: Int, id: String) { p.edit().putString("slot$k", id).apply(); Repo.slotVersion.value++ }

    /** The page the «Спорт-дисплей» card at [card] ("home.defi", "drive.defi") was left on. */
    fun sportPage(card: String): Int = p.getInt("sport.$card", 0)
    fun setSportPage(card: String, page: Int) = p.edit().putInt("sport.$card", page).apply()

    /** The one-time tip on the home screen (how to customise it) was closed. */
    var homeTipSeen: Boolean
        get() = p.getBoolean("homeTipSeen", false)
        set(v) = p.edit().putBoolean("homeTipSeen", v).apply()

    /** What the bottom bar shows: "id:bar,…" (see BottomBarCfg); "" = nothing. */
    var bottomBar: String
        get() = p.getString("bottomBar", null) ?: kg.forester.sg.ui.BottomBarCfg.DEFAULT
        set(v) { p.edit().putString("bottomBar", v).apply(); Repo.bottomBar.value = v }

    /** Values in the top bar next to the car's name, same catalogue as the bottom bar. */
    var topBar: String
        get() = p.getString("topBar", null) ?: kg.forester.sg.ui.BottomBarCfg.TOP_DEFAULT
        set(v) { p.edit().putString("topBar", v).apply(); Repo.topBar.value = v }

    /**
     * Diagnostic adapter: "auto" (find it), "kkl" (USB KKL cable or OpenPort 1.3), "op2" (Tactrix OpenPort 2.0 /
     * clone), "elm_usb", "elm_bt", "elm_wifi" (ELM327 by USB, Bluetooth or Wi-Fi), "canable", "mt_bt" (a Multitronics
     * trip computer on Bluetooth).
     */
    var adapter: String
        get() = p.getString("adapter", "auto")!!
        set(v) = p.edit().putString("adapter", v).apply()

    /** Paired Bluetooth adapter used when [adapter] is "elm_bt" or "mt_bt" (an ELM327 or a Multitronics). */
    var btAddress: String
        get() = p.getString("btAddress", "")!!
        set(v) = p.edit().putString("btAddress", v).apply()

    var btName: String
        get() = p.getString("btName", "")!!
        set(v) = p.edit().putString("btName", v).apply()

    /** The SSM logger profile: "stock", or "carberry42" (CarBerry 4.2 channels on top of the stock ones, ROM 3D04EA4605 only). */
    var loggerProfile: String
        get() = p.getString("loggerProfile", "stock")!!
        set(v) = p.edit().putString("loggerProfile", v).apply()

    /** Parameters from the RomRaider list the user ticked on the «Датчики» screen, comma-separated ids. */
    /** The driver's own ECU addresses (JSON, see CustomParams). */
    var customParams: String
        get() = p.getString("customParams", "[]")!!
        set(v) = p.edit().putString("customParams", v).apply()

    /** Capability bits turned on by hand per ROM, for sensors the ECU has but doesn't list (JSON, see EcuCaps). */
    var capsForced: String
        get() = p.getString("capsForced", "{}")!!
        set(v) = p.edit().putString("capsForced", v).apply()

    /** The driver's own RomRaider definition files in files/defs, in the order they apply (JSON, see EcuDefs). */
    var ecuDefs: String
        get() = p.getString("ecuDefs", "[]")!!
        set(v) = p.edit().putString("ecuDefs", v).apply()

    /** Roles given to parameters of those files: role → id (JSON, see EcuDefs). */
    var ecuDefRoles: String
        get() = p.getString("ecuDefRoles", "{}")!!
        set(v) = p.edit().putString("ecuDefRoles", v).apply()

    /** Values the driver reads off a CAN stream by id and bytes (see CanChannels), JSON. */
    var canChannels: String
        get() = p.getString("canChannels", "[]")!!
        set(v) = p.edit().putString("canChannels", v).apply()

    /** Speed of the bus a CANable listens to, kbit/s: what the sports ECU's dash CAN is set to in its software. */
    var canKbit: Int
        get() = p.getInt("canKbit", 500)
        set(v) = p.edit().putInt("canKbit", v).apply()

    /** The fuel gauge wire's hand-made calibration, "volts,litres;…" (see FuelLine). */
    var fuelLinePoints: String
        get() = p.getString("fuelLinePoints", "")!!
        set(v) = p.edit().putString("fuelLinePoints", v).apply()

    /** The colour screen on its own USB (JSON, see UsbScreen). */
    var usbScreen: String
        get() = p.getString("usbScreen", "{}")!!
        set(v) = p.edit().putString("usbScreen", v).apply()

    /** The SensorBox displays (JSON, see Displays). */
    var displays: String
        get() = p.getString("displays", "[]")!!
        set(v) = p.edit().putString("displays", v).apply()

    /** The SensorBox outputs (relays) and their rules (JSON, see Relays). */
    var relays: String
        get() = p.getString("relays", "[]")!!
        set(v) = p.edit().putString("relays", v).apply()

    /** The USB wideband (Innovate LC-2): on or off, its adapter (JSON, see Wideband). */
    var wideband: String
        get() = p.getString("wideband", "{}")!!
        set(v) = p.edit().putString("wideband", v).apply()

    /** The SensorBox: on or off, the board, what is wired to which input (JSON, see SensorBox). */
    var sensorBox: String
        get() = p.getString("sensorBox", "{}")!!
        set(v) = p.edit().putString("sensorBox", v).apply()

    var watchIds: Set<String>
        get() = p.getString("watch", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        set(v) { p.edit().putString("watch", v.joinToString(",")).apply(); Repo.watchVersion.value++ }

    private fun str(k: String, d: String) = p.getString(k, d)!!
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()

    /**
     * Optional features, all on by default and switched in Настройки → «Функции»: "knock" (IAM/knock warnings),
     * "turbo" (turbo timer), "warmup", "cooling" (overheating, thermostat, coolant spike), "atf", "service"
     * (maintenance reminders), "perf" (acceleration/laps, «Замеры» screen), "motion" (G-meter, tilt),
     * "sensors" and "health" (those screens in the menu).
     */
    fun feature(id: String) = p.getBoolean("f_$id", true)
    fun setFeature(id: String, on: Boolean) { p.edit().putBoolean("f_$id", on).apply(); Repo.featureVersion.value++ }

    /** K-line: the ECU sends the values by itself (SSM continuous mode, RomRaider's «fast poll»), where it can. */
    var fastPoll: Boolean
        get() = p.getBoolean("fastPoll", true)
        set(v) = p.edit().putBoolean("fastPoll", v).apply()

    /** The transmission unit is read every N-th engine poll (3 = as before; more = faster engine data). */
    var tcuEvery: Int
        get() = p.getInt("tcuEvery", 3)
        set(v) = p.edit().putInt("tcuEvery", v).apply()

    /** Read only the ATF temperature (and gear selector) from the transmission unit. */
    var tcuAtfOnly: Boolean
        get() = p.getBoolean("tcuAtfOnly", false)
        set(v) { p.edit().putBoolean("tcuAtfOnly", v).apply(); Repo.watchVersion.value++ }

    /** CSV log columns switched off (keys of LogColumns); everything else is written. */
    var logOff: Set<String>
        get() = p.getString("logOff", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        set(v) = p.edit().putString("logOff", v.joinToString(",")).apply()

    /** Built-in voice for the spoken warnings: "irina-hq" (female, full-precision Piper, default), "irina", "dmitri", "denis", "ruslan" (+"-hq"), "st-f1"… (Supertonic), "system". */
    var ttsVoice: String
        get() = p.getString("ttsVoice", "irina-hq")!!
        set(v) = p.edit().putString("ttsVoice", v).apply()

    /** Loudness of the spoken warnings: 0 normal, 1 louder (compressed), 2 loudest (the music pauses for it). */
    var voiceLoud: Int
        get() = p.getInt("voiceLoud", 1)
        set(v) = p.edit().putInt("voiceLoud", v).apply()

    /** Player app the Media screen's «Приложения» shows and controls: a package, "fyt" for the unit's stock player. */
    var musicApp: String
        get() = p.getString("musicApp", "")!!
        set(v) = p.edit().putString("musicApp", v).apply()

    /** The Media screen's source: "files" (own player), "bt", "radio", "apps". */
    var mediaSource: String
        get() = p.getString("mediaSource", "files")!!
        set(v) = p.edit().putString("mediaSource", v).apply()

    /** The last «Обучение» reading (LearnedValues.Result as JSON), shown until the next one. */
    var learnedLast: String
        get() = p.getString("learnedLast", "")!!
        set(v) = p.edit().putString("learnedLast", v).apply()

    /** Last automatic look for a newer release. */
    var lastUpdateCheckMs: Long
        get() = p.getLong("updCheck", 0L)
        set(v) = p.edit().putLong("updCheck", v).apply()

    /** Spoken warnings (IAM, overheating, maintenance…); without a Russian voice on the unit it is a beep. */
    /** «Мотор» in «Мониторинг»: the 3D engine with callouts, or the classic three panels. */
    var engine3d: Boolean
        get() = p.getBoolean("engine3d", true)
        set(v) = p.edit().putBoolean("engine3d", v).apply()

    /** Read the ROM's Knock Sum 1–4 (16 bytes more per poll, so off until asked for in «Откаты и наддув»). */
    var knockSums: Boolean
        get() = p.getBoolean("knockSums", false)
        set(v) { p.edit().putBoolean("knockSums", v).apply(); Repo.featureVersion.value++ }

    /** Beep on a timing pull from this many degrees (0 = off), and only from [knockBeepRpm]: −1° low down beeps all the time. */
    var knockBeepDeg: Float
        get() = p.getFloat("knockBeepDeg", 0f)
        set(v) = p.edit().putFloat("knockBeepDeg", v).apply()
    var knockBeepRpm: Int
        get() = p.getInt("knockBeepRpm", 3000)
        set(v) = p.edit().putInt("knockBeepRpm", v).apply()
    var voiceAlerts: Boolean
        get() = p.getBoolean("voiceAlerts", true)
        set(v) = p.edit().putBoolean("voiceAlerts", v).apply()

    /** Shift light: the screen edges flash from [shiftRpm]. */
    var shiftLight: Boolean
        get() = p.getBoolean("shiftLight", false)
        set(v) = p.edit().putBoolean("shiftLight", v).apply()
    var shiftRpm: Int
        get() = p.getInt("shiftRpm", 6300)
        set(v) = p.edit().putInt("shiftRpm", v).apply()

    /** Car with driver and fuel, kg: for the "dyno" power estimate. */
    var massKg: Int
        get() = p.getInt("massKg", 1550)
        set(v) = p.edit().putInt("massKg", v).apply()

    /** Odometer the user typed in, and the app's own trip total at that moment (km). */
    var odoBaseKm: Double
        get() = p.getFloat("odoBase", -1f).toDouble()
        set(v) = p.edit().putFloat("odoBase", v.toFloat()).apply()
    var odoBaseTripsKm: Double
        get() = p.getFloat("odoBaseTrips", 0f).toDouble()
        set(v) = p.edit().putFloat("odoBaseTrips", v.toFloat()).apply()

    /** Engine running hours the app has counted (plus what the user set), for oil changes by hours. */
    /** When the engine was last switched off (for how long the car stood before a resting reading). */
    var lastEngineOffMs: Long
        get() = p.getLong("lastEngineOffMs", 0L)
        set(v) = p.edit().putLong("lastEngineOffMs", v).apply()

    var engineHours: Double
        get() = p.getFloat("engineHours", 0f).toDouble()
        set(v) = p.edit().putFloat("engineHours", v.toFloat()).apply()

    /** Maintenance items as JSON (see Maintenance). */
    var maintenance: String
        get() = str("maintenance", "")
        set(v) = putStr("maintenance", v)

    /** IAM readings worth keeping (start of each drive), JSON list. */
    var iamLog: String
        get() = str("iamLog", "[]")
        set(v) = putStr("iamLog", v)

    /** Acceleration runs, JSON list (newest first). */
    var perfRuns: String
        get() = str("perfRuns", "[]")
        set(v) = putStr("perfRuns", v)

    /** Laps of the lap timer, JSON list (oldest first). */
    var perfLaps: String
        get() = str("perfLaps", "[]")
        set(v) = putStr("perfLaps", v)

    /** Intervals the acceleration runs are timed on, "0-100,100-150,…" (km/h; 0 = from a standstill). */
    var perfIntervals: String
        get() = str("perfIntervals", "0-60,0-100,0-150,0-200,60-100,80-120,100-150,100-200")
        set(v) = putStr("perfIntervals", v)

    /** What new runs are marked with for the series: the fuel or the mod ("98", "присадка"), and a free note (passengers). */
    var perfLabel: String
        get() = str("perfLabel", "")
        set(v) = putStr("perfLabel", v)
    var perfNote: String
        get() = str("perfNote", "")
        set(v) = putStr("perfNote", v)

    /** Spool tests, JSON list (newest first). */
    var perfSpool: String
        get() = str("perfSpool", "[]")
        set(v) = putStr("perfSpool", v)

    /** Lap timer start/finish line "lat,lon", "" = not set. */
    var lapLine: String
        get() = str("lapLine", "")
        set(v) = putStr("lapLine", v)

    /** Bluetooth tyre pressure sensors. */
    var tpms: Boolean
        get() = p.getBoolean("tpms", false)
        set(v) = p.edit().putBoolean("tpms", v).apply()
    var tpmsMinBar: Float
        get() = p.getFloat("tpmsMin", 1.8f)
        set(v) = p.edit().putFloat("tpmsMin", v).apply()
    /** Sensor address → wheel (FL, FR, RL, RR) where the user moved one, JSON object. */
    var tpmsWheels: String
        get() = str("tpmsWheels", "{}")
        set(v) = putStr("tpmsWheels", v)

    /** Head unit tilt sensor zero: gravity at rest "x,y,z", "" = not calibrated. */
    var tiltZero: String
        get() = str("tiltZero", "")
        set(v) = putStr("tiltZero", v)

    /** What a USB adapter turned out to be in "Auto": ELM327 speed, 0 = KKL, null = not asked yet. */
    fun usbKind(id: String): Int? = if (p.contains("usb:$id")) p.getInt("usb:$id", 0) else null
    fun setUsbKind(id: String, baud: Int) = p.edit().putInt("usb:$id", baud).apply()

    /** Wi-Fi ELM327 address, "host:port"; almost all of them are 192.168.0.10:35000. */
    var wifiHost: String
        get() = p.getString("wifiHost", "192.168.0.10:35000")!!
        set(v) = p.edit().putString("wifiHost", v).apply()

    /** Bus the ECU is on: "kline", "can" or "auto" (K-line first, then CAN). CAN needs the OpenPort. */
    var bus: String
        get() = p.getString("bus", "auto")!!
        set(v) = p.edit().putString("bus", v).apply()

    /** OpenPort 2.0: also listen to what the units send on CAN by themselves (ssm/CanBroadcast). */
    var canListen: Boolean
        get() = p.getBoolean("canListen", false)
        set(v) = p.edit().putBoolean("canListen", v).apply()

    /** OBD-II on CAN: the make's own parameters from a downloaded OBDb set (ssm/Obdb), shown on «Датчики». */
    var obdbOn: Boolean
        get() = p.getBoolean("obdbOn", false)
        set(v) = p.edit().putBoolean("obdbOn", v).apply()

    /** The OBDb set picked for this car ("Toyota-RAV4"); "" = the only one downloaded for the VIN's make. */
    var obdbRepo: String
        get() = p.getString("obdbRepo", "")!!
        set(v) = p.edit().putString("obdbRepo", v).apply()

    /** The header text when the ECU has not said which car it is (or the driver's own name). */
    var carTitle: String
        get() = p.getString("carTitle", "FORESTER SG5")!!
        set(v) { p.edit().putString("carTitle", v).apply(); Repo.carVersion.value++ }

    /** The driver typed the header's name himself: it stays even when the ECU says which car it is. */
    var carTitleCustom: Boolean
        // before the flag: a name that isn't one of the model presets was typed by the driver
        get() = p.getBoolean("carTitleCustom", carTitle !in setOf("FORESTER SG5", "FORESTER SH", "IMPREZA", "LEGACY", "SUBARU", ""))
        set(v) { p.edit().putBoolean("carTitleCustom", v).apply(); Repo.carVersion.value++ }
    var carSub: String
        get() = p.getString("carSub", "2003   EJ20 turbo")!!
        set(v) { p.edit().putString("carSub", v).apply(); Repo.carVersion.value++ }

    /** "at" (automatic, TCU at 0x18) or "mt" (manual: no transmission unit, no АКПП screen). */
    var gearbox: String
        get() = p.getString("gearbox", "at")!!
        set(v) { p.edit().putString("gearbox", v).apply(); Repo.carVersion.value++ }

    /** Automatic AWD layout for the split estimate: "mpt" (front-biased clutch) or "vtd" (45:55 centre diff). */
    var awd: String
        get() = p.getString("awd", "mpt")!!
        set(v) { p.edit().putString("awd", v).apply(); Repo.carVersion.value++ }

    /** 3D car on the home screen: "SG" or "SH" (see CarModels3d). */
    var car3d: String
        get() = p.getString("car3d", "SG")!!
        set(v) { p.edit().putString("car3d", v).apply(); Repo.car3d.value = v }
    /** The driver picked the 3D car himself: the ECU's car doesn't replace it. */
    var car3dCustom: Boolean
        get() = p.getBoolean("car3dCustom", false)
        set(v) = p.edit().putBoolean("car3dCustom", v).apply()

    /** «Тип машины»: "auto" (by the ECU and the VIN), "subaru", "other" (any car on plain OBD-II, see CarKind). */
    var carKind: String
        get() = p.getString("carKind", "auto")!!
        set(v) { p.edit().putString("carKind", v).apply(); Repo.carVersion.value++ }
    /** What "auto" decided at the last connection ("subaru" / "other"), for the start before the car answers. */
    var carKindLast: String
        get() = p.getString("carKindLast", "subaru")!!
        set(v) = p.edit().putString("carKindLast", v).apply()
    /** The VIN of the last car that gave one: its make for the header, the logo and the start-up screen. */
    var lastVin: String
        get() = p.getString("lastVin", "")!!
        set(v) = p.edit().putString("lastVin", v).apply()
    /** «Логотип в шапке»: "auto" (by the car), "subaru", "custom" (own picture, [logoImage]), "none". */
    var logoMode: String
        get() = p.getString("logoMode", "auto")!!
        set(v) { p.edit().putString("logoMode", v).apply(); Repo.carVersion.value++ }
    var logoImage: String
        get() = p.getString("logoImage", "")!!
        set(v) { p.edit().putString("logoImage", v).apply(); Repo.carVersion.value++ }
    /**
     * «Экран загрузки»: "route" (the neon road picture, the default since 0.9-beta21), "subaru" (the Subaru picture of
     * earlier versions), "car" (the car's logo), "custom" (own picture, [splashImage]), "name".
     */
    var splashMode: String
        get() = p.getString("splashMode", "route")!!
        set(v) { p.edit().putString("splashMode", v).apply(); Repo.carVersion.value++ }
    var splashImage: String
        get() = p.getString("splashImage", "")!!
        set(v) { p.edit().putString("splashImage", v).apply(); Repo.carVersion.value++ }

    /** The ROM ID of the last ECU: another one = another car (its own boost, MAF and load scales). */
    var lastRomId: String
        get() = p.getString("lastRomId", "")!!
        set(v) = p.edit().putString("lastRomId", v).apply()

    /** The car on the sports ECU (CanEcu) is a turbo: boost was seen once; a Link says nothing about its engine. */
    var linkTurbo: Boolean
        get() = p.getBoolean("linkTurbo", false)
        set(v) = p.edit().putBoolean("linkTurbo", v).apply()

    /** Injector flow, cc/min, for the fuel count without a MAF (a Link car's injectors are rarely stock); 0 = by the engine. */
    var injectorCc: Int
        get() = p.getInt("injectorCc", 0)
        set(v) = p.edit().putInt("injectorCc", v).apply()

    /** Injector opening time (dead time) at 14 V, ms, for the same count; 0 = the usual 0.6 (see FuelCalc.deadTimeMs). */
    var injectorDeadMs: Float
        get() = p.getFloat("injectorDeadMs", 0f)
        set(v) = p.edit().putFloat("injectorDeadMs", v).apply()

    /** Body colour of the 3D car (see CarPaints). */
    var carPaint: String
        get() = p.getString("carPaint", "chameleon")!!
        set(v) { p.edit().putString("carPaint", v).apply(); Repo.carPaint.value = v }

    /** Home screen cards (see HomeLayout), "" = factory layout. */
    var homeTiles: String
        get() = p.getString("homeTiles", "")!!
        set(v) { p.edit().putString("homeTiles", v).apply(); Repo.homeTiles.value = v }

    /** Keep polling, fuel counting and logging when the app is not on screen (the service outlives the UI). */
    var backgroundWork: Boolean
        get() = p.getBoolean("backgroundWork", true)
        set(v) { p.edit().putBoolean("backgroundWork", v).apply() }

    /** Start the service when the head unit / phone starts or wakes up (ACC on). */
    var autostartService: Boolean
        get() = p.getBoolean("autostartService", !phone)
        set(v) { p.edit().putBoolean("autostartService", v).apply() }

    /** Also bring the app on screen then (needs "display over other apps" on Android 10+). */
    var autostartApp: Boolean
        get() = p.getBoolean("autostartApp", false)
        set(v) { p.edit().putBoolean("autostartApp", v).apply() }

    /** App the mini-map opens on a tap: a package, or "" for the "Навигатор" shortcut. */
    var mapApp: String
        get() = p.getString("mapApp", "")!!
        set(v) { p.edit().putString("mapApp", v).apply() }

    /** Daily backup into Download/CarPulse (see Backup). */
    var autoBackup: Boolean
        get() = p.getBoolean("autoBackup", true)
        set(v) { p.edit().putBoolean("autoBackup", v).apply() }

    var lastAutoBackupMs: Long
        get() = p.getLong("lastAutoBackupMs", 0L)
        set(v) { p.edit().putLong("lastAutoBackupMs", v).apply() }

    /** Layout of any editable screen (see Board), by its settings key. */
    fun board(key: String) = p.getString(key, "")!!
    fun setBoard(key: String, v: String) { p.edit().putString(key, v).apply(); Repo.boardFlow(key).value = v }

    /** «Мой экран» as the driver had set it before a ready-made layout replaced it ("" = none), for «Вернуть мой экран». */
    var myTilesPrev: String
        get() = p.getString("myTilesPrev", "")!!
        set(v) { p.edit().putString("myTilesPrev", v).apply() }

    /** The cluster route's destination (Place.format, "" = none), kept so the route comes back after a restart. */
    var navDest: String
        get() = p.getString("navDest", "")!!
        set(v) { p.edit().putString("navDest", v).apply() }

    /** The last places driven to, newest first, one Place.format per line. */
    var navRecent: String
        get() = p.getString("navRecent", "")!!
        set(v) { p.edit().putString("navRecent", v).apply() }

    /** Also send the destination to the navigator, so it guides along the same way. */
    var navHandoff: Boolean
        get() = p.getBoolean("navHandoff", true)
        set(v) { p.edit().putBoolean("navHandoff", v).apply() }

    /** 3D car + dials block: 0 hidden, 1 normal, 2 large, 3 huge. */
    var clusterSize: Int
        get() = p.getInt("clusterSize", 1)
        set(v) { p.edit().putInt("clusterSize", v).apply(); Repo.clusterSize.value = v }

    /** Interface colour theme: an ui.AccentTheme id or "custom:RRGGBB". */
    var accentTheme: String
        get() = p.getString("accentTheme", "violet")!!
        set(v) { p.edit().putString("accentTheme", v).apply(); kg.forester.sg.ui.C.applyTheme(v) }

    /** Gauge designs (see DialStyles), separately for the tachometer and the speedometer. */
    var tachStyle: String
        get() = p.getString("tachStyle", "neon")!!
        set(v) { p.edit().putString("tachStyle", v).apply(); Repo.dialStyles.value = v to speedoStyle }

    var speedoStyle: String
        get() = p.getString("speedoStyle", "neon")!!
        set(v) { p.edit().putString("speedoStyle", v).apply(); Repo.dialStyles.value = tachStyle to v }

    /** Generated data instead of the car, to look at the screens. */
    /**
     * Demo data instead of the car: only until the app is restarted, never saved. Kept in the preferences it came
     * back on its own (a restart, Android restoring a backup after a reinstall) and people took the made-up drive
     * for their car.
     */
    var demo: Boolean
        get() = Repo.demoOn.value
        set(v) { Repo.demoOn.value = v }

    /** The first-start question (model and adapter) was answered. */
    var setupDone: Boolean
        get() = p.getBoolean("setupDone", false)
        set(v) = p.edit().putBoolean("setupDone", v).apply()

    /** Raw adapter exchange log for testers (logs/ssm_trace.txt). */
    var traceEnabled: Boolean
        get() = p.getBoolean("trace", false)
        set(v) { p.edit().putBoolean("trace", v).apply(); kg.forester.sg.ssm.SsmTrace.enabled = v }

    /** Interface scale in percent, 0 = automatic (fit a 1280×720-like layout). */
    var uiScale: Int
        get() = p.getInt("uiScale", 0)
        set(v) { p.edit().putInt("uiScale", v).apply(); Repo.uiScale.value = v }

    var currentTripId: Long
        get() = p.getLong("tripId", 0)
        set(v) = p.edit().putLong("tripId", v).apply()
}

/** Process-wide state shared between the service and the UI. */
object Repo {
    lateinit var db: Db
    lateinit var settings: Settings

    val link = MutableStateFlow(LinkState.NO_CABLE)
    val linkMessage = MutableStateFlow("")
    val ecuInit = MutableStateFlow<EcuInit?>(null)
    /** The init as the ECU sent it, before the bits turned on by hand (EcuCaps). */
    val ecuInitRaw = MutableStateFlow<EcuInit?>(null)
    /** Bumped when those bits change: the connection starts over with them. */
    val capsVersion = MutableStateFlow(0)
    val live = MutableStateFlow(EcuSnapshot())
    val gps = MutableStateFlow<GpsFix?>(null)
    /** Why there is no GPS position ("" = there is one), for the GPS card and the mini-map. */
    val gpsStatus = MutableStateFlow("")
    /** Satellites used in the fix and seen, from GnssStatus (the header's satellite icon); 0 to 0 before the first report. */
    val gnssSats = MutableStateFlow(0 to 0)
    val trip = MutableStateFlow(TripState())
    val dataVersion = MutableStateFlow(0L) // bump to make screens re-query the DB
    val logFile = MutableStateFlow<String?>(null)

    /** Last ~60 s of samples for the live charts. */
    val history = MutableStateFlow<List<EcuSnapshot>>(emptyList())
    val tcuInit = MutableStateFlow<EcuInit?>(null)
    val tcu = MutableStateFlow<TcuSnapshot?>(null)
    val tcuHistory = MutableStateFlow<List<TcuSnapshot>>(emptyList())
    val tcuStatus = MutableStateFlow("АКПП: ожидание")
    val battery = MutableStateFlow(BatteryState())
    val knock = MutableStateFlow(KnockStats())
    val navPosition = MutableStateFlow("left")
    val carousel = MutableStateFlow<List<String>>(emptyList())
    val freeGrid = MutableStateFlow("10x6")
    val bottomBar = MutableStateFlow("")
    val topBar = MutableStateFlow("")
    val slotVersion = MutableStateFlow(0)
    val customVersion = MutableStateFlow(0)
    /** Bumped when the driver's own RomRaider files or their roles change: the polled parameters are chosen again. */
    val defsVersion = MutableStateFlow(0)
    /** VIN read from the ECU at connect ("" = not available). */
    val vin = MutableStateFlow("")
    /** Another car's engine unit by OBD mode 09: its name and calibration id ("ECM-EngineControl · CALID …"), "" = unknown. */
    val obdEcu = MutableStateFlow("")
    val uiScale = MutableStateFlow(0)
    val car3d = MutableStateFlow("SG")
    val carPaint = MutableStateFlow("chameleon")
    val homeTiles = MutableStateFlow("")
    val driveTiles = MutableStateFlow("")
    val myTiles = MutableStateFlow("")
    val tcuTiles = MutableStateFlow("")
    fun boardFlow(key: String) = when (key) {
        "driveTiles" -> driveTiles
        "myTiles" -> myTiles
        "tcuTiles" -> tcuTiles
        else -> homeTiles
    }
    /** tachometer style id to speedometer style id */
    val dialStyles = MutableStateFlow("neon" to "neon")
    val clusterSize = MutableStateFlow(1)
    val carVersion = MutableStateFlow(0)
    /** How many parameters the ECU is polled for (fewer than all = some are not supported). */
    val pollAddresses = MutableStateFlow(0)
    /** What the connected engine and transmission units can give from the RomRaider list. */
    val rrAvailable = MutableStateFlow<List<kg.forester.sg.ssm.RrParam>>(emptyList())
    /** Latest values of the ticked parameters, by id. */
    val rrValues = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Ticked parameters that are being read now (the rest are not supported or answered nothing). */
    val rrPolled = MutableStateFlow<List<kg.forester.sg.ssm.RrParam>>(emptyList())
    val watchVersion = MutableStateFlow(0)
    /** RomRaider parameters the app itself needs (IAM, knock corrections, target boost, VDC G sensor…). */
    val internalWatch = MutableStateFlow<Set<String>>(emptySet())
    val guard = MutableStateFlow(kg.forester.sg.service.GuardState())
    val peaks = MutableStateFlow(kg.forester.sg.service.Peaks())
    val motion = MutableStateFlow(kg.forester.sg.service.MotionState())
    val perf = MutableStateFlow(kg.forester.sg.service.PerfState())
    val tyres = MutableStateFlow<Map<String, kg.forester.sg.service.TyreReading>>(emptyMap())
    val shift = MutableStateFlow(false)
    /** Lowest and highest value of each home card since the trip started, by card id. */
    val tripRange = MutableStateFlow<Map<String, Pair<Float, Float>>>(emptyMap())
    val maintenanceVersion = MutableStateFlow(0)
    val featureVersion = MutableStateFlow(0)
    /** The ECU answers only standard OBD-II (no SSM): basic parameters, no transmission data. */
    val obdMode = MutableStateFlow(false)
    /** What the automatic adapter search found, e.g. "USB 1A86:7523 → KKL". */
    val adapterFound = MutableStateFlow("")

    /** Demo mode on (see [Settings.demo]). */
    val demoOn = MutableStateFlow(false)

    /** A free layout is being edited: the bottom bar steps aside for it (see FreeFrame). */
    val freeEditing = MutableStateFlow(false)

    /**
     * The adapter was let go for another app (BTSSM and the like): no polling, the cable or socket closed, until
     * «Подключиться» or the next start of the app.
     */
    val adapterReleased = MutableStateFlow(false)

    /** «Демо Link» (adb only, till the app restarts): a made-up Link G4X on a CANable, see LinkDemo. */
    val linkDemo = MutableStateFlow(false)

    /** Logs of the acceleration runs and laps; the demo's own go to the cache and are gone with the next start. */
    lateinit var perfFiles: java.io.File
    lateinit var perfDemoFiles: java.io.File

    fun init(ctx: Context) {
        if (!::db.isInitialized) {
            perfFiles = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "perf")
            perfDemoFiles = java.io.File(ctx.cacheDir, "perf-demo").also { it.deleteRecursively() }
            db = Db(ctx.applicationContext)
            settings = Settings(ctx.applicationContext)
            // older versions saved it: a leftover "on" must not start the demo by itself
            ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("demo").apply()
            BoxPresets.load(ctx.applicationContext)
            SensorBox.load()
            Wideband.load()
            Relays.load()
            Displays.load()
            UsbScreen.load()
            FuelLine.load()
            navPosition.value = settings.navPosition
            carousel.value = settings.carousel
            freeGrid.value = settings.freeGrid
            bottomBar.value = settings.bottomBar
            topBar.value = settings.topBar
            uiScale.value = settings.uiScale
            car3d.value = settings.car3d
            carPaint.value = settings.carPaint
            homeTiles.value = settings.homeTiles
            driveTiles.value = settings.board("driveTiles")
            myTiles.value = settings.board("myTiles")
            tcuTiles.value = settings.board("tcuTiles")
            dialStyles.value = settings.tachStyle to settings.speedoStyle
            clusterSize.value = settings.clusterSize
            kg.forester.sg.ui.C.applyTheme(settings.accentTheme)
            kg.forester.sg.ssm.SsmTrace.enabled = settings.traceEnabled
            CustomGauges.init(ctx)
            Scales.init(ctx)
            CarKind.start(ctx)
        }
    }

    fun touch() {
        dataVersion.value = System.currentTimeMillis()
    }
}

