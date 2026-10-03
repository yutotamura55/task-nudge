package dev.tasknudge.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ReminderTask(
    val id: Long,
    val createdAt: Instant,
    val recurrenceDays: Int = 1,
    val archived: Boolean = false,
    val lastCompletedAt: Instant? = null,
    val lastCompletedDate: LocalDate? = null,
    val completedToday: Boolean = false,
    val snoozedUntil: Instant? = null,
)

data class ReminderSettings(
    val startHour: Int = 9,
    val endHour: Int = 21,
    val intervalMinutes: Int = 30,
)

object ReminderPolicy {
    fun select(
        tasks: List<ReminderTask>,
        now: Instant,
        zone: ZoneId,
        settings: ReminderSettings,
        lastNotifiedAt: Instant?,
        hasActiveNotification: Boolean,
    ): Long? {
        if (!isWithinHours(now, zone, settings) || hasActiveNotification) return null
        if (lastNotifiedAt != null &&
            Duration.between(lastNotifiedAt, now) < Duration.ofMinutes(settings.intervalMinutes.toLong())
        ) return null

        val today = now.atZone(zone).toLocalDate()
        return tasks.asSequence()
            .filter { task ->
                !task.archived && !task.completedToday &&
                    (task.lastCompletedDate == null ||
                        ChronoUnit.DAYS.between(task.lastCompletedDate, today) >= task.recurrenceDays) &&
                    (task.snoozedUntil == null || !now.isBefore(task.snoozedUntil))
            }
            .minWithOrNull(
                compareBy<ReminderTask> { if (it.snoozedUntil != null) 0 else 1 }
                    .thenBy { it.lastCompletedAt ?: it.createdAt }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )?.id
    }

    fun isWithinHours(now: Instant, zone: ZoneId, settings: ReminderSettings): Boolean {
        validateSettings(settings)
        val hour = now.atZone(zone).hour
        return hour >= settings.startHour && hour < settings.endHour
    }
}
