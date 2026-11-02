package kg.forester.sg.data

/**
 * The driver's menu: the screens in their order, and which are hidden. Ids are the names of the screens. [order] is what
 * was saved, merged with the factory list whenever it is read, so an item of a later version isn't lost. Nothing saved
 * (empty) is exactly the factory order. «Настройки» can move but never hide: it is the way back to this.
 */
data class NavMenu(val order: List<String>, val hiddenIds: Set<String>) {
    /** [shown]: what the driver has in the list now (without what this car doesn't offer); a step is a step among those. */
    fun moved(id: String, dir: Int, factory: List<String>, shown: List<String> = factory): NavMenu {
        val inShown = shown.indexOf(id)
        val neighbour = shown.getOrNull(inShown + dir)
        if (inShown < 0 || neighbour == null) return this
        val l = merge(order, factory).toMutableList()
        val i = l.indexOf(id)
        l.removeAt(i)
        val at = l.indexOf(neighbour)
        l.add(if (dir < 0) at else at + 1, id)
        return copy(order = l)
    }

    /** To the place [index] of the whole list, for a drag. */
    fun movedTo(id: String, index: Int, factory: List<String>): NavMenu {
        val l = merge(order, factory).toMutableList()
        if (!l.remove(id)) return this
        l.add(index.coerceIn(0, l.size), id)
        return copy(order = l)
    }

    fun hidden(id: String, hide: Boolean, factory: List<String>): NavMenu =
        if (hide && id == PINNED) this else copy(order = merge(order, factory), hiddenIds = if (hide) hiddenIds + id else hiddenIds - id)

    fun orderText() = order.joinToString(",")
    fun hiddenText() = hiddenIds.joinToString(",")

    companion object {
        const val PINNED = "Settings"
        val FACTORY = NavMenu(emptyList(), emptySet())

        fun reset() = FACTORY

        /**
         * The saved order with what is gone dropped, and each item it doesn't know put right after its factory
         * neighbour (first, if it is the factory's first), so it appears where the factory has it.
         */
        fun merge(saved: List<String>, factory: List<String>): List<String> {
            val l = saved.distinct().filter { it in factory }.toMutableList()
            factory.forEachIndexed { i, id ->
                if (id !in l) {
                    val before = factory.getOrNull(i - 1)
                    l.add(if (before == null) 0 else l.indexOf(before) + 1, id)
                }
            }
            return l
        }

        /** The menu as shown: in order, with the hidden ones out, «Настройки» always in. */
        fun visible(m: NavMenu, factory: List<String>): List<String> =
            merge(m.order, factory).filter { it !in m.hiddenIds || it == PINNED }

        fun parse(order: String, hidden: String): NavMenu {
            fun list(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return NavMenu(list(order), list(hidden).toSet())
        }

        /** The screen that was showing has just been hidden: where to go instead; null when it may stay. */
        fun afterHiding(current: String, visible: List<String>): String? =
            if (current in visible) null else visible.firstOrNull()

        /** Where Back goes: Home, or the first thing in the menu when Home is hidden. */
        fun backTarget(home: String, visible: List<String>): String = if (home in visible) home else visible.firstOrNull() ?: home
    }
}
