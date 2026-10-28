package com.omarchy.launcher.util

/** Simple subsequence fuzzy matcher (rofi/wofi-style): "cam" matches "Camera" and "ChatCam". */
object FuzzySearch {

    fun matches(query: String, target: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        val t = target.lowercase()
        if (t.contains(q)) return true
        var qi = 0
        for (c in t) {
            if (qi < q.length && c == q[qi]) qi++
        }
        return qi == q.length
    }

    fun <T> filter(query: String, items: List<T>, label: (T) -> String): List<T> =
        if (query.isBlank()) items else items.filter { matches(query, label(it)) }
}
