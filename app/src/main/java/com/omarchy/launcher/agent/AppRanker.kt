package com.omarchy.launcher.agent

import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.UsageEvent
import kotlin.math.ln

/**
 * Local "agent" #1: ranks the app list by learned habit instead of alphabetical/install order.
 *
 * Score blends three on-device signals, no network or cloud model involved:
 *  - overall frequency (log-dampened, so one binge session can't dominate forever)
 *  - recency (exponential decay)
 *  - same-hour-of-day frequency (so e.g. a podcast app opened every morning at 7am surfaces at 7am)
 */
object AppRanker {

    private const val RECENCY_HALF_LIFE_MS = 1000L * 60 * 60 * 24 * 3 // 3 days
    private const val SAME_HOUR_WINDOW = 1 // +/- 1 hour counts as "same time of day"

    fun rank(apps: List<AppInfo>, usage: List<UsageEvent>, nowHourOfDay: Int, now: Long = System.currentTimeMillis()): List<AppInfo> {
        if (usage.isEmpty()) return apps
        val scores = apps.associateWith { app -> score(app.packageName, usage, nowHourOfDay, now) }
        return apps.sortedWith(
            compareByDescending<AppInfo> { scores[it] ?: 0.0 }.thenBy { it.label.lowercase() }
        )
    }

    /** Top-N apps for the home screen's favorites row. Falls back to install order if no history yet. */
    fun favorites(apps: List<AppInfo>, usage: List<UsageEvent>, nowHourOfDay: Int, count: Int = 6): List<AppInfo> =
        rank(apps, usage, nowHourOfDay).take(count)

    private fun score(packageName: String, usage: List<UsageEvent>, nowHourOfDay: Int, now: Long): Double {
        val events = usage.filter { it.packageName == packageName }
        if (events.isEmpty()) return 0.0

        val frequencyScore = ln(1.0 + events.size)

        val recencyScore = events.maxOf { e ->
            val ageMs = (now - e.timestampMillis).coerceAtLeast(0)
            Math.pow(0.5, ageMs.toDouble() / RECENCY_HALF_LIFE_MS)
        }

        val sameHourCount = events.count { hourDistance(it.hourOfDay, nowHourOfDay) <= SAME_HOUR_WINDOW }
        val habitScore = ln(1.0 + sameHourCount) * 2.0

        return frequencyScore + recencyScore * 3.0 + habitScore
    }

    private fun hourDistance(a: Int, b: Int): Int {
        val diff = Math.abs(a - b)
        return minOf(diff, 24 - diff)
    }
}
