package dev.tasknudge.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {
    private val workManager = WorkManager.getInstance(context)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun ensureRecurringEvaluation() {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork("reminder-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        scheduleWhileIdle(Instant.now().plus(Duration.ofMinutes(15)))
    }

    override fun requestEvaluation(delay: Duration) {
        require(!delay.isNegative)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
        // KEEP bounds opportunistic requests to one job and never cancels a running worker.
        workManager.enqueueUniqueWork("reminder-evaluation", ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleWhileIdle(at: Instant) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
                return
            } catch (_: SecurityException) {
                // Access can change between the check and the scheduling call.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }
}
