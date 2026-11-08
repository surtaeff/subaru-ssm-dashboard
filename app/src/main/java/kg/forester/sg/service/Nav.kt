package kg.forester.sg.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import kg.forester.sg.data.Repo
import kg.forester.sg.data.Shortcuts

/**
 * Hands a place or a search to the navigator the driver uses: the one named in the command, else the app the
 * mini-map opens, else the «Навигатор» button, else the first one installed. Links per app: Yandex Navigator and
 * Maps (yandex.ru/dev), 2GIS (help.2gis.com), Google Maps (google.navigation / geo), Waze (waze.com/ul), OsmAnd
 * (its osmand.api scheme).
 */
object Nav {
    private val ORDER = listOf(
        "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "ru.dublgis.dgismobile",
        "com.google.android.apps.maps", "com.waze", "net.osmand.plus", "net.osmand",
    )

    fun pkg(ctx: Context, named: String?): String? =
        listOfNotNull(named, Repo.settings.mapApp.ifBlank { null }, Shortcuts.list.value.firstOrNull { it.icon == "navigation" }?.pkg)
            .firstOrNull { Shortcuts.isInstalled(ctx, it) } ?: ORDER.firstOrNull { Shortcuts.isInstalled(ctx, it) }

    private fun enc(s: String) = Uri.encode(s)

    /** Shows [q] in the navigator (the driver picks a result); Google Maps and Waze route straight away when [route]. */
    fun search(ctx: Context, pkg: String?, q: String, route: Boolean) {
        val g = Repo.gps.value
        val uri = when (pkg) {
            "ru.yandex.yandexnavi" -> "yandexnavi://map_search?text=${enc(q)}"
            "ru.yandex.yandexmaps" -> "yandexmaps://maps.yandex.ru/?text=${enc(q)}" +
                (if (g?.lat != null) "&ll=${g.lon},${g.lat}&z=14" else "")
            "ru.dublgis.dgismobile" -> "dgis://2gis.ru/search/${enc(q)}"
            "com.google.android.apps.maps" -> if (route) "google.navigation:q=${enc(q)}&mode=d" else "geo:${g?.lat ?: 0},${g?.lon ?: 0}?q=${enc(q)}"
            "com.waze" -> "https://waze.com/ul?q=${enc(q)}" + if (route) "&navigate=yes" else ""
            "net.osmand.plus", "net.osmand" -> if (g?.lat == null) "geo:0,0?q=${enc(q)}" else
                "osmand.api://navigate_search?dest_search_query=${enc(q)}&search_lat=${g.lat}&search_lon=${g.lon}" +
                    "&profile=car&force=true&show_search_results=${!route}"
            else -> "geo:0,0?q=${enc(q)}"
        }
        open(ctx, pkg, uri)
    }

    /** A route to known coordinates (home / work remembered by voice). */
    fun routeTo(ctx: Context, pkg: String?, lat: Double, lon: Double) {
        val g = Repo.gps.value
        val uri = when (pkg) {
            "ru.yandex.yandexnavi" -> "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
            "ru.yandex.yandexmaps" -> "yandexmaps://maps.yandex.ru/?rtext=${g?.lat ?: ""},${g?.lon ?: ""}~$lat,$lon&rtt=auto"
            "ru.dublgis.dgismobile" -> "dgis://2gis.ru/routeSearch/rsType/car/to/$lon,$lat" // longitude first
            "com.google.android.apps.maps" -> "google.navigation:q=$lat,$lon&mode=d"
            "com.waze" -> "https://waze.com/ul?ll=$lat,$lon&navigate=yes"
            "net.osmand.plus", "net.osmand" -> "osmand.api://navigate?dest_lat=$lat&dest_lon=$lon&profile=car&force=true"
            else -> "geo:$lat,$lon?q=$lat,$lon"
        }
        open(ctx, pkg, uri)
    }

    /** Traffic: Yandex Maps can open with the jams layer; elsewhere the navigator shows them anyway. */
    fun traffic(ctx: Context, pkg: String?) {
        if (Shortcuts.isInstalled(ctx, "ru.yandex.yandexmaps") && pkg != "ru.yandex.yandexnavi") {
            open(ctx, "ru.yandex.yandexmaps", "yandexmaps://maps.yandex.ru/?l=map,trf")
        } else kg.forester.sg.ui.openNavigator(ctx)
    }

    private fun open(ctx: Context, pkg: String?, uri: String) {
        // bring the navigator up the way its button does (split screen), then hand it the link
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        // the link goes with the launch, after the navigator is in its half (sent at once it docked as a strip)
        val sc = if (pkg != null) Shortcuts.list.value.firstOrNull { it.pkg == pkg } else null
        if (sc != null && Shortcuts.isInstalled(ctx, pkg!!)) { Shortcuts.launch(ctx, sc, then = i); return }
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(uri))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
