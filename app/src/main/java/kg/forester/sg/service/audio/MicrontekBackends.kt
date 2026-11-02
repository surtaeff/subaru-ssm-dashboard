package kg.forester.sg.service.audio

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import kg.forester.sg.service.BtAudio
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Microntek (MTCB/MTCD/MTCE, PX5 and others) has the BT chip outside Android: its sound comes on a channel of the
 * audio processor that only the stock Bluetooth app opens ("av_channel_enter=gsm_bt", as in its activity). No session
 * at all as a rule, the track only if the unit announces it; the music itself plays all the same.
 */
class MicrontekBt(ctx: Context) : GenericBt(ctx) {
    private var stockOpenedAt = 0L
    private val unmute = MtcBtService(ctx)
    @Volatile private var soundOpen = false

    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun enter(byUser: Boolean): BtRoute {
        BtAudio.logPick(ctx)
        // the stock music and radio close on this, as when the stock Bluetooth app opens; the BT app itself stays
        runCatching { ctx.sendBroadcast(Intent("com.microntek.bootcheck").putExtra("class", "com.microntek.bluetooth")) }
        runCatching { am.setParameters("av_channel_enter=gsm_bt") }
        SsmTrace.log("bt: microntek channel gsm_bt", keep = true)
        BtAudio.listenAnnouncements(ctx)
        attach()
        openSound(byUser)
        return BtRoute.UNIT_CHANNEL
    }

    /** The channel alone stays silent: the chip has to be unmuted, by the unit's own service or else by its stock screen. */
    private suspend fun openSound(byUser: Boolean) {
        val gone = left
        val code = unmute.unmute()
        if (gone != left) return
        val now = SystemClock.elapsedRealtime()
        val stock = BtAudio.stockBtMusic(ctx)
        val step = microntekSound(byUser, code != null, stock != null && now - stockOpenedAt > 60_000)
        SsmTrace.log("bt: microntek route $step" + (code?.let { " (transaction $it)" } ?: ""), keep = true)
        when (step) {
            MicrontekSound.UNMUTED -> soundOpen = true
            MicrontekSound.HOP -> { stockOpenedAt = now; soundOpen = true; if (stock != null) hop(stock) }
            MicrontekSound.NONE -> {}
        }
    }

    /** The stock BT music screen opened for a moment, then back to us: what drivers did by hand to get sound. */
    private fun hop(stock: android.content.ComponentName) {
        val am = am
        SsmTrace.log("bt: open ${stock.flattenToShortString()} for a moment", keep = true)
        runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).setComponent(stock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val back = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { am.setParameters("av_channel_enter=gsm_bt") }
            back?.let { b -> runCatching { ctx.startActivity(b) } }
        }, 1800)
    }

    /** A plain play or pause (no focus here: this sound isn't Android's); the chip first if the start left it muted. */
    override fun press(playing: Boolean) {
        audioScope.launch {
            val wasShut = !soundOpen
            if (wasShut) openSound(true)
            if (microntekPressSends(wasShut, playing, soundOpen)) send(if (playing) Transport.PAUSE else Transport.PLAY)
        }
    }

    /** Another mode was picked: Android's own sound back. */
    override fun leave() {
        super.leave()
        soundOpen = false
        unmute.release()
        val am = am
        runCatching { am.setParameters("av_channel_exit=gsm_bt") }
        runCatching { am.setParameters("av_channel_enter=sys") }
        SsmTrace.log("bt: microntek channel sys", keep = true)
    }

    /** No kick here: this BT sound never shows up in Android's audio, "no music" would be true all the time. */
    override fun read(): BtTrack {
        val s = sessionTrack(ctx)
        val a = BtAudio.announced.value
        val t = if (!s.session && a.title.isNotBlank()) s.copy(title = a.title, artist = a.artist, playing = a.playing ?: true) else s
        return t.copy(silentUnit = true)
    }
}

internal enum class MicrontekSound { UNMUTED, HOP, NONE }

/** The press that just opened the chip of a phone already playing has done its job: a pause on top would stop it. */
internal fun microntekPressSends(wasShut: Boolean, playing: Boolean, soundOpen: Boolean) = !(wasShut && playing && soundOpen)

/**
 * Unmuting is routing and asks for nothing, so a restored mode does it too; the stock screen flashing up comes only
 * from the driver's action.
 */
