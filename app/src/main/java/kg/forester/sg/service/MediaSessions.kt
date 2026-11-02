package kg.forester.sg.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle

/**
 * Only a notification listener may see other apps' media sessions: it gives the voice shuffle, repeat, rewind,
 * likes and "what's playing" for Yandex Music, VK, Spotify… The driver allows it once in the Android settings
 * («Доступ к уведомлениям»); without it these commands fall back to the media keys or say they can't.
 * The same permission gives the navigators' turn hints (see [NavGuidance]).
 */
class MediaListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        NavGuidance.onWorker { activeNotifications?.forEach { NavGuidance.posted(this, it) } }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        NavGuidance.posted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        NavGuidance.removed(sbn)
    }
}

/** What another player's media session shows: track, cover, position, play state. */
data class PlayerState(
    val title: String = "", val artist: String = "", val album: String = "", val art: android.graphics.Bitmap? = null,
    val posMs: Long = 0, val totalMs: Long = 0, val playing: Boolean = false, val session: Boolean = false,
)

fun playerState(c: android.media.session.MediaController): PlayerState {
    val md = c.metadata
    val st = c.playbackState
    val playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
    // the session reports the position at its last update: run it on while playing
    val pos = st?.let {
        if (playing && it.lastPositionUpdateTime > 0)
            it.position + ((android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong()
        else it.position
    } ?: 0L
    return PlayerState(
        title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
        artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
        album = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
        art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART),
        posMs = pos.coerceAtLeast(0), totalMs = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0,
        playing = playing, session = true,
    )
}

/**
 * Units with two modules (TS18 and others) have Android's own stack on the OBD one: its session is there, idle, and
 * nothing sent to it reaches the phone. A Bluetooth session counts when it plays, shows a track or a phone is connected.
 */
internal fun <T> pickBtSession(all: List<T>, playing: (T) -> Boolean, hasTrack: (T) -> Boolean, system: (T) -> Boolean, a2dp: () -> Boolean): T? =
    all.firstOrNull(playing) ?: all.firstOrNull(hasTrack)
        ?: if (a2dp()) all.firstOrNull { !system(it) } ?: all.firstOrNull() else null

/** The session that plays, else the latest one that isn't such an idle Bluetooth session ([pickBtSession]). */
internal fun <T> pickSession(all: List<T>, playing: (T) -> Boolean, hasTrack: (T) -> Boolean, isBt: (T) -> Boolean, a2dp: () -> Boolean): T? =
    all.firstOrNull(playing) ?: all.firstOrNull { !isBt(it) || hasTrack(it) || a2dp() }

object MediaSessions {
    fun allowed(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The session that plays now, else the latest one that counts ([pickSession]); null without the listener permission. */
    fun controller(ctx: Context): MediaController? = runCatching {
        pickSession(sessions(ctx), ::isPlaying, ::hasTrack, { isBt(it.packageName) }, { a2dpConnected(ctx) })
    }.getOrNull()

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun hasTrack(c: MediaController) = !c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()

    /** BluetoothProfile.A2DP_SINK, which the SDK hides. */
    private const val A2DP_SINK = 11

    /** A phone is connected to Android's stack for its sound to come here; false when it can't be asked. */
    fun a2dpConnected(ctx: Context): Boolean {
        // Android 12+ asks for the runtime permission; some firmware throws even with it
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            a != null && a.getProfileConnectionState(A2DP_SINK) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Whether a Bluetooth session taken from elsewhere (the stack's browser) counts, as in [pickBtSession]. */
    fun counts(ctx: Context, c: MediaController) = isPlaying(c) || hasTrack(c) || a2dpConnected(ctx)

    private fun sessions(ctx: Context): List<MediaController> = runCatching {
        ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
    }.getOrNull().orEmpty()

    /**
     * The app of a Bluetooth module: Android's own stack (com.android.bluetooth), FYT's com.syu.bt, and the head units'
     * own Bluetooth apps (a package with a "bt" part, gocsdk…).
     */
    fun isBt(pkg: String): Boolean = "bluetooth" in pkg || "gocsdk" in pkg || "a2dp" in pkg ||
        pkg.split('.').any { it == "bt" || it.startsWith("bt") && it.length <= 8 || it.endsWith("bt") && it.length <= 6 }

    private var btSeen = ""

    /**
     * The phone's music over Bluetooth, as a media session. Units with two modules (TS18 and others: one for the
     * phone and music, the other for OBD dongles) have Android's own stack on the OBD one: the session that plays or
     * shows a track wins, then, with a phone connected, a head unit's own Bluetooth app before Android's.
     */
    fun btController(ctx: Context): MediaController? {
        val bt = sessions(ctx).filter { isBt(it.packageName) }
        bt.joinToString(",") { it.packageName }.let { if (it != btSeen) { btSeen = it; kg.forester.sg.ssm.SsmTrace.log("bt sessions: ${it.ifEmpty { "none" }}", keep = true) } }
        fun system(c: MediaController) = c.packageName == "com.android.bluetooth" || c.packageName == "com.google.android.bluetooth"
        return pickBtSession(bt, ::isPlaying, ::hasTrack, ::system) { a2dpConnected(ctx) }
    }

    /** The session of the player [pkg], if it has one now (the app was started at least once). */
    fun controllerOf(ctx: Context, pkg: String): MediaController? = sessions(ctx).firstOrNull { it.packageName == pkg }

    /**
     * Music players on the unit, as package → name: apps offering a media browser service (Yandex Music, VK,
     * Spotify, YouTube Music…) and any app that has a media session right now.
     */
    fun players(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val pkgs = LinkedHashSet<String>()
        runCatching {
            pm.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService"), 0)
                .forEach { pkgs += it.serviceInfo.packageName }
        }
        sessions(ctx).forEach { pkgs += it.packageName }
        pkgs -= ctx.packageName
        // the FYT stock player and Bluetooth are offered as the head unit's own source
        pkgs.removeAll { it.startsWith("com.syu.") }
        return pkgs.mapNotNull { p ->
            if (pm.getLaunchIntentForPackage(p) == null) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: return@mapNotNull null
            p to label
        }.sortedBy { it.second.lowercase() }
    }

    /** Another app that plays right now (not this one, not Bluetooth), as package → name, or null. */
    fun playingApp(ctx: Context): Pair<String, String>? {
        val c = sessions(ctx).firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName != ctx.packageName &&
                !isBt(it.packageName) && !it.packageName.startsWith("com.syu.")
        } ?: return null
        val pm = ctx.packageManager
        val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
        return c.packageName to name
    }

    fun open(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { ctx.startActivity(it) }
        }
    }

    // the androidx.media "compat" custom actions every MediaSessionCompat / Media3 session understands
    fun shuffle(ctx: Context, on: Boolean): Boolean = controller(ctx)?.transportControls?.sendCustomAction(
        "android.support.v4.media.session.action.SET_SHUFFLE_MODE",
        Bundle().apply { putInt("android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE", if (on) 1 else 0) },
    ) != null

    /** [mode]: 0 off, 1 this track, 2 all. */
    fun repeat(ctx: Context, mode: Int): Boolean = controller(ctx)?.transportControls?.sendCustomAction(
        "android.support.v4.media.session.action.SET_REPEAT_MODE",
        Bundle().apply { putInt("android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE", mode) },
    ) != null

    /** "Artist — title" of what plays, or null. */
    fun nowPlaying(ctx: Context): String? {
        val m = controller(ctx)?.metadata ?: return null
        val title = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { null }
    }
}
