package kg.forester.sg.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.composables.icons.lucide.*
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kg.forester.sg.data.LinkState
import kg.forester.sg.data.Repo
import kg.forester.sg.service.EcuLink
import kg.forester.sg.ssm.EcuInit
import kg.forester.sg.ssm.SsmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One SSM2 trouble code: a bit in the "current" (tmp) and "memorized" (mem) status bytes. */
data class DtcDef(val code: String, val desc: String, val tmpAddr: Int, val memAddr: Int, val bit: Int, val descRu: String = "")

data class DtcHit(val def: DtcDef, val current: Boolean, val memorized: Boolean)

object DtcRepo {
    private val cache = HashMap<String, List<DtcDef>>()

    /** Reads a big JSON the first time: off the main thread. */
    fun load(ctx: Context, asset: String): List<DtcDef> = synchronized(cache) { cache.getOrPut(asset) {
        val text = runCatching { ctx.assets.open(asset).bufferedReader().readText() }.getOrNull() ?: "[]"
        val arr = JSONArray(text)
        val out = ArrayList<DtcDef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += DtcDef(
                code = o.getString("code"),
                desc = o.optString("desc"),
                tmpAddr = o.getString("tmp").removePrefix("0x").toInt(16),
                memAddr = o.getString("mem").removePrefix("0x").toInt(16),
                bit = o.getInt("bit"),
                descRu = o.optString("ru").also { kg.forester.sg.data.I18n.learn(it, o.optString("desc")) },
            )
        }
        out
    } }

    @Volatile private var hints: Map<String, String>? = null

    /**
     * What to check first for [code] (assets/dtc_hints.json): Subaru specifics from the service manuals, TSBs and
     * forums, otherwise plain OBD-II logic. 2-digit codes mean different things on the engine and the
     * transmission unit, so the key is the unit too: "ECU" for P-codes, "ECU2" / "TCU" for the 2-digit ones.
     */
    fun hint(ctx: Context, code: String, tcu: Boolean, twoDigit: Boolean): String? {
        val m = hints ?: synchronized(this) {
            hints ?: runCatching {
                val a = org.json.JSONObject(ctx.assets.open("dtc_hints.json").bufferedReader().readText()).getJSONArray("hints")
                (0 until a.length()).associate { i -> a.getJSONObject(i).let { "${it.getString("unit")}/${it.getString("code")}" to it.getString("ru") } }
            }.getOrDefault(emptyMap()).also { hints = it }
        }
        val unit = if (!twoDigit) "ECU" else if (tcu) "TCU" else "ECU2"
        return m["$unit/$code"]
    }

    /** Every OBD-II-style code (engine and transmission share it); also the descriptions for plain OBD-II. */
    fun ecuDefs(ctx: Context) = load(ctx, "ssm_dtc.json")

    /**
     * The codes [init]'s unit keeps, picked like FreeSSM (SSMFlagbyteDefinitionsInterface::setupDiagnosticCodes),
     * the same way for the engine and the transmission: flag byte 37 bit 7 = Subaru 2-digit codes at 0x8E–0x97 /
     * 0xA4–0xAD; otherwise OBD-II codes, each block only when its flag bit says the unit has it. Reading a block
     * the unit does not have returns ordinary RAM: codes "in memory" with the check engine lamp off that never clear.
     */
    fun defs(ctx: Context, init: EcuInit): List<DtcDef> =
        if (init.supports(37, 7)) load(ctx, "ssm_dtc_2digit.json")
        else ecuDefs(ctx).filter { d -> obdBlockFlags(d.tmpAddr).any { (b, bit) -> init.supports(b, bit) } }

    /** Flag bits (in [EcuInit.supports] numbering) that enable the OBD-II code block holding [tmp]. */
    private fun obdBlockFlags(tmp: Int): List<Pair<Int, Int>> = when (tmp) {
        in 0x8E..0xAD -> listOf(37 to 4, 37 to 6)
        in 0xF0..0xF3 -> listOf(36 to 0)
        in 0x123..0x12A -> listOf(47 to 7)
        in 0x150..0x154 -> listOf(47 to 6)
        in 0x160..0x164 -> listOf(47 to 5)
        in 0x174..0x17A -> listOf(47 to 4)
        in 0x1C1..0x1C6, in 0x20A..0x20D -> listOf(58 to 6)
        in 0x263..0x267 -> listOf(58 to 5)
        // blocks only RomRaider lists (0x2A6–0x2A9, 0x2B6): newest CAN ECUs, assumed to come with the 0x263 block
        else -> listOf(58 to 5)
    }

    /**
     * Reads every status byte the table refers to (in chunks) and returns the set bits. A set bit no definition
     * explains is shown as "???" with its address, like FreeSSM does: better a question than a silent miss.
     */
    suspend fun read(defs: List<DtcDef>, dest: Int): List<DtcHit>? {
        if (defs.isEmpty()) return emptyList()
        val addrs = (defs.map { it.tmpAddr } + defs.map { it.memAddr }).distinct().sorted()
        val values = HashMap<Int, Int>()
        for (chunk in addrs.chunked(30)) {
            val raw = EcuLink.use { it.read(chunk.toIntArray(), dest) } ?: return null
            chunk.forEachIndexed { i, a -> values[a] = raw[i].toInt() and 0xFF }
        }
        // 0xFF = address not implemented by this unit (returns junk), ignore it
        fun bits(a: Int) = values[a]?.takeIf { it != 0xFF } ?: 0
        val known = defs.mapNotNull { def ->
            val cur = bits(def.tmpAddr) shr def.bit and 1 == 1
            val mem = bits(def.memAddr) shr def.bit and 1 == 1
            if (cur || mem) DtcHit(def, cur, mem) else null
        }
        val defined = defs.flatMap { listOf(it.tmpAddr to it.bit, it.memAddr to it.bit) }.toSet()
        val pairs = defs.map { it.tmpAddr to it.memAddr }.distinct()
        val unknown = pairs.filter { it.first != 0xA2 }.flatMap { (tmp, mem) -> // 0xA2/0xC2: bits FreeSSM ignores
            (0..7).mapNotNull { bit ->
                val cur = bits(tmp) shr bit and 1 == 1 && (tmp to bit) !in defined
                val stored = bits(mem) shr bit and 1 == 1 && (mem to bit) !in defined
                if (!cur && !stored) null
                else DtcHit(DtcDef("???", "Unknown code: 0x%02X/0x%02X bit %d".format(tmp, mem, bit), tmp, mem, bit,
                    "Код без описания в таблице: 0x%02X/0x%02X, бит %d".format(tmp, mem, bit)), cur, stored)
            }
        }
        return known + unknown
    }

    @Volatile private var generic: kg.forester.sg.ssm.GenericDtc? = null

    /** The standard codes' descriptions (assets/obd_dtc_ru.json), read once. */
    fun generic(ctx: Context): kg.forester.sg.ssm.GenericDtc = generic ?: synchronized(this) {
        generic ?: kg.forester.sg.ssm.GenericDtc(
            runCatching { ctx.assets.open("obd_dtc_ru.json").bufferedReader().use { it.readText() } }.getOrDefault("{}"),
        ).also { generic = it }
    }

    /** The make by the VIN the ECU gave (null when there is none or the tables don't know it). */
    fun make(ctx: Context): kg.forester.sg.data.Brand? = Repo.vin.value?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { kg.forester.sg.data.Brands.get(ctx).makeFromVin(it) }.getOrNull() }

    /**
     * A description for a standard OBD-II code: Subaru's table first for a Subaru, then the standard (SAE J2012) one;
     * a make's own code (P1xxx…) says whose it is instead of a guess. Subaru's table also fills a standard code the
     * standard table lacks: the standard codes mean the same on every make.
     */
    fun obdDef(ctx: Context, code: String, make: kg.forester.sg.data.Brand?): DtcDef {
        val ssm = ecuDefs(ctx).associate { it.code to (it.desc to it.descRu) }
        val subaru = make?.slug == kg.forester.sg.data.Brands.SUBARU
        val t = generic(ctx).describe(code, if (subaru) ssm else null, make?.name)
        if (!t.known && !kg.forester.sg.ssm.GenericDtc.isManufacturer(code)) ssm[code]?.let { (en, ru) -> return DtcDef(code, en, 0, 0, 0, ru) }
        kg.forester.sg.data.I18n.learn(t.ru, t.en)
        return DtcDef(code, t.en, 0, 0, 0, t.ru)
    }

    /**
     * Standard OBD-II codes (when the car is read without SSM): stored ones count as current and memorized,
     * pending ones as current only.
     */
    suspend fun readObd(ctx: Context): List<DtcHit>? {
        val (stored, pending) = withObd { it.readDtcs() } ?: return null
        val make = make(ctx)
        return stored.map { DtcHit(obdDef(ctx, it, make), current = true, memorized = true) } +
            (pending - stored.toSet()).map { DtcHit(obdDef(ctx, it, make), current = true, memorized = false) }
    }

    /** What else OBD-II tells about the codes: readiness, the freeze frame of the first stored code, the unit's ids. */
    data class ObdDiag(
        val readiness: kg.forester.sg.ssm.ObdPids.Readiness?,
        val freeze: kg.forester.sg.ssm.Obd2.FreezeFrame?,
        val info: kg.forester.sg.ssm.Obd2.VehicleInfo?,
    )

    suspend fun readObdDiag(): ObdDiag? {
        val r = withObd { it.readiness() }
        val f = withObd { it.freezeFrame() }
        val i = withObd { it.vehicleInfo() }
        i?.let { kg.forester.sg.ssm.Obd2.ecuLine(it.ecuName, it.calIds.firstOrNull()) }?.let { Repo.obdEcu.value = it }
        return if (r == null && f == null && i == null) null else ObdDiag(r, f, i)
    }

    /** Mode 06: slow on the K-line (a request per test), so only on a tap. */
    suspend fun readObdTests(): List<kg.forester.sg.ssm.ObdPids.TestResult>? = withObd { it.testResults() }

    suspend fun clearObd(): Boolean = withObd { it.clearDtcs() } == true

    private suspend fun <T> withObd(block: (kg.forester.sg.ssm.Obd2) -> T): T? = EcuLink.mutex.withLock {
        val o = EcuLink.obd ?: return@withLock null
        runCatching { block(o) }.getOrNull()
    }

    /**
     * Only the "current" bytes of [defs], for the check-engine icon: half of what a read by hand asks. Null with no answer.
     */
    suspend fun readCurrent(defs: List<DtcDef>, dest: Int): Int? {
        if (defs.isEmpty()) return 0
        val addrs = defs.map { it.tmpAddr }.distinct().sorted()
        val values = HashMap<Int, Int>()
        for (chunk in addrs.chunked(30)) {
            val raw = EcuLink.use { it.read(chunk.toIntArray(), dest) } ?: return null
            chunk.forEachIndexed { i, a -> values[a] = raw[i].toInt() and 0xFF }
        }
        return kg.forester.sg.service.currentCodes(defs.map { it.tmpAddr to it.bit }, values)
    }

    /**
     * SSM2 "clear memory": write 0x40 to 0x000060 (also resets learned values); the unit echoes the byte it took.
     * The codes are gone only after the ignition has been off for a few seconds (FreeSSM asks for the same).
     */
    suspend fun clear(dest: Int): Boolean = EcuLink.use { it.write(0x000060, 0x40, dest) } == 0x40
}

