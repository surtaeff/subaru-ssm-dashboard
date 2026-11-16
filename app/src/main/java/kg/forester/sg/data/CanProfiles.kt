package kg.forester.sg.data

import kg.forester.sg.ssm.CanDb
import kg.forester.sg.ssm.CanImport
import kg.forester.sg.ssm.CanMsg
import kg.forester.sg.ssm.CanSig

/**
 * «Готовые профили»: the dash streams of third-party ECUs and CAN sensors, added in one tap. Each profile is a
 * description in assets/can_profiles (a RealDash XML of janimm/RealDash-extras, public domain, as it is; or a short
 * DBC written for the app from public facts, its sources in its first comment) and the values a dash usually wants
 * from it, with Russian names and roles. The rest of the file can be ticked by hand.
 */
class CanProfile(
    val key: String,
    val title: String,
    /** The adapter's bus speed, as the driver sets it. */
    val kbit: String,
    /** The ids it sends on. */
    val ids: String,
    val about: String,
    /** Where the layout comes from. */
    val source: String,
    /** Checked against a second source or the maker's description; false = «не проверено». */
    val verified: Boolean,
    val asset: String? = null,
    val picks: List<CanPick> = emptyList(),
    /** A template: one channel to fill in by hand (the form opens with it). */
    val template: ((String) -> CanChannel)? = null,
)

/** A value of a profile ticked by default: signal [signal] of message [msg], under a Russian [name]. */
class CanPick(
    val msg: Int,
    val signal: String,
    val name: String,
    val role: String? = null,
    /** Lambda shown as petrol AFR (×14.7): what the AFR role reads. */
    val afr: Boolean = false,
    val units: String? = null,
)

object CanProfiles {
    private const val AFR = 14.7

