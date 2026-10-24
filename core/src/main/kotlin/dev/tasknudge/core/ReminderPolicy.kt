package dev.tasknudge.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReminderTask(
    val id: Long,
    val createdAt: Instant,
    val recurrenceDays: Int = 1,
    val recurrenceHours: Int? = null,
    val archived: Boolean = false,
    val lastCompletedAt: Instant? = null,
    val lastCompletedDate: LocalDate? = null,
    val completedToday: Boolean = false,
    val snoozedUntil: Instant? = null,
) {
    /** Hourly tasks wait from the completion time; daily tasks only from the completion date. */
    val nextCompletableAt: Instant?
        get() = if (recurrenceHours == null) null else lastCompletedAt?.plus(Duration.ofHours(recurrenceHours.toLong()))

    /** Daily tasks become due on this local date; hourly tasks use [nextCompletableAt]. */
    val nextDueDate: LocalDate?
        get() = if (recurrenceHours == null) lastCompletedDate?.plusDays(recurrenceDays.toLong()) else null

    /** A daily task can be completed once per local date even before its recurrence is due. */
    fun canComplete(now: Instant): Boolean =
        if (recurrenceHours == null) !completedToday else nextCompletableAt?.let { !now.isBefore(it) } ?: true

    fun isSnoozed(now: Instant): Boolean = snoozedUntil?.let { now.isBefore(it) } ?: false

    fun isDue(now: Instant, today: LocalDate): Boolean =
        !archived && canComplete(now) && (nextDueDate?.let { !today.isBefore(it) } ?: true) && !isSnoozed(now)
}

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
        lastNotifiedByTask: Map<Long, Instant> = emptyMap(),
    ): Long? {
        if (!isWithinHours(now, zone, settings)) return null
        if (lastNotifiedAt != null &&
            Duration.between(lastNotifiedAt, now) < Duration.ofMinutes(settings.intervalMinutes.toLong())
        ) return null

        val today = now.atZone(zone).toLocalDate()
        return tasks.asSequence()
            .filter { it.isDue(now, today) }
            .minWithOrNull(
                compareBy<ReminderTask> { lastNotifiedByTask[it.id] }
                    .thenBy { if (it.snoozedUntil != null) 0 else 1 }
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
