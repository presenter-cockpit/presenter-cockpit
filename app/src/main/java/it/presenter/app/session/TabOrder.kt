package it.presenter.app.session

/**
 * Order of the tabs in the top bar: PDFs and work tabs mixed as the user arranged them. Pure functions
 * on lists (tested); the keys make the order saveable.
 */
object TabOrder {
    /**
     * The tabs [present] in the order of [order]; those [order] does not know (just opened) go at the end,
     * in the order they have in [present].
     */
    fun <T> arrange(order: List<T>, present: List<T>): List<T> {
        val known = order.filter { it in present }.distinct()
        return known + present.filter { it !in known }
    }

    /** [list] with the element at [from] moved to [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val out = list.toMutableList()
        val item = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), item)
        return out
    }

    /** [list] with [item] (removed from where it was) right after [anchor]; without [anchor], at the end. */
    fun <T> insertAfter(list: List<T>, item: T, anchor: T): List<T> {
        val out = list.filter { it != item }.toMutableList()
        val at = out.indexOf(anchor)
        out.add(if (at < 0) out.size else at + 1, item)
        return out
    }

    /** The tab beside [item] in [list]: the one after it, or the one before if it is the last; null if alone. */
    fun <T> neighbor(list: List<T>, item: T): T? {
        val i = list.indexOf(item)
        if (i < 0) return null
        return list.getOrNull(i + 1) ?: list.getOrNull(i - 1)
    }

    /** The tabs [present] in the order of the saved keys [keys] (key of each tab: [key]). */
    fun <T> byKeys(keys: List<String>, present: List<T>, key: (T) -> String): List<T> {
        val byKey = present.associateBy(key)
        return arrange(keys.mapNotNull { byKey[it] }, present)
    }
}