    val all: List<CanProfile> = listOf(
        CanProfile(
            "aem_x", "AEM X-Series (ШДК)", "500 кбит", "0x180 (29 бит), второй контроллер 0x181",
            "Контроллер ШДК AEM 30-0300/30-0310 по AEMnet (CAN). Терминатора внутри нет: на шине из двух устройств поставьте 120 Ом на обоих концах",
            "Инструкция AEM 30-0300 (скорость, ID, лямбда), код rusEFI AemXSeriesLambda.cpp (флаги)", true,
            "aem_xseries_wideband.dbc",
            listOf(
                CanPick(0x180, "Lambda", "AFR (AEM)", "afr", afr = true),
                CanPick(0x180, "Lambda", "Лямбда (AEM)", ""),
                CanPick(0x180, "LambdaValid", "AEM: лямбда готова", ""),
            ),
        ),
        CanProfile(
            "rusefi_wbo", "rusEFI Wideband (ШДК)", "500 кбит", "0x190 и 0x191, второй контроллер 0x192",
            "Открытый контроллер ШДК rusEFI (mck1117/wideband) и платы на его прошивке",
            "wideband_can.h контроллера (MIT) и декодер rusEFI; скорость шины в них не указана", false,
            "rusefi_wideband.dbc",
            listOf(
                CanPick(0x190, "Lambda", "AFR (rusEFI WBO)", "afr", afr = true),
                CanPick(0x190, "Lambda", "Лямбда (rusEFI WBO)", ""),
                CanPick(0x190, "SensorTemperature", "Температура датчика ШДК", ""),
            ),
        ),
        CanProfile(
            "emu_black", "ECUMaster EMU Black", "500 кбит или 1 Мбит, как в EMU", "0x600–0x607 (база по умолчанию)",
            "Поток «EMU CAN Stream»: включите его в EMU Client (CAN-Bus, Send EMU stream over CAN-Bus)",
            "ecumaster_emublack.xml из RealDash-extras и библиотека EMUcan совпадают; ЕГТ, давление топлива и этанол только по EMUcan", true,
            "ecumaster_emu_stream.dbc",
            listOf(
                CanPick(0x602, "OilTemperature", "Темп. масла (EMU)", "oil"),
                CanPick(0x602, "OilPressure", "Давление масла (EMU)", "oilP"),
                CanPick(0x602, "FuelPressure", "Давление топлива (EMU)", "fuelP"),
                CanPick(0x603, "Lambda", "AFR (EMU)", "afr", afr = true),
                CanPick(0x603, "EGT1", "ЕГТ 1 (EMU)", "egt"),
                CanPick(0x603, "EGT2", "ЕГТ 2 (EMU)", "egt"),
                CanPick(0x604, "Ethanol", "Этанол (EMU)", "eth"),
                CanPick(0x602, "CoolantTemperature", "Темп. ОЖ (EMU)", ""),
                CanPick(0x604, "BatteryVoltage", "Напряжение (EMU)", ""),
            ),
        ),
        CanProfile(
            "haltech", "Haltech (Elite, Nexus)", "1 Мбит", "0x360–0x3EB, 0x469–0x477",
            "Широковещательный поток Haltech для дашбордов, 11-битные ID, старший байт первым. Температуры в нём в кельвинах: приложение пересчитает в °C",
            "haltech_v3_can.xml из RealDash-extras; с протоколом Haltech не сверено", false,
            "haltech_v3_can.xml",
            listOf(
                CanPick(0x361, "Oil pressure", "Давление масла (Haltech)", "oilP"),
                CanPick(0x361, "Fuel pressure", "Давление топлива (Haltech)", "fuelP"),
                CanPick(0x3E0, "Oil temperature", "Темп. масла (Haltech)", "oil"),
                CanPick(0x368, "Lambda 1", "AFR (Haltech)", "afr", afr = true),
                CanPick(0x3E1, "Fuel Composition", "Этанол (Haltech)", "eth", units = "%"),
                CanPick(0x3E0, "Coolant temperature", "Темп. ОЖ (Haltech)", ""),
                CanPick(0x372, "Battery voltage", "Напряжение (Haltech)", "", units = "В"),
            ),
        ),
        CanProfile(
            "maxxecu", "MaxxECU", "500 кбит", "0x520–0x542",
            "Протокол MaxxECU по умолчанию (v1.3): включите его в MTune, CAN-шина, младший байт первым. Быстрые кадры 50 Гц, медленные 10 Гц",
            "maxxecu_default_can.xml из RealDash-extras, сверено со справкой MaxxECU (Default MaxxECU protocol)", true,
            "maxxecu_default_can.xml",
            listOf(
                CanPick(0x536, "Oil pressure", "Давление масла (MaxxECU)", "oilP"),
                CanPick(0x536, "Oil temperature", "Темп. масла (MaxxECU)", "oil"),
                CanPick(0x537, "Fuel pressure", "Давление топлива (MaxxECU)", "fuelP"),
                CanPick(0x520, "Lambda 1", "AFR (MaxxECU)", "afr", afr = true),
                CanPick(0x531, "EGT 1", "ЕГТ 1 (MaxxECU)", "egt"),
                CanPick(0x533, "EGT Highest", "ЕГТ макс. (MaxxECU)", "egt"),
                CanPick(0x531, "Ethanol concentration", "Этанол (MaxxECU)", "eth"),
                CanPick(0x530, "Coolant temperature", "Темп. ОЖ (MaxxECU)", ""),
                CanPick(0x530, "Battery voltage", "Напряжение (MaxxECU)", ""),
            ),
        ),
        CanProfile(
            "rusefi", "rusEFI (поток ЭБУ)", "500 кбит, как в TunerStudio", "0x200–0x209 (база по умолчанию 512)",
            "Широковещательный поток rusEFI, младший байт первым. Базовый ID и скорость задаются в TunerStudio",
            "своё краткое описание по фактам из rusEFI_CAN_verbose.dbc (сам файл GPL и не копировался); на ЭБУ не проверено", false,
            "rusefi_broadcast.dbc",
            listOf(
                CanPick(0x204, "OilPressure", "Давление масла (rusEFI)", "oilP"),
                CanPick(0x204, "OilTemperature", "Темп. масла (rusEFI)", "oil"),
                CanPick(0x207, "Lambda1", "AFR (rusEFI)", "afr", afr = true),
                CanPick(0x209, "EGT1", "ЕГТ 1 (rusEFI)", "egt"),
                CanPick(0x201, "FlexEthanol", "Этанол (rusEFI)", "eth"),
                CanPick(0x203, "CoolantTemperature", "Темп. ОЖ (rusEFI)", ""),
                CanPick(0x204, "BatteryVoltage", "Напряжение (rusEFI)", ""),
            ),
        ),
        CanProfile(
            "megasquirt", "Megasquirt (MS3, поток реального времени)", "500 кбит", "0x5F0–0x61A (1520…)",
            "Real-time Data Broadcasting MS3, старший байт первым. Температуры в нём в °F: приложение пересчитает в °C",
            "megasquirt_realtime_data_broadcasting_can.xml из RealDash-extras", false,
            "megasquirt_realtime_data_broadcasting_can.xml",
            listOf(
                CanPick(1523, "AFR 1", "AFR (Megasquirt)", "afr"),
                CanPick(1522, "Coolant temperature", "Темп. ОЖ (Megasquirt)", ""),
                CanPick(1523, "Battery voltage", "Напряжение (Megasquirt)", ""),
            ),
        ),
        CanProfile(
            "link", "Link G4+/G4X", "как в PCLink (ECU Controls, CAN Setup)", "Dash2Pro: 1000–1003, Generic Dash: один ID",
            "Ничего добавлять не нужно: потоки Dash2Pro и Generic Dash приложение находит само и показывает обороты, давления, " +
                "лямбду и прочее. ЕГТ и другие значения своего потока Link добавьте в «Свои значения из CAN»",
            "link_dash2pro.xml из RealDash-extras и приёмник Generic Dash (jasongaunt, MIT), см. поток Link", true,
        ),
        CanProfile(
            "tpl_afr", "Шаблон: CAN-ШДК", "как у датчика", "свой",
            "Контроллер ШДК с выходом CAN: впишите его ID и байты лямбды, формула даёт AFR (лямбда × 14,7)",
            "шаблон", false,
            template = { id -> CanChannel(id, "AFR (CAN)", 0x400, false, -1, 0, 2, true, false, "x*0.001*14.7", "AFR", 2, "afr") },
        ),
        CanProfile(
            "tpl_egt", "Шаблон: CAN-термопара ЕГТ", "как у датчика", "свой",
            "Плата термопар с выходом CAN: впишите её ID и байты своего канала, формула должна давать °C",
            "шаблон", false,
            template = { id -> CanChannel(id, "ЕГТ (CAN)", 0x400, false, -1, 0, 2, true, false, "x", "°C", 0, "egt") },
        ),
        CanProfile(
            "tpl_oilp", "Шаблон: CAN-датчик давления масла", "как у датчика", "свой",
            "Датчик давления с выходом CAN: впишите его ID и байты; давление по манометру, в единицах из поля «Единицы»",
            "шаблон", false,
            template = { id -> CanChannel(id, "Давление масла (CAN)", 0x400, false, -1, 0, 2, true, false, "x*0.1", "кПа", 0, "oilP") },
        ),
    )

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    /** [pick]'s signal in [db], with its message; null when the file has no such signal. */
    fun find(db: CanDb, pick: CanPick): Pair<CanMsg, CanSig>? =
        db.messages.filter { it.id == pick.msg }.firstNotNullOfOrNull { m -> m.signals.firstOrNull { it.name == pick.signal }?.let { m to it } }

