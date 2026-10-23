package kg.forester.sg.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** The next manoeuvre as a navigator shows it in its notification: "300 м", "Поверните направо на ул. Ленина", an arrow. */
data class NavHint(
    val pkg: String,
    val title: String,
    val text: String,
    val sub: String,
    val icon: Bitmap?,
)

/**
 * Turn hints for the «Приборка с картой» card, read from the navigators' own notifications while they guide
 * (Google Maps, 2GIS and others print the distance, the street and an arrow there). Yandex Maps and Navigator put
 * nothing in the notification's text: it is all in their own layout (titleView with the distance, descriptionView,
 * the arrow in primaryIcon, the time left and of arrival), which is inflated here and read by the names of its
 * views. Another app's map cannot be shown inside this one, its notification can be read: that is the
 * [MediaListener]'s permission, the one the media controls already ask for. Only notifications of navigation apps
 * are looked at, nothing is logged.
 */
object NavGuidance {
    val hint = MutableStateFlow<NavHint?>(null)
    private var key: String? = null

    private val NavApps = setOf(
        "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps", "ru.dublgis.dgismobile",
        "com.waze", "net.osmand.plus", "net.osmand", "com.sygic.aura", "cityguide.probki.net", "app.organicmaps",
        "com.here.app.maps", "com.mapswithme.maps.pro",
    )

    fun posted(ctx: Context, sbn: StatusBarNotification) = runCatching {
        val n = sbn.notification ?: return@runCatching
        if (sbn.packageName !in NavApps && n.category != Notification.CATEGORY_NAVIGATION) return@runCatching
        // guidance runs as an ongoing notification; reviews, offers and traffic news are not
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return@runCatching
        val ex = n.extras
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        var sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        var icon = n.getLargeIcon()?.loadDrawable(ctx)?.let(::bitmapOf)
        // nothing in the text: the navigator's own layout (Yandex)
        if (!(title + text).any { it.isDigit() }) fromViews(ctx, sbn.packageName, n)?.let { v ->
            title = v.title; text = v.text; sub = v.sub; icon = v.icon ?: icon
        }
        // a manoeuvre always comes with a distance or a time; "Навигатор работает в фоне" does not
        if (!(title + text).any { it.isDigit() }) {
            if (sbn.key == key) clear()
            return@runCatching
        }
        key = sbn.key
        hint.value = NavHint(sbn.packageName, title, text, sub, icon)
    }

    private class Views(val title: String, val text: String, val sub: String, val icon: Bitmap?)

    /**
     * The texts and the arrow of a notification drawn in the navigator's own layout: inflated as the shade would,
     * its views read by their resource names (Yandex: titleView "350 м", descriptionView the street, primaryIcon the
     * arrow, remainingDistanceView / remainingTimeView / timeOfArrivalView). Another layout: its texts in order.
     */
    private fun fromViews(ctx: Context, pkg: String, n: Notification): Views? {
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView ?: n.headsUpContentView ?: return null
        val root = runCatching { rv.apply(ctx, null) }.getOrNull() ?: return null
        val res = runCatching { ctx.packageManager.getResourcesForApplication(pkg) }.getOrNull()
        val texts = LinkedHashMap<String, String>()
        var arrow: Bitmap? = null
        var anyIcon: Bitmap? = null
        fun walk(v: android.view.View) {
            if (v.visibility != android.view.View.VISIBLE) return
            val name = if (v.id > 0 && res != null) runCatching { res.getResourceEntryName(v.id) }.getOrDefault("") else ""
            when (v) {
                is android.view.ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                is android.widget.TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts[name.ifEmpty { "t${texts.size}" }] = it }
                is android.widget.ImageView -> v.drawable?.takeIf { it.intrinsicWidth > 0 }?.let { d ->
                    if (arrow == null && (name.startsWith("primaryIcon") || "maneuver" in name.lowercase())) arrow = bitmapOf(d)
                    // not the app's own little icon in the header
                    else if (anyIcon == null && "title" !in name.lowercase() && "app" !in name.lowercase()) anyIcon = bitmapOf(d)
                }
            }
        }
        walk(root)
        if (texts.isEmpty()) return null
        val title = texts["titleView"] ?: texts.values.firstOrNull { t -> t.any { it.isDigit() } } ?: texts.values.first()
        val text = texts["descriptionView"] ?: texts.values.firstOrNull { it != title }.orEmpty()
        val sub = listOfNotNull(texts["remainingDistanceView"], texts["remainingTimeView"], texts["timeOfArrivalView"]).joinToString(" · ")
        return Views(title, text, sub, arrow ?: anyIcon)
    }

    fun removed(sbn: StatusBarNotification) {
        if (sbn.key == key) clear()
    }

    private fun clear() {
        key = null
        hint.value = null
    }

    private fun bitmapOf(d: android.graphics.drawable.Drawable): Bitmap {
        val side = 128
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, side, side)
        d.draw(android.graphics.Canvas(b))
        return b
    }
}
