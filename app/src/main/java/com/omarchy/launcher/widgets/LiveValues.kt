package com.omarchy.launcher.widgets

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The small, fixed set of live values widgets can interpolate with `${...}`. No sources/HTTP/
 *  shell commands like OWL's `cmd()`/`http()` - just what the OS already exposes cheaply. */
data class LiveValues(
    val clock: String,
    val date: String,
    val batteryPercent: Int,
) {
    fun asMap(): Map<String, String> = mapOf(
        "clock" to clock,
        "date" to date,
        "battery" to if (batteryPercent >= 0) batteryPercent.toString() else "…",
    )

    companion object {
        fun snapshot(context: Context): LiveValues {
            val now = Date()
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            return LiveValues(clock, date, percent)
        }
    }
}

@Composable
fun rememberLiveValues(context: Context, tickMillis: Long = 15_000L): LiveValues {
    val state = produceState(initialValue = LiveValues.snapshot(context), key1 = Unit) {
        while (true) {
            value = LiveValues.snapshot(context)
            kotlinx.coroutines.delay(tickMillis)
        }
    }
    return state.value
}

/** Replaces every `${name}` in [template] using [values], leaving unknown names as `…`. */
fun interpolate(template: String, values: Map<String, String>): String {
    val regex = Regex("""\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}""")
    return regex.replace(template) { match -> values[match.groupValues[1]] ?: "…" }
}