@Composable
fun DtcScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by Repo.link.collectAsState()
    val ecuInit by Repo.ecuInit.collectAsState()
    val tcuInit by Repo.tcuInit.collectAsState()
    val obdMode by Repo.obdMode.collectAsState()
    var unitTcu by remember { mutableStateOf(false) }
    // another make: no Subaru transmission unit to ask, no SSM ids to show
    val other by kg.forester.sg.data.CarKind.other.collectAsState()
    if ((obdMode || other) && unitTcu) unitTcu = false
    val init = if (unitTcu) tcuInit else ecuInit
    val dest = if (unitTcu) SsmClient.TCU else SsmClient.ECU
    fun tableOf() = init?.let { DtcRepo.defs(ctx, it) } ?: emptyList()
    val defs by androidx.compose.runtime.produceState(emptyList<DtcDef>(), init, unitTcu) { value = withContext(Dispatchers.IO) { tableOf() } }
    // the hints' JSON once, before a list of codes wants it line by line
    var hintsRead by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { DtcRepo.hint(ctx, "", false, false) }
        hintsRead = true
    }
    var hits by remember { mutableStateOf<List<DtcHit>?>(null) }
    var status by remember { mutableStateOf("Нажмите «Прочитать ошибки»") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf<DtcRepo.ObdDiag?>(null) }
    var tests by remember { mutableStateOf<List<kg.forester.sg.ssm.ObdPids.TestResult>?>(null) }
    var testsStatus by remember { mutableStateOf("") }

    fun readNow() {
        // a sports ECU keeps its codes to its own software: say how many it reports, nothing to read here
        if (kg.forester.sg.data.CanEcu.active) {
            val n = kg.forester.sg.data.CanEcu.values()["${kg.forester.sg.data.CanEcu.PREFIX}FAULTS"]?.toInt()
            // the code itself only when the tuner put Link's «Fault Code» into a stream and it is set up as an own value
            val code = kg.forester.sg.data.CanEcu.role("fault")?.toInt()?.takeIf { it > 0 }
            hits = emptyList()
            status = when {
                code != null -> "ЭБУ Link: код ошибки $code (что он значит, смотрите в PCLink или справке Link)" + (n?.takeIf { it > 1 }?.let { ". Всего ошибок: $it" } ?: "")
                n == null -> "ЭБУ Link: число ошибок в этом потоке не передаётся, коды читаются в PCLink"
                n == 0 -> "ЭБУ Link: ошибок нет"
                else -> "ЭБУ Link: ошибок $n. Сами коды Link в поток для дашбордов не кладёт: добавьте в свой поток параметр Fault Code " +
                    "и заведите его в «Своих значениях из CAN» с ролью «Код ошибки Link», или смотрите их в PCLink"
            }
            return
        }
        busy = true; status = "Чтение…"
        scope.launch {
            // the table may not have come yet when the button is pressed
            val defs = withContext(Dispatchers.IO) { tableOf() }
            val r = withContext(Dispatchers.IO) { if (obdMode) DtcRepo.readObd(ctx) else DtcRepo.read(defs, dest) }
            hits = r
            // no second look right after: that was another ECU+TCU read in a row on the same wire (the next
            // regular one comes within 5 min); after clearing codes it still asks
            // readiness, freeze frame and the unit's ids come along on OBD-II
            if (obdMode && r != null) diag = withContext(Dispatchers.IO) { DtcRepo.readObdDiag() }
            status = when {
                r == null -> "Не удалось прочитать (нет связи с ЭБУ)"
                !obdMode && init == null -> if (unitTcu) "Блок АКПП не ответил" else "Нет связи с ЭБУ"
                !obdMode && defs.isEmpty() -> "Блок не сообщает, где хранит коды ошибок"
                r.isEmpty() -> "Ошибок нет"
                else -> "Найдено: ${r.size}"
            }
            busy = false
        }
    }

    ScrollWhenLow(500.dp) {
    Row(Modifier.fillMaxSize()) {
        Panel(Modifier.width(if (LocalCar.current.small) 390.dp else 320.dp).fillMaxHeight()) {
            var manual by remember { mutableStateOf(false) }
            PanelTitle(Lucide.TriangleAlert, "Коды ошибок") {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(C.CardHi).clickable { manual = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) { T("Без сканера", 11.sp, C.Sub) }
            }
            if (manual) InfoTexts.byKey["dtc.manual"]?.let { InfoDialog("Коды без сканера", it) { manual = false } }
            Spacer(Modifier.height(10.dp))
            if (!obdMode && !other) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Двигатель", !unitTcu, { unitTcu = false; hits = null; status = "Нажмите «Прочитать ошибки»" }, modifier = Modifier.weight(1f))
                Chip(kg.forester.sg.data.I18n.pick("АКПП", "Gearbox"), unitTcu, {
                    if (!obdMode) { unitTcu = true; hits = null; status = "Нажмите «Прочитать ошибки»" }
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            T("Связь: " + if (link == LinkState.CONNECTED) "есть" else "нет", 14.sp, if (link == LinkState.CONNECTED) C.Green else C.Orange)
            if (!other || init != null) {
                T("SSM ID: ${init?.ssmId ?: "—"}", 13.sp, C.Sub)
                T("ROM ID: ${init?.romId ?: "—"}", 13.sp, C.Sub)
            }
            init?.let { i -> kg.forester.sg.ssm.EcuIds.car(ctx, i.romId)?.let { T(it, 13.sp, C.Sub) } }
            val vin by Repo.vin.collectAsState()
            if (!unitTcu && vin.isNotEmpty()) T("VIN: $vin", 13.sp, C.Sub)
            // an empty line of the same height until the tables have said
            val vinMake by androidx.compose.runtime.produceState<String?>(if (vin.isNotEmpty()) "" else null, vin) {
                value = withContext(Dispatchers.IO) { DtcRepo.make(ctx)?.name }
            }
            if (obdMode) vinMake?.let { T(if (it.isEmpty()) " " else "Марка по VIN: $it", 13.sp, C.Sub) }
            if (obdMode) T("Режим OBD-II: стандартные коды, АКПП недоступна", 13.sp, C.Sub)
            else if (init != null) T(
                (if (init!!.supports(37, 7)) "Коды Subaru (2-значные)" else "Коды OBD-II") + ", в таблице: ${defs.size}", 13.sp, C.Sub,
            )
            Spacer(Modifier.height(20.dp))
            ActionButton("Прочитать ошибки", !busy) { readNow() }
            Spacer(Modifier.height(10.dp))
            // a Link's codes are cleared in PCLink: the Subaru clear command means nothing to it
            ActionButton("Сбросить ошибки", !busy && !kg.forester.sg.data.CanEcu.active, danger = true) { confirmClear = true }
            Spacer(Modifier.height(16.dp))
            T(status, 14.sp)
            if (unitTcu) {
                val tcu by Repo.tcu.collectAsState()
                val tcuStatus by Repo.tcuStatus.collectAsState()
                if (tcuInit == null && tcuStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    T(tcuStatus, 12.sp, C.Orange)
                }
                if (tcu?.atfLamp == true || tcu?.diagLamp == true) {
                    Spacer(Modifier.height(8.dp))
                    T("Лампа AT OIL TEMP горит. Горит постоянно — перегрев ATF. Мигает после пуска (16 вспышек) — блок " +
                        "АКПП записал неисправность: прочитайте ошибки здесь.", 12.sp, C.Orange)
                }
                if (tcu?.fwdFuse == true) {
                    Spacer(Modifier.height(8.dp))
                    T("В держатель FWD вставлен предохранитель: блок АКПП отключил задний мост, на приборке горит лампа FWD. " +
                        "Это режим, а не неисправность, кода ошибки в памяти не будет.", 12.sp, C.Orange)
                }
            }
            Spacer(Modifier.weight(1f))
            T("Сброс стирает и адаптации ЭБУ (топливные коррекции, обучение детонации и холостого хода). После сброса выключите зажигание на 5–10 с.", 11.sp, C.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            // no codes (not read yet, or none found): a plain message in the middle instead of a table header over
            // an empty panel
            val none = hits.isNullOrEmpty()
            if (none && !obdMode) {
                DtcEmpty(read = hits != null, busy = busy, status = status, modifier = Modifier.fillMaxSize())
                return@Panel
            }
            if (!none) HeaderRow(listOf("Код", "Описание", "Текущая", "В памяти"))
            LazyColumn {
                if (none) item { DtcEmpty(read = hits != null, busy = busy, status = status, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
                items(hits ?: emptyList()) { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(h.def.code, 15.sp, C.Orange, FontWeight.Bold, Modifier.weight(0.6f))
                        Column(Modifier.weight(2.4f)) {
                            T(h.def.descRu.ifBlank { h.def.desc }, 14.sp)
                            if (h.def.descRu.isNotBlank() && !kg.forester.sg.data.I18n.english) T(h.def.desc, 11.sp, C.Sub)
                            val twoDigit = !h.def.code.startsWith("P") && !h.def.code.startsWith("U") && !h.def.code.startsWith("C") && !h.def.code.startsWith("B")
                            if (hintsRead) DtcRepo.hint(ctx, h.def.code, unitTcu, twoDigit)?.let { T("Что проверить: $it", 12.sp, C.Violet) }
                        }
                        T(if (h.current) "да" else "—", 14.sp, if (h.current) C.Red else C.Sub, modifier = Modifier.weight(0.5f))
                        T(if (h.memorized) "да" else "—", 14.sp, modifier = Modifier.weight(0.5f))
                    }
                }
                if (obdMode) {
                    diag?.let { d -> item { ObdDiagView(d) } }
                    item {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("Результаты бортовых тестов (режим 06)", 15.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Chip("Прочитать", false, {
                                testsStatus = "Чтение…"
                                scope.launch {
                                    val t = withContext(Dispatchers.IO) { DtcRepo.readObdTests() }
                                    tests = t
                                    testsStatus = if (t == null) "Блок не отдаёт результаты тестов" else ""
                                }
                            })
                        }
                        if (testsStatus.isNotEmpty()) T(testsStatus, 12.sp, C.Sub)
                    }
                    items(tests ?: emptyList()) { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(t.name, 12.sp, modifier = Modifier.weight(2.2f), maxLines = 2)
                            T(t.value, 12.sp, C.Text, FontWeight.SemiBold, Modifier.weight(0.9f), maxLines = 1)
                            T("${t.min} … ${t.max}", 11.sp, C.Sub, modifier = Modifier.weight(1.3f), maxLines = 1)
                            T(when (t.pass) { true -> "норма"; false -> "не прошёл"; null -> "—" }, 12.sp,
                                if (t.pass == false) C.Red else C.Green, modifier = Modifier.weight(0.7f))
                        }
                    }
                }
            }
        }
    }
    }

    if (confirmClear) {
        CarAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    if (unitTcu) "Блок АКПП сотрёт коды и, скорее всего, сбросит адаптации переключений — первое время переключения могут быть жёстче, пока коробка обучится."
                    else "ЭБУ сотрёт коды ошибок и сбросит адаптации (топливные коррекции, обучение холостого хода). Первые километры двигатель может работать чуть иначе, пока ЭБУ заново обучится."
                )
            },
            confirmButton = {
                TextButton({
                    confirmClear = false; busy = true; status = "Сброс…"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { if (obdMode) DtcRepo.clearObd() else DtcRepo.clear(dest) }
                        hits = null
                        kg.forester.sg.service.CodeWatch.ask()
                        status = if (ok) "Блок принял команду. Теперь выключите зажигание минимум на 5–10 секунд, включите снова, " +
                            "дождитесь связи и прочитайте ошибки: до этого коды могут ещё показываться." else "Блок не подтвердил сброс"
                        busy = false
                    }
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } },
        )
    }
}

