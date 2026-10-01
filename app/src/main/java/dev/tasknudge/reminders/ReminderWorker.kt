package dev.tasknudge.reminders

import android.content.Context
import androidx.work.*
import dev.tasknudge.TaskNudgeApplication
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as TaskNudgeApplication).container.engine.evaluate()
        Result.success()
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Result.retry()
    }
}
class WorkManagerReminderScheduler(context: Context) : ReminderScheduler {
    private val workManager = WorkManager.getInstance(context)
    override fun ensurePeriodicWork() {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork("reminder-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    override fun requestEvaluation(delay: Duration) {
        require(!delay.isNegative)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
        // KEEP bounds opportunistic requests to one job and never cancels a running worker.
        // The periodic job supplies subsequent evaluations, so there is no successor chain.
        workManager.enqueueUniqueWork("reminder-evaluation", ExistingWorkPolicy.KEEP, request)
    }
}
