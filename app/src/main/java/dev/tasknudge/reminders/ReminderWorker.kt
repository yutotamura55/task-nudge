package dev.tasknudge.reminders

import android.content.Context
import androidx.work.*
import dev.tasknudge.TaskNudgeApplication
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
