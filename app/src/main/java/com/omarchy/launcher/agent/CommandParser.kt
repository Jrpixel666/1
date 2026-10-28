package com.omarchy.launcher.agent

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.omarchy.launcher.data.AppInfo
import com.omarchy.launcher.data.AppRepository

sealed class CommandResult {
    data class LaunchedApp(val app: AppInfo) : CommandResult()
    data class PerformedAction(val description: String) : CommandResult()
    data class FilterApps(val query: String) : CommandResult()
    data object NoMatch : CommandResult()
}

/**
 * Local "agent" #3: a tiny keyword/regex command interpreter for the search bar - no LLM, no
 * network call, just pattern matching against a handful of known intents. Anything that doesn't
 * match a pattern just falls through to a live fuzzy filter of the app list.
 */
class CommandParser(private val appRepository: AppRepository) {

    private val openPattern = Regex("""^(open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val callPattern = Regex("""^(call|dial)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val timerPattern = Regex("""^(timer|set timer)\s+(\d+)\s*(min|minutes)?$""", RegexOption.IGNORE_CASE)
    private val alarmPattern = Regex("""^(alarm|set alarm)\s+(\d{1,2}):(\d{2})\s*(am|pm)?$""", RegexOption.IGNORE_CASE)
    private val webSearchPattern = Regex("""^(search|google)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(context: Context, input: String, apps: List<AppInfo>): CommandResult {
        val text = input.trim()
        if (text.isEmpty()) return CommandResult.FilterApps("")

        openPattern.matchEntire(text)?.let { m ->
            val needle = m.groupValues[2]
            val app = fuzzyFindApp(needle, apps)
            // Launching is left to the caller (MainViewModel.onAppLaunched) so usage tracking
            // and automations fire exactly once, the same path as a normal tap.
            if (app != null) return CommandResult.LaunchedApp(app)
        }

        callPattern.matchEntire(text)?.let { m ->
            val target = m.groupValues[2]
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = android.net.Uri.parse("tel:" + target.filter { it.isDigit() || it == '+' }.ifEmpty { "" })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return CommandResult.PerformedAction("opened dialer for \"$target\"")
        }

        timerPattern.matchEntire(text)?.let { m ->
            val minutes = m.groupValues[2].toInt()
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return CommandResult.PerformedAction("started a $minutes minute timer")
        }

        alarmPattern.matchEntire(text)?.let { m ->
            var hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toInt()
            val meridiem = m.groupValues[4].lowercase()
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return CommandResult.PerformedAction("set an alarm for ${m.groupValues[2]}:${m.groupValues[3]}")
        }

        webSearchPattern.matchEntire(text)?.let { m ->
            val query = m.groupValues[2]
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra("query", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return CommandResult.PerformedAction("searching for \"$query\"")
            }
        }

        return CommandResult.FilterApps(text)
    }

    private fun fuzzyFindApp(needle: String, apps: List<AppInfo>): AppInfo? {
        val lowered = needle.lowercase().trim()
        return apps.firstOrNull { it.label.lowercase() == lowered }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(lowered) }
            ?: apps.firstOrNull { it.label.lowercase().contains(lowered) }
    }
}
