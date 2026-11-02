package kg.forester.sg.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kg.forester.sg.data.Repo
import kg.forester.sg.ssm.EngineId
import kg.forester.sg.ssm.EngineInfo

/**
 * The car in the header from what the ECU says (its ROM: "JDM Forester XT 2003 AT" → «FORESTER XT», «2003 · EJ205 ·
 * 2.0 · турбо»), so nobody has to type it in. A name the driver gave the car himself stays; without an ECU yet the
 * settings' text is shown as before.
 */
@Composable
fun rememberCarHeader(): Pair<String, String> {
    val ctx = LocalContext.current
    val init by Repo.ecuInit.collectAsState()
    val demo by Repo.demoOn.collectAsState()
    Repo.carVersion.collectAsState().value
    val s = Repo.settings
    val guard = Repo.guard.collectAsState()
    val turbo by remember { androidx.compose.runtime.derivedStateOf { guard.value.turboCar } }
    val engine = remember(init, demo, turbo) { EngineId.detect(ctx) }
    // another make: what its VIN says (the make, the model year), no Subaru model or engine
    val other by kg.forester.sg.data.CarKind.other.collectAsState()
    if (other) {
        val make by kg.forester.sg.data.CarKind.make.collectAsState()
        val vin by Repo.vin.collectAsState()
        val link by Repo.link.collectAsState()
        return kg.forester.sg.data.CarKind.header(
            make?.name, vin.ifEmpty { s.lastVin }, link == kg.forester.sg.data.LinkState.CONNECTED,
            java.util.Calendar.getInstance().get(java.util.Calendar.YEAR), s.carTitle, s.carTitleCustom,
        )
    }
    return carHeader(engine, s.carTitle, s.carSub, s.carTitleCustom)
}

/** The 3D car closest to the ECU's car ("JDM Forester XT 2003 AT" → SG5), null when nothing fits. */
fun car3dFor(car: String): String? {
    val e = EngineId.fromCar(car)
    val u = car.uppercase()
    val year = u.split(' ').mapNotNull { it.toIntOrNull() }.firstOrNull { it in 1990..2030 }
    return when {
        "FORESTER" in u -> when {
            (year ?: 2005) <= 2007 -> if (e.turbo) "SG" else "SGNA"
            (year ?: 2010) <= 2012 -> if (e.turbo) "SH" else "SHNA"
            else -> "SK"
        }
        "IMPREZA" in u || "WRX" in u -> when {
            (year ?: 2005) <= 2007 -> if ("STI" in u) "GD" else "GG"
            (year ?: 2010) <= 2014 -> "GV"
            else -> "VA"
        }
        "LEGACY" in u || "LIBERTY" in u || "OUTBACK" in u -> "BM"
        else -> null
    }
}

/** The stand-in for a car nobody can name (see [car3dShown]). */
const val CAR3D_UNKNOWN = "GT3R"

/** Earlier builds stored the old stand-in under this id. */
fun car3dStoredId(stored: String): String = if (stored == "RAY") CAR3D_UNKNOWN else stored

/** The stand-in keeps the livery it was rendered in, whatever colour was chosen for another car. */
fun car3dPaintId(model: String, paintId: String): String = if (model == CAR3D_UNKNOWN) "chameleon" else paintId

/**
 * The 3D car on the screen. [stored] and [custom] are the settings, [other] the car kind, [demo] the demo running: a
 * model picked by hand always stays; the demo is a Subaru, whatever was connected last; another make gets the stand-in,
 * and its model stays stored for when the Subaru comes back.
 */
fun car3dShown(stored: String, custom: Boolean, other: Boolean, demo: Boolean): String = when {
    custom -> stored
    demo -> if (stored == CAR3D_UNKNOWN) "SG" else stored
    other -> CAR3D_UNKNOWN
    else -> stored
}

/**
 * What a connected ECU leaves stored; [car] is what its ROM says, null when the ROM names no car. A named car that has
 * no 3D model of its own (Tribeca, Baja) is determined: it keeps what it had, only never the stand-in.
 */
fun car3dFromEcu(stored: String, custom: Boolean, car: String?): String = when {
    custom -> stored
    car == null -> CAR3D_UNKNOWN
    else -> car3dFor(car) ?: if (stored == CAR3D_UNKNOWN) "SG" else stored
}

/** «Автоматически»: what stays stored when the driver gives the choice back; with no ROM seen yet, as it is. */
fun car3dAutomatic(stored: String, romId: String, car: String?): String =
    if (romId.isEmpty() || romId == "LINK") stored else car3dFromEcu(stored, false, car)

/** The 3D car to show now, for the screens. */
@Composable
fun car3dNow(): String {
    val stored by Repo.car3d.collectAsState()
    val custom by Repo.car3dCustom.collectAsState()
    val other by kg.forester.sg.data.CarKind.other.collectAsState()
    val demo by Repo.demoOn.collectAsState()
    return car3dShown(stored, custom, other, demo)
}

/** Title and caption for [e]; [ownTitle] is kept when [custom] (the driver typed it). */
fun carHeader(e: EngineInfo, title: String, sub: String, custom: Boolean): Pair<String, String> {
    // nothing typed in: no ECU yet means nothing known, not a made-up "2003 EJ20 turbo"
    if (!e.known) return (if (custom && title.isNotBlank()) title else "SUBARU") to when (e.source) {
        "демо" -> "демо-данные"
        "ЭБУ без модели" -> "ЭБУ ${e.romId}"
        // a sports ECU on a CANable has no ROM to tell the car by
        else -> if (!kg.forester.sg.data.CanEcu.active) "подключите ЭБУ"
            else if (kg.forester.sg.ssm.LinkDash.id != null) "ЭБУ Link по CAN" else "CANable на шине"
    }
    val car = e.car
    val words = car?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    val year = words.firstOrNull { w -> w.toIntOrNull()?.let { it in 1990..2030 } == true }
    // the model: without the market, the year and the gearbox ("MT", "AT", "MT/AT", "5EAT", "CVT")
    val model = words.drop(1).filter { w ->
        w != year && w.uppercase() !in setOf("MT", "AT", "MT/AT", "AT/MT", "5EAT", "4EAT", "CVT", "6MT")
    }.joinToString(" ").uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("\\s+"), " ").trim()
    // the driver's own name, else the model by the ECU's ROM, else just the make (never a made-up model)
    val t = if (custom && title.isNotBlank()) title else model.ifEmpty { "SUBARU" }
    val engine = listOf(e.code, e.litres, if (e.diesel) "дизель" else if (e.turbo) "турбо" else "атмо").filter { it.isNotEmpty() }.joinToString(" · ")
    return t to listOfNotNull(year, engine).joinToString(" · ")
}
