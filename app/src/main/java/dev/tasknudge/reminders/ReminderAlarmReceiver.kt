package dev.tasknudge.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import dev.tasknudge.TaskNudgeApplication
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Wakes the local reminder engine during Doze; it does not show an alarm UI or play a sound. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TaskNudgeApplication
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaskNudge:reminder").apply { acquire(15_000L) }
        val pending = goAsync()
        app.reminderScope.launch {
            try {
                app.container.engine.evaluate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("TaskNudge", "Unable to evaluate reminder", failure)
                app.container.scheduler.scheduleWhileIdle(Instant.now().plus(Duration.ofMinutes(15)))
            } finally {
                pending.finish()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }
}
