package com.github.ytlog.mobby.android.programs.call

/** Calls per minute per caller and capability; a refused call does not count. */
internal class RateWindow {
    private val calls = HashMap<Pair<String, String>, ArrayDeque<Long>>()

    @Synchronized fun admit(caller: String, capability: String, perMinute: Int, now: Long): Boolean {
        val window = calls.getOrPut(caller to capability) { ArrayDeque() }
        while (window.isNotEmpty() && window.first() <= now - 60_000) window.removeFirst()
        if (window.size >= perMinute) return false
        window.addLast(now)
        if (calls.size > 10_000) calls.entries.removeAll { it.value.isEmpty() }
        return true
    }
}
