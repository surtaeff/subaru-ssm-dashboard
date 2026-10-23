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
        runCatching { activeNotifications?.forEach { NavGuidance.posted(this, it) } }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        NavGuidance.posted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        NavGuidance.removed(sbn)
    }
}

object MediaSessions {
    fun allowed(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The session that plays now, else the latest one; null without the listener permission. */
    fun controller(ctx: Context): MediaController? = runCatching {
        val all = sessions(ctx)
        all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }.getOrNull()

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
     * shows a track wins, then a head unit's own Bluetooth app before Android's.
     */
    fun btController(ctx: Context): MediaController? {
        val bt = sessions(ctx).filter { isBt(it.packageName) }
        bt.joinToString(",") { it.packageName }.let { if (it != btSeen) { btSeen = it; kg.forester.sg.ssm.SsmTrace.log("bt sessions: ${it.ifEmpty { "none" }}", keep = true) } }
        fun system(c: MediaController) = c.packageName == "com.android.bluetooth" || c.packageName == "com.google.android.bluetooth"
        return bt.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: bt.firstOrNull { !it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            ?: bt.firstOrNull { !system(it) } ?: bt.firstOrNull()
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
