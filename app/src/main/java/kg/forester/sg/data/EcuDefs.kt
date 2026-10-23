package kg.forester.sg.data

import android.content.Context
import android.net.Uri
import kg.forester.sg.ssm.RomRaiderXml
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The driver's own RomRaider logger definitions (logger.xml or a piece of one), for ECUs the bundled base doesn't know
 * or describes wrongly: old JECS units, 16-bit ECUs, a ROM without the "Direct" manifold pressure. Loaded ones are kept
 * as files in files/defs (they go into the backup with it); besides, every .xml put by a file manager into the open
 * folder Android/media/kg.forester.sg/defs ([folder]) is read as it is, in the order of the names, before the loaded
 * ones. Listed in the settings in the order they apply: a later file wins.
 * RomRaider lays them over its base. A parameter under RomRaider's id (E51, P90…) is picked by the engine guard by
 * itself; one under an id of its own gets its role here ([roles]).
 */
object EcuDefs {
    /** [inFolder]: a file of the open folder, [file] its name there. */
    class Entry(val file: String, val name: String, val inFolder: Boolean = false)

    /**
     * One file as read: its parameters, or why it could not be read. [stats] is filled in by RomRaider's merge. An
     * ECUFlash ROM definition gives [rom] instead: the axes of «Обучение» for its ROM.
     */
    class Loaded(
        val entry: Entry, val defs: RomRaiderXml.Defs?, val error: String?, val stats: RomRaiderXml.Stats? = null,
        val rom: kg.forester.sg.ssm.EcuFlashDefs.Rom? = null,
    )

    private const val DIR = "defs"
    private const val MAX_BYTES = 8 shl 20

    /** Bumped when the files change: RomRaider reads them again. */
    @Volatile var stamp = 0
        private set

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    /**
     * The open folder for definitions, Android/media/kg.forester.sg/defs: the app needs no permission for it and file
     * managers reach it on every Android (Android/data is closed to them since 11). Made with a note on what goes in;
     * null where the head unit has no shared storage.
     */
    fun folder(ctx: Context): File? = runCatching {
        @Suppress("DEPRECATION")
        val media = ctx.externalMediaDirs.firstOrNull() ?: return null
        File(media, DIR).apply {
            mkdirs()
            val note = File(this, "README.txt")
            if (!note.exists() || note.readText() != README) note.writeText(README)
        }.takeIf { it.isDirectory }
    }.getOrNull()

    private const val README = "Сюда можно класть свои определения RomRaider для CarPulse: logger.xml целиком или кусок с\n" +
        "элементами parameter, ecuparam, switch (адреса и формулы для ЭБУ, которых нет в базе или где она ошибается).\n\n" +
        "В base/carpulse_base.xml лежит вся база приложения в том же формате: копируйте оттуда то, что надо поправить\n" +
        "(сама она не читается и обновляется с новой версией приложения).\n\n" +
        "Читаются все .xml этой папки (без подпапок), по порядку имён; параметр с тем же id, что в базе (P2, E51…), заменяет её,\n" +
        "файл ниже по алфавиту главнее. Параметр без ecubyteindex/ecubit опрашивается, даже если ЭБУ при подключении\n" +
        "сказал, что его нет. Новый или изменённый файл подхватывается сам за несколько секунд; что вышло из каждого,\n" +
        "видно в Настройки → Авто и адаптер → Свои определения RomRaider.\n"

    /**
     * The open folder made, with the bundled base in base/carpulse_base.xml as a RomRaider logger.xml to copy from
     * (not read: the base is the base already), written again when a new version of the app brings another base.
     * Writes a couple of megabytes: not on the main thread.
     */
    fun prepareFolder(ctx: Context) {
        val dir = folder(ctx) ?: return
        runCatching {
            val json = ctx.assets.open("romraider_ssm.json").bufferedReader().readText()
            val out = File(dir, "base").apply { mkdirs() }
            val mark = File(out, ".base_stamp")
            val stamp = json.hashCode().toString()
            val xml = File(out, "carpulse_base.xml")
            if (xml.isFile && mark.isFile && mark.readText() == stamp) return
            xml.writeText(RomRaiderXml.exportBase(json))
            mark.writeText(stamp)
        }.onFailure { kg.forester.sg.ssm.SsmTrace.log("defs folder: base not written: $it") }
    }

