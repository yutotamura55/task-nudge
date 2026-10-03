package dev.tasknudge.reminders

import java.time.Duration

interface ReminderScheduler {
    fun ensurePeriodicWork()
    fun requestEvaluation(delay: Duration = Duration.ZERO)
}
