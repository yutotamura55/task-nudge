package dev.tasknudge.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tasknudge.TaskNudgeApplication

/** Restores the next reminder alarm after boot or a clock change. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskNudgeApplication
        app.container.scheduler.ensureRecurringEvaluation()
        app.container.scheduler.requestEvaluation()
    }
}
