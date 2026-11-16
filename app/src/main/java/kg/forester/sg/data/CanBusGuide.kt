package kg.forester.sg.data

/**
 * What the CAN connection wizard («Мастер подключения CAN») knows: why the driver connects (a sensor of their own, the
 * car's bus, a sports ECU), which bus of which make runs at what speed and where it is reached, how to read a
 * multimeter across the bus, and where a gateway hides the traffic from the diagnostic socket. Data only (tested); the
 * screens are in ui/CanWizard.kt. Speeds are [kg.forester.sg.ssm.CanBitrates] kbit (83 = 83.3, 33 = 33.3).
 */
object CanBusGuide {
    enum class Scenario(val id: String, val title: String, val sub: String) {
        SENSOR("sensor", "Свой датчик", "CAN-плата или датчик (широкополосная лямбда, ЕГТ, давление) на своей шине: адаптер и датчик вдвоём"),
        CAR("car", "Шина машины", "Подслушать штатную шину: обороты, скорость, кнопки, климат. Только приём, в шину ничего не отправляется"),
        ECU("ecu", "Спортивный ЭБУ", "Поток для дашборда от Link, Haltech, ECUMaster и других: ЭБУ шлёт его сам"),
        ;

        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** The bus's wires: ordinary two-wire high speed, two-wire fault-tolerant (ISO 11898-3), one wire (SAE J2411). */
    enum class Phys(val title: String) {
        HIGH_SPEED("обычная двухпроводная"),
        FAULT_TOLERANT("отказоустойчивая (ISO 11898-3)"),
        SINGLE_WIRE("однопроводная (SAE J2411)"),
    }

    /** Where to take the bus from. */
    enum class Tap { OBD, HEAD_UNIT, OWN_BUS }

    class Bus(
        val id: String,
        val make: String,
        val name: String,
        val kbit: Int,
        /** The wires at the OBD socket (CAN-H, CAN-L); null when the bus isn't there. */
        val obdPins: Pair<Int, Int>?,
        val tap: Tap,
        val phys: Phys = Phys.HIGH_SPEED,
        /** Where else (or where exactly) to reach it, in the driver's words. */
        val where: String = "",
        val note: String = "",
    ) {
        /** The adapter's transceiver (a CANable's is a high-speed one) can't read this bus as it is. */
        val needsOtherTransceiver: Boolean get() = phys != Phys.HIGH_SPEED
        val transceiverNote: String? get() = when (phys) {
            Phys.HIGH_SPEED -> null
            Phys.FAULT_TOLERANT -> "Нужен другой трансивер: отказоустойчивый TJA1055 (ISO 11898-3). Обычный CANable эту шину не прочитает, а подключённый к ней может мешать"
            Phys.SINGLE_WIRE -> "Нужен однопроводный трансивер (TH8056 или похожий, SAE J2411): у шины один провод и масса. Обычный CANable её не прочитает"
        }
    }

    /** The makes in the picker; «Другая» last. */
    val MAKES = listOf("VW, Audi, Skoda, Seat", "BMW", "Mercedes", "GM: Chevrolet, Opel, Cadillac", "Ford", "Jeep, Chrysler, Dodge, Fiat", "Subaru", "Другая")

    val BUSES: List<Bus> = listOf(
        Bus("vag_comfort", MAKES[0], "Комфорт (Komfort) и инфотейнмент", 100, null, Tap.HEAD_UNIT,
            where = "Разъём магнитолы или блока комфорта: витая пара CAN-H/CAN-L",
            note = "До 2000 г. шина комфорта шла на 62,5 кбит. У VW ID.4 и других на платформе MEB комфорт 500 кбит"),
        Bus("vag_drive", MAKES[0], "Силовая (Antrieb)", 500, 6 to 14, Tap.OBD,
            where = "На разъёме OBD через шлюз (Gateway): там обычно только диагностика, поток силовой шины берут за шлюзом",
            note = "У VW ID.4 силовая шина 2 Мбит (CAN FD)"),
        Bus("bmw_kcan", MAKES[1], "K-CAN (кузов, E-серия)", 100, null, Tap.HEAD_UNIT, Phys.FAULT_TOLERANT,
            where = "Разъём магнитолы или блока климата", note = "У E9x K-CAN на OBD не выведена"),
        Bus("bmw_ptcan", MAKES[1], "PT-CAN (двигатель)", 500, null, Tap.HEAD_UNIT,
            where = "За шлюзом, у блоков двигателя и коробки", note = "На разъёме OBD у BMW диагностика через шлюз"),
        Bus("mb_canb", MAKES[2], "CAN-B (салон)", 83, null, Tap.HEAD_UNIT,
            where = "Разъём магнитолы или приборной панели", note = "Скорость около 83,3 кбит: не у всех адаптеров она есть"),
        Bus("mb_canc", MAKES[2], "CAN-C (двигатель)", 500, 6 to 14, Tap.OBD),
        Bus("gm_sw", MAKES[3], "GMLAN однопроводная (кузов)", 33, null, Tap.HEAD_UNIT, Phys.SINGLE_WIRE,
            where = "На OBD это контакт 1 (один провод, масса на 4/5), или провод GMLAN у магнитолы"),
        Bus("gm_hs", MAKES[3], "HS-GMLAN (двигатель)", 500, 6 to 14, Tap.OBD),
        Bus("ford_hs", MAKES[4], "HS-CAN (двигатель, АКПП, ABS)", 500, 6 to 14, Tap.OBD),
        Bus("ford_ms", MAKES[4], "MS-CAN (кузов, климат, магнитола)", 125, 3 to 11, Tap.OBD,
            where = "Контакты 3 (CAN-H) и 11 (CAN-L) разъёма OBD", note = "Скорость до 125 кбит"),
        Bus("fca_c", MAKES[5], "C-CAN (двигатель, кузов)", 500, 6 to 14, Tap.HEAD_UNIT,
            where = "С 2018 г. на OBD стоит шлюз SGW: подключайтесь за ним (разъём магнитолы или перемычка в обход SGW)"),
        Bus("subaru_hs", MAKES[6], "Высокоскоростная (2009+)", 500, 6 to 14, Tap.OBD,
            note = "Обороты, педаль, скорость, температуры, передача слышны прямо на OBD"),
        Bus("other_hs", MAKES[7], "Обычная шина двигателя (HS-CAN)", 500, 6 to 14, Tap.OBD,
            note = "Скорость у большинства машин 500 кбит. Не уверены: «Подобрать скорость» найдёт сама"),
        Bus("other_comfort", MAKES[7], "Шина салона за магнитолой", 125, null, Tap.HEAD_UNIT,
            note = "Скорость бывает 33,3–500 кбит: подберите автоматически"),
    )

