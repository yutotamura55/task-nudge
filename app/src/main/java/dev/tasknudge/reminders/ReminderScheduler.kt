package dev.tasknudge.reminders

import java.time.Duration
import java.time.Instant

interface ReminderScheduler {
    fun ensureRecurringEvaluation()
    fun requestEvaluation(delay: Duration = Duration.ZERO)
    fun scheduleWhileIdle(at: Instant)
}