internal fun microntekSound(byUser: Boolean, unmuted: Boolean, hopFree: Boolean): MicrontekSound = when {
    unmuted -> MicrontekSound.UNMUTED
    byUser && hopFree -> MicrontekSound.HOP
    else -> MicrontekSound.NONE
}

/**
 * The number the unit's own stub gives musicUnmute, if it can be used. It differs by the unit's version (39 in one,
 * 40 in another, where 39 is musicMute), so it is never assumed and a number that is also musicMute's is refused.
 */
internal fun unmuteCode(unmute: Int?, mute: Int?): Int? = unmute?.takeIf { it in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION && it != mute }

/** The unit's Bluetooth service (android.microntek.mtcser), reached only with what its own APK says about it. */
private class MtcBtService(private val ctx: Context) {
    @Volatile private var binder: IBinder? = null
    @Volatile private var conn: ServiceConnection? = null
    @Volatile private var code: Int? = null

    /** The transaction it was sent with; null when it couldn't be, for any reason. */
    suspend fun unmute(): Int? = withContext(Dispatchers.IO) {
        try {
            if (!systemApp()) return@withContext null
            val c = code ?: readCode()?.also { code = it } ?: return@withContext null
            val b = binder?.takeIf { it.isBinderAlive } ?: bound() ?: return@withContext null
            // on its own thread: a service that hangs holds that one and not the press, and the hop still comes
            val sent = audioScope.async(Dispatchers.IO) { transact(b, c) }
            val answered = withTimeoutOrNull(TRANSACT_MS) { sent.await() }
            if (answered == null) SsmTrace.log("bt: musicUnmute got no answer in $TRANSACT_MS ms", keep = true)
            if (answered == null) release()
            if (answered == true) c else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SsmTrace.log("bt: musicUnmute failed: $e", keep = true)
            null
        }
    }

    private fun transact(b: IBinder, c: Int): Boolean {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(DESCRIPTOR)
            if (!b.transact(c, d, r, 0)) return false
            r.readException()
            return true
        } finally { d.recycle(); r.recycle() }
    }

    fun release() {
        conn?.let { c -> runCatching { ctx.unbindService(c) } }
        conn = null; binder = null
    }

    /** Reading the stub runs its static initialiser in this process, and binding starts it: the firmware's own app only. */
    private fun systemApp() = ctx.packageManager.getApplicationInfo(PACKAGE, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0

    /** From the Stub class in the unit's APK; an obfuscated or a different one gives null. */
    private fun readCode(): Int? {
        val cl = ctx.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        cl.loadClass(DESCRIPTOR).getMethod("musicUnmute") // no arguments, as sent below
        val stub = cl.loadClass("$DESCRIPTOR\$Stub")
        fun field(name: String) = runCatching { stub.getDeclaredField(name).apply { isAccessible = true }.getInt(null) }.getOrNull()
        return unmuteCode(field("TRANSACTION_musicUnmute"), field("TRANSACTION_musicMute"))
    }

    /** The service isn't named anywhere reliable: its Bluetooth-looking services, and only the one that answers as this interface. */
    private suspend fun bound(): IBinder? {
        release()
        val services = ctx.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SERVICES).services.orEmpty()
            .filter { it.exported && Regex("bt|blue|serial", RegexOption.IGNORE_CASE).containsMatchIn(it.name.substringAfterLast('.')) }
        for (s in services) {
            val got = CompletableDeferred<IBinder?>()
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { got.complete(service) }
                override fun onServiceDisconnected(name: ComponentName?) { binder = null }
            }
            var kept = false
            try {
                if (!ctx.bindService(Intent().setComponent(ComponentName(PACKAGE, s.name)), c, Context.BIND_AUTO_CREATE)) continue
                val b = withTimeoutOrNull(500) { got.await() }
                if (b != null && b.interfaceDescriptor == DESCRIPTOR) {
                    kept = true
                    binder = b; conn = c
                    SsmTrace.log("bt: microntek service ${s.name}", keep = true)
                    return b
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SsmTrace.log("bt: microntek service ${s.name}: $e", keep = true)
            } finally {
                if (!kept) runCatching { ctx.unbindService(c) }
            }
        }
        return null
    }

    private companion object {
        const val PACKAGE = "android.microntek.mtcser"
        const val DESCRIPTOR = "android.microntek.mtcser.BTServiceInf"
        const val TRANSACT_MS = 1000L
    }
}
