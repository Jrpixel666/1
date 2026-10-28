package com.omarchy.launcher.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omarchy.launcher.OmarchyLauncherApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** AlarmManager alarms don't survive a reboot - this puts every time-of-day rule's alarm back. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as OmarchyLauncherApplication).container
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rules = container.agentPreferences.rules.first()
                TimeOfDayScheduler.syncAll(context, rules)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
