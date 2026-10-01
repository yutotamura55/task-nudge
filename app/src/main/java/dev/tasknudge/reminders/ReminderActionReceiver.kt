package dev.tasknudge.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.tasknudge.TaskNudgeApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("taskId", -1)
        val token = intent.getStringExtra("token") ?: return
        val action = intent.getStringExtra("action")?.let { name -> ReminderAction.entries.firstOrNull { it.name == name } } ?: return
        if (id <= 0 || token.isBlank()) return
        val application = context.applicationContext as TaskNudgeApplication
        val pending = goAsync()
        application.reminderScope.launch {
            try {
                application.container.engine.handleAction(id, token, action)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.e("TaskNudge", "Unable to apply reminder action", failure)
            } finally {
                pending.finish()
            }
        }
    }
}