    private fun folderFiles(ctx: Context): List<File> =
        folder(ctx)?.listFiles { f -> f.isFile && f.name.endsWith(".xml", ignoreCase = true) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** The loaded files, then those of the open folder. */
    fun list(): List<Entry> = runCatching {
        val a = JSONArray(Repo.settings.ecuDefs)
        List(a.length()) { a.getJSONObject(it).let { o -> Entry(o.getString("file"), o.optString("name")) } }
    }.getOrDefault(emptyList())

    /** Every file that applies, in order: the open folder's first, then the loaded ones (a later file wins). */
    fun all(ctx: Context): List<Entry> = folderFiles(ctx).map { Entry(it.name, it.name, inFolder = true) } + list()

    @Volatile private var folderSig = 0L
    @Volatile private var folderChecked = 0L

    /**
     * [stamp] with the open folder's files in it (their names, sizes and times), looked at no more than every few
     * seconds: a file put there or changed makes RomRaider read them all again, and the settings show it.
     */
    fun stamp(ctx: Context): Int {
        val now = System.currentTimeMillis()
        if (now - folderChecked > 3000) {
            folderChecked = now
            val sig = folderFiles(ctx).fold(17L) { h, f -> (h * 31 + f.name.hashCode()) * 31 + f.length() * 7 + f.lastModified() }
            if (sig != folderSig) { folderSig = sig; Repo.defsVersion.value++ }
        }
        return stamp * 31 + folderSig.hashCode()
    }

    private fun save(l: List<Entry>) {
        Repo.settings.ecuDefs = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("file", it.file).put("name", it.name)) } }.toString()
        stamp++
        Repo.defsVersion.value++
    }

    /** Every file that applies read and parsed (a missing or broken one with its error). */
    fun read(ctx: Context): List<Loaded> = all(ctx).map { e ->
        try {
            val f = if (e.inFolder) File(folder(ctx), e.file) else File(dir(ctx), e.file)
            if (!f.isFile) Loaded(e, null, "файл пропал") else {
                val text = RomRaiderXml.text(f.readBytes())
                if (kg.forester.sg.ssm.EcuFlashDefs.looksLike(text)) kg.forester.sg.ssm.EcuFlashDefs.parse(text)
                    .let { r -> Loaded(e, null, if (r == null) "определение ECUFlash без ECU ID или без осей обучения" else null, rom = r) }
                else Loaded(e, RomRaiderXml.parse(text), null)
            }
        } catch (x: RomRaiderXml.BadFile) {
            Loaded(e, null, x.message)
        } catch (x: Exception) {
            Loaded(e, null, x.message ?: x.toString())
        }
    }

    /**
     * Reads [uri], checks it is a definition with SSM parameters and keeps a copy; a file of the same name replaces
     * the old one in its place. Null when it went in, otherwise what is wrong with it.
     */
    fun import(ctx: Context, uri: Uri, name: String): String? {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= MAX_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            } ?: return "Не удалось открыть файл"
        } catch (x: Exception) {
            return "Не удалось прочитать файл: ${x.message}"
        }
        if (bytes.size > MAX_BYTES) return "Файл больше 8 МБ: это не определение логгера"
        val text = RomRaiderXml.text(bytes)
        // an ECUFlash ROM definition: the axes of «Обучение» for its ROM
        if (kg.forester.sg.ssm.EcuFlashDefs.looksLike(text)) {
            if (kg.forester.sg.ssm.EcuFlashDefs.parse(text) == null) return "Файл ECUFlash не подходит: нет ECU ID или таблиц осей обучения " +
                "(A/F Learning #1 Airflow Ranges, Fine Correction Rows/Columns)"
            return keep(ctx, bytes, name)
        }
        val defs = try {
            RomRaiderXml.parse(bytes)
        } catch (x: RomRaiderXml.BadFile) {
            return "Файл не подходит: ${x.message}"
        } catch (x: Exception) {
            return "Файл не разобрался: ${x.message}"
        }
        if (defs.params.none { it.expr != null } && defs.ext.none { it.param.expr != null })
            return "Файл не подходит: ни одной формулы, которую приложение понимает"
        return keep(ctx, bytes, name)
    }

    /** A copy of [bytes] kept under [name]; a file of the same name replaces the old one in its place. */
    private fun keep(ctx: Context, bytes: ByteArray, name: String): String? {
        val d = dir(ctx).apply { mkdirs() }
        val old = list()
        val same = old.firstOrNull { it.name == name }
        val file = same?.file ?: "def${System.currentTimeMillis()}.xml"
        File(d, file).writeBytes(bytes)
        save(if (same != null) old else old + Entry(file, name))
        return null
    }

    @Volatile private var axesCache: Pair<Int, Map<String, kg.forester.sg.ssm.EcuFlashDefs.Rom>>? = null

    /** The ROMs the driver's ECUFlash files give the axes of «Обучение» for, by ECU id (a later file wins). */
    fun romAxes(ctx: Context): Map<String, kg.forester.sg.ssm.EcuFlashDefs.Rom> {
        val st = stamp(ctx)
        axesCache?.takeIf { it.first == st }?.let { return it.second }
        val m = read(ctx).mapNotNull { it.rom }.associateBy { it.ecuId }
        axesCache = st to m
        return m
    }

    fun remove(ctx: Context, e: Entry) {
        if (e.inFolder) { folder(ctx)?.let { File(it, e.file).delete() }; folderChecked = 0L; stamp++; Repo.defsVersion.value++; return }
        File(dir(ctx), e.file).delete()
        save(list().filter { it.file != e.file })
    }

    /** Role → parameter id, for the engine guard (the driver's own addresses come before these). */
    fun roles(): Map<String, String> = runCatching {
        val o = JSONObject(Repo.settings.ecuDefRoles)
        o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    fun setRole(role: String, id: String?) {
        val o = runCatching { JSONObject(Repo.settings.ecuDefRoles) }.getOrDefault(JSONObject())
        if (id == null) o.remove(role) else o.put(role, id)
        Repo.settings.ecuDefRoles = o.toString()
        Repo.defsVersion.value++
    }
}
