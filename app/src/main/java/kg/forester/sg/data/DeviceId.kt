package kg.forester.sg.data

import android.content.Context
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The id a tester tells the owner so that the owner finds the logs: 8 characters, the same after an update and after
 * a delete and install again, different on every unit, and nothing that says whose it is.
 *
 * The order: the stored value; else a hash of ANDROID_ID, the hardware serial when it can be read, and the package (neither
 * goes anywhere itself; on Android 8+ ANDROID_ID outlives a reinstall with the same signature and changes only when the unit
 * is reset, the serial never does); else, when the system gives nothing usable, a random one, also kept in
 * Documents/CarPulse/.device-id where the system allows it.
 * Cloned firmware can give two units one ANDROID_ID and, on cheap units, one serial too, so an id can still be shared: the
 * [key] tells installs apart under one id, the log receiver shows that as a second tag in the file names.
 * The stored values are left out of the backup (res/xml/backup_rules.xml, data_extraction_rules.xml): a restore must not
 * carry them to another unit.
 */
object DeviceId {
    /** 32 symbols, 5 bits each: no 0, O, 1 and I, which are read wrong aloud and from a screen. */
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val LENGTH = 8

    /** Where an id is kept. */
    interface Slot {
        fun get(): String?
        fun put(id: String)
    }

    /** The value every unit of a known old build reported, and the ones that mean "nothing". */
    private val BAD = setOf("9774d56d682e549c", "0000000000000000", "unknown", "null", "android_id", "0123456789abcdef")

    fun isBad(androidId: String?): Boolean {
        val s = androidId?.trim()?.lowercase().orEmpty()
        return s.length < 8 || s in BAD || s.toSet().size == 1
    }

    fun isValid(id: String?) = id != null && id.length == LENGTH && id.all { it in ALPHABET }

    /** 5 bytes (40 bits) to 8 symbols. */
    fun fromBytes(b: ByteArray): String {
        var acc = 0L
        for (i in 0 until 5) acc = (acc shl 8) or (b[i].toLong() and 0xFF)
        return buildString { for (i in 7 downTo 0) append(ALPHABET[((acc shr (i * 5)) and 31).toInt()]) }
    }

    /** From the system's id, the hardware serial and the package, each only if good; null when neither id is. */
    fun derive(androidId: String?, pkg: String, serial: String? = null): String? {
        val parts = listOf(androidId, serial).filterNot { isBad(it) }.map { it!!.trim().lowercase() }
        if (parts.isEmpty()) return null
        return fromBytes(MessageDigest.getInstance("SHA-256").digest((parts + pkg).joinToString("|").toByteArray()))
    }

    /** ro.serialno, by reflection: the class is hidden, and later Android versions answer nothing to an app. Null when unreadable. */
    fun hardwareSerial(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.serialno") as? String
    }.getOrNull()?.takeUnless { isBad(it) }

    fun random(): String = fromBytes(ByteArray(5).also { SecureRandom().nextBytes(it) })

    /** The id by the order above; writes what it settled on to [prefs] (and [shared] when it had to draw a random one). */
    fun resolve(prefs: Slot, shared: Slot, androidId: String?, pkg: String, random: () -> String = ::random, serial: String? = null): String {
        prefs.get()?.takeIf { isValid(it) }?.let { return it }
        derive(androidId, pkg, serial)?.let { prefs.put(it); return it }
        val id = runCatching { shared.get() }.getOrNull()?.takeIf { isValid(it) } ?: random().also { runCatching { shared.put(it) } }
        prefs.put(id)
        return id
    }

    private val KEY_RE = Regex("[0-9a-f]{32}")

    /** The per-install secret from [slot], made once: 128 random bits as hex. A damaged one is replaced. */
    fun key(slot: Slot): String {
        slot.get()?.takeIf { KEY_RE.matches(it) }?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }.also { slot.put(it) }
    }

    @Volatile private var cached: String? = null
    @Volatile private var cachedKey: String? = null

    /** This install's secret for the log receiver. Goes only to the receiver, over https; never into a log or a report. */
    fun key(ctx: Context): String {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: run {
                val p = ctx.applicationContext.getSharedPreferences("device_id", Context.MODE_PRIVATE)
                key(object : Slot {
                    override fun get() = p.getString("key", null)
                    // commit: a key lost to a killed process would look like a clone at the next send
                    override fun put(id: String) { p.edit().putString("key", id).commit() }
                })
            }.also { cachedKey = it }
        }
    }

    /** The id of this unit; made at the first launch, whether the log upload is on or not. */
    fun get(ctx: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val app = ctx.applicationContext
                // its own file: backup_rules.xml leaves exactly this one out
                val p = app.getSharedPreferences("device_id", Context.MODE_PRIVATE)
                val prefs = object : Slot {
                    override fun get() = p.getString("id", null)
                    override fun put(id: String) { p.edit().putString("id", id).apply() }
                }
                val aid = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
                resolve(prefs, SharedFile, aid, app.packageName, serial = hardwareSerial())
            }.also { cached = it }
        }
    }

    /** Documents/CarPulse/.device-id: best effort, where the system lets the app write there. */
    @Suppress("DEPRECATION")
    private object SharedFile : Slot {
        private fun file() = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CarPulse"), ".device-id")
        override fun get(): String? = file().takeIf { it.isFile }?.readText()?.trim()
        override fun put(id: String) { file().apply { parentFile?.mkdirs() }.writeText(id) }
    }
}
