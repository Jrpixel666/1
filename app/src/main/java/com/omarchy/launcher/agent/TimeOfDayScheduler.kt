package com.omarchy.launcher.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.omarchy.launcher.data.AutomationRule
import java.util.Calendar

/**
 * Schedules/cancels one daily AlarmManager alarm per time-of-day automation rule. Inexact
 * (`setAndAllowWhileIdle`) on purpose - a "do this around 7am" automation doesn't need
 * battery-costly exact-alarm permission, it just needs to fire once a day near the target time.
 */
object TimeOfDayScheduler {
    const val ACTION_FIRE_RULE = "com.omarchy.launcher.action.FIRE_TIME_RULE"
    const val EXTRA_RULE_ID = "rule_id"

    fun syncAll(context: Context, rules: List<AutomationRule>) {
        rules.filter { it.triggerType == AutomationTriggerType.TIME_OF_DAY.id }
            .forEach { rule -> if (rule.enabled) schedule(context, rule) else cancel(context, rule.id) }
    }

    fun schedule(context: Context, rule: AutomationRule) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = nextTriggerTimeMillis(rule.triggerHour, rule.triggerMinute)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntentFor(context, rule.id))
    }

    fun cancel(context: Context, ruleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntentFor(context, ruleId))
    }

    private fun pendingIntentFor(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, TimeOfDayAlarmReceiver::class.java)
            .setAction(ACTION_FIRE_RULE)
            .putExtra(EXTRA_RULE_ID, ruleId)
        return PendingIntent.getBroadcast(
            context,
            ruleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerTimeMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }
}