    /** [pick] as a channel [id]: the signal's layout under the profile's name, role and, for AFR, lambda × 14.7. */
    fun channel(db: CanDb, pick: CanPick, id: String): CanChannel? {
        val (m, s) = find(db, pick) ?: return null
        if (s.problem != null) return null
        val (u, f) = CanImport.normalize(s.units, s.formula)
        return if (pick.afr) CanImport.channel(m, s, id, pick.name, pick.role ?: "afr", "($f)*$AFR", "AFR", 2)
        else CanImport.channel(m, s, id, pick.name, pick.role, f, pick.units ?: u)
    }

    /**
     * The same signal read the same way is in [have] already (a profile added twice): [c] would only double it.
     */
    fun duplicate(c: CanChannel, have: List<CanChannel>) = have.any {
        it.canId == c.canId && it.ext == c.ext && it.mux == c.mux && it.startBit == c.startBit && it.bitLen == c.bitLen &&
            (c.bits || (it.byte == c.byte && it.len == c.len)) && it.bigEndian == c.bigEndian && it.formula == c.formula
    }

    /** Adds [list] to the own channels but those already there; how many were added. */
    fun add(list: List<(String) -> CanChannel?>): Pair<Int, Int> {
        var added = 0
        var same = 0
        for (make in list) {
            val c = make(CanChannels.newId()) ?: continue
            if (duplicate(c, CanChannels.list())) { same++; continue }
            CanChannels.put(c)
            added++
        }
        return added to same
    }
}