/** The code table's place while it has no codes: not read yet ([read] false), reading, or read and clean. */
@Composable
private fun DtcEmpty(read: Boolean, busy: Boolean, status: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // green only for a unit that answered and has no codes; no link or a Link ECU says why in the line below
        val clean = read && !busy && status.endsWith("ошибок нет", ignoreCase = true)
        Icon(
            if (clean) Lucide.CircleCheck else Lucide.ScanSearch, null,
            tint = if (clean) C.Green else C.Sub, modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(14.dp))
        T(
            when {
                busy -> "Чтение…"
                clean -> "Ошибок нет"
                read -> "Коды не получены"
                else -> "Коды ещё не прочитаны"
            },
            20.sp, weight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        T(
            when {
                busy -> "Спрашиваем блок, это несколько секунд"
                clean -> "Блок не хранит ни одного кода ошибки"
                read || !status.startsWith("Нажмите") -> status
                else -> "Нажмите «Прочитать ошибки» слева: найденные коды появятся здесь, с описанием и подсказкой, что проверить"
            },
            15.sp, C.Sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
    }
}

/** OBD-II readiness (MIL, monitors), the freeze frame of the first stored code and what the engine unit says about itself. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ObdDiagView(d: DtcRepo.ObdDiag) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        d.readiness?.let { r ->
            T("Готовность систем самодиагностики", 15.sp, weight = FontWeight.SemiBold)
            T((if (r.mil) "«Чек» горит" else "«Чек» не горит") + ", кодов в памяти: ${r.dtcCount}" +
                if (r.notReady == 0) ", все тесты пройдены" else ", не готово: ${r.notReady}", 13.sp, if (r.mil) C.Orange else C.Sub)
            if (r.notReady > 0) T("Не готово бывает после сброса ошибок или отключения аккумулятора: тесты проходят сами за несколько " +
                "поездок разного характера (холодный пуск, трасса, холостой ход). Техосмотр с неготовыми системами могут не принять", 11.sp, C.Sub)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.monitors.forEach { m ->
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.CardHi).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        T((if (m.ready) "✓ " else "… ") + m.name, 12.sp, if (m.ready) C.Green else C.Orange)
                    }
                }
            }
        }
        d.freeze?.let { f ->
            Spacer(Modifier.height(12.dp))
            T("Стоп-кадр" + (f.dtc?.let { " (код $it)" } ?: ""), 15.sp, weight = FontWeight.SemiBold)
            if (f.dtc == null) T("ЭБУ не сохранил стоп-кадр", 12.sp, C.Sub)
            else T("Что показывали датчики в момент, когда записался код", 11.sp, C.Sub)
            f.values.forEach { (n, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    T(n, 12.sp, C.Sub, modifier = Modifier.weight(2f), maxLines = 1)
                    T(v, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                }
            }
        }
        d.info?.let { i ->
            if (i.calIds.isEmpty() && i.cvns.isEmpty() && i.ecuName == null) return@let
            Spacer(Modifier.height(12.dp))
            T("Блок управления двигателем", 15.sp, weight = FontWeight.SemiBold)
            i.ecuName?.let { T("Название: $it", 12.sp, C.Sub) }
            if (i.calIds.isNotEmpty()) T("Калибровка (CALID): ${i.calIds.joinToString(", ")}", 12.sp, C.Sub)
            if (i.cvns.isNotEmpty()) T("Контрольная сумма (CVN): ${i.cvns.joinToString(", ")}", 12.sp, C.Sub)
        }
    }
}

