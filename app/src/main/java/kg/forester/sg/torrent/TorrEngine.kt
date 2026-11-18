package kg.forester.sg.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lifecycle of the bundled TorrServer MatriX. The binary lives in the APK as
 * `jniLibs/armeabi-v7a/libtorrserver.so`: that lands in `nativeLibraryDir`, the only place on Android 10+ where exec is
 * allowed. The engine starts on first call, applies the chosen settings (disk cache, since head-unit RAM is tight) and a
 * watchdog respawns it if it dies. Head units are both 32- and 64-bit ARM; Android runs the 32-bit binary on arm64 too.
 */
class TorrEngine(context: Context, val port: Int = TorrClient.DEFAULT_PORT) {
    private val appContext = context.applicationContext
    val client = TorrClient("http://127.0.0.1:$port")

    private val binary = File(appContext.applicationInfo.nativeLibraryDir, "libtorrserver.so")
    private val dataDir = File(appContext.filesDir, "torrserver").apply { mkdirs() }
    val cacheDir: File = File(dataDir, "cache").apply { mkdirs() }
    private val logFile = File(dataDir, "ts.log")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var settingsApplied = false

    /** Call once from the Application: later calls are no-ops. The watchdog keeps the server up. */
    fun start() {
        scope.launch {
            ensureRunning()
            watchdog()
        }
    }

    /** Bring the server up if it is not answering yet. Returns true when the engine ends up alive. */
    suspend fun ensureRunning(): Boolean = lock.withLock {
        if (client.isUp()) {
            applySettingsOnce()
            return true
        }
        if (!binary.exists()) {
            Log.e(TAG, "TorrServer binary missing at ${binary.absolutePath}")
            return false
        }
        // what a run before left (the app killed while a film played): a fresh engine starts with an empty cache
        clearCache()
        spawn()
        val up = awaitUp(timeoutMs = 20_000)
        if (up) applySettingsOnce() else Log.e(TAG, "TorrServer did not come up")
        up
    }

    /** Wipe the on-disk cache. Called from the Настройки screen; safe to run while the server is up (it recreates). */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        cacheDir.mkdirs()
    }

    private fun spawn() {
        runCatching { process?.destroy() }
        process = null
        settingsApplied = false
        try {
            val pb = ProcessBuilder(
                binary.absolutePath,
                "--port", port.toString(),
                "--path", dataDir.absolutePath,
                "--logpath", logFile.absolutePath,
                "--ip", "127.0.0.1",
            )
            pb.redirectErrorStream(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                pb.redirectOutput(File(dataDir, "ts.out"))
            }
            pb.directory(dataDir)
            process = pb.start()
            Log.i(TAG, "TorrServer started from ${binary.absolutePath} on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TorrServer", e)
        }
    }

    private suspend fun awaitUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.isUp()) return true
            delay(400)
        }
        return false
    }

    private fun applySettingsOnce() {
        if (settingsApplied) return
        val ok = client.applySettings(settings.toJson(cacheDir.absolutePath))
        if (ok) {
            settingsApplied = true
            Log.i(TAG, "TorrServer settings applied (cache=${settings.cacheBytes} UseDisk=${settings.useDisk})")
        }
    }

    private suspend fun watchdog() {
        while (true) {
            delay(WATCHDOG_EVERY_MS)
            if (!client.isUp()) {
                Log.w(TAG, "TorrServer down — restarting")
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        if (!client.isUp()) {
                            spawn()
                            if (awaitUp(15_000)) applySettingsOnce()
                        }
                    }
                }
            }
        }
    }

    /** Settings live in the engine so TorrentSettingsUi can update them before the first stream is opened. */
    @Volatile var settings: Settings = Settings.DEFAULT
        set(value) {
            field = value
            settingsApplied = false
        }

    data class Settings(
        val cacheBytes: Long,
        val readerReadAhead: Int,
        val preloadCache: Int,
        val useDisk: Boolean,
        val removeCacheOnDrop: Boolean,
        val connectionsLimit: Int,
    ) {
        fun toJson(cachePath: String): String = """
            {
              "CacheSize": $cacheBytes,
              "ReaderReadAHead": $readerReadAhead,
              "PreloadCache": $preloadCache,
              "UseDisk": $useDisk,
              "TorrentsSavePath": "${cachePath.replace("\\", "\\\\").replace("\"", "\\\"")}",
              "RemoveCacheOnDrop": $removeCacheOnDrop,
              "ConnectionsLimit": $connectionsLimit,
              "EnableIPv6": false
            }
        """.trimIndent()

        companion object {
            /**
             * Default: 128 MB on-disk cache. Head units run SSM, GPS, media, nav at once, so the TV plan's 384 MB (that
             * TCL had 668 MB free) would starve them. 128 MB streams even 4K without pauses (plan: ~0.4-1.4 MB/s), and
             * the user can raise it in Настройки on units with more RAM.
             */
            val DEFAULT = Settings(
                cacheBytes = 128L * 1024 * 1024,
                readerReadAhead = 95,
                preloadCache = 40,
                useDisk = true,
                removeCacheOnDrop = true,
                connectionsLimit = 40,
            )

            /** Rungs the Настройки slider snaps to. 384 MB is the TV-measured upper bound (plan says more is wasted). */
            val CACHE_RUNGS_MB = listOf(64, 128, 192, 256, 384)
        }
    }

    companion object {
        private const val TAG = "TorrEngine"
        private const val WATCHDOG_EVERY_MS = 15_000L
    }
}
