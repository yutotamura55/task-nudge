package dev.tasknudge.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.tasknudge.TaskNudgeApplication

/** Restores the next reminder alarm after boot or a clock change. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskNudgeApplication
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED && Build.VERSION.SDK_INT >= 31 &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return
        app.container.scheduler.ensureRecurringEvaluation()
        app.container.scheduler.requestEvaluation()
    }
}