    fun bus(id: String?): Bus? = BUSES.firstOrNull { it.id == id }
    fun busesOf(make: String): List<Bus> = BUSES.filter { it.make == make }

    /** The makes whose diagnostic socket is behind a gateway (only diagnostics there, no broadcast traffic), and why. */
    fun gatewayNote(make: String): String? = when (make) {
        MAKES[0] -> "У VW, Audi, Skoda и Seat примерно с 2004 г. разъём OBD подключён через шлюз (Gateway): на нём тишина, пока не спросишь. Поток шин берут за шлюзом"
        MAKES[1] -> "У BMW разъём OBD за шлюзом, а K-CAN (E9x) на него не выведена: подключайтесь у магнитолы или блока климата"
        MAKES[3] -> "У GM с архитектурой Global B (примерно 2019–2022) разъём закрыт шлюзом SDGM: слушать надо за ним"
        MAKES[5] -> "У Jeep, Chrysler, Dodge, Fiat с 2018 г. на OBD стоит шлюз безопасности SGW: на разъёме только диагностика"
        else -> null
    }

    /** Sports ECUs and their usual dash-stream speeds (set in their software: check yours). */
    class Ecu(val name: String, val kbit: Int?, val note: String)

    val ECUS = listOf(
        Ecu("Link G4+/G4X", null, "Скорость и поток задаются в PCLink (ECU Controls, CAN Setup). Dash2Pro и Generic Dash приложение находит само"),
        Ecu("Haltech", 1000, "1 Мбит, 11-битные ID; обороты и давление во впуске на 0x360"),
        Ecu("ECUMaster EMU Black", null, "500 кбит или 1 Мбит, базовый ID 0x600"),
        Ecu("AEM X-Series (лямбда)", null, "Базовый ID 0x180, у каждого контроллера свой; своего терминатора у него нет"),
        Ecu("Другой", null, "Скорость в настройках CAN самого ЭБУ; не знаете: «Подобрать скорость»"),
    )

    /** What the ohms between CAN-H and CAN-L (ignition off, the adapter's own terminator off) say. */
    enum class Termination(val ok: Boolean, val text: String) {
        SHORT(false, "Почти ноль: H и L замкнуты между собой или с массой, или зажигание включено. Выключите зажигание и проверьте провода"),
        EXTRA(false, "Около 40 Ом: на шине лишний терминатор (три по 120). Уберите свой: у адаптера выключите, у датчика не ставьте"),
        OK(true, "Около 60 Ом: два терминатора по 120 на концах шины, как надо. Свой не добавляйте"),
        ODD(false, "Между 60 и 120: плохой контакт щупов или повреждённый терминатор. Перемерьте"),
        MISSING(false, "Около 120 Ом: один терминатор есть, второго нет. На своей шине поставьте 120 Ом на втором конце (у адаптера включите свой)"),
        NONE(false, "Больше 150 Ом: терминаторов нет или щупы не на тех проводах. На своей шине нужно 120 Ом на обоих концах"),
    }

    fun termination(ohm: Float): Termination = when {
        ohm < 20f -> Termination.SHORT
        ohm < 50f -> Termination.EXTRA
        ohm <= 75f -> Termination.OK
        ohm < 100f -> Termination.ODD
        ohm <= 150f -> Termination.MISSING
        else -> Termination.NONE
    }

    /**
     * The adapter must only listen on a car's bus (it would ACK and, at a wrong speed, send error frames into the
     * car's units). On a bus of two (the adapter and one sensor) somebody has to ACK, or the sensor goes error-passive:
     * there it works normally.
     */
    fun listenOnly(s: Scenario): Boolean = s == Scenario.CAR
}
