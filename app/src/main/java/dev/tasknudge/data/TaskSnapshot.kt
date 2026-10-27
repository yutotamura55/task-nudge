package dev.tasknudge.data

import dev.tasknudge.core.BackupCompletion
import dev.tasknudge.core.BackupTask
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.ReminderTask
import java.time.Instant
import java.time.LocalDate

data class CompletionRecord(val id: Long, val completedAt: Instant, val localDate: LocalDate)

data class TaskSnapshot(
    val id: Long,
    val title: String,
    val recurrenceDays: Int,
    val archived: Boolean,
    val createdAt: Instant,
    val snoozedUntil: Instant?,
    val completions: List<CompletionRecord>,
    val recurrenceHours: Int? = null,
)

data class AppSnapshot(val tasks: List<TaskSnapshot>, val settings: ReminderSettings, val permissionRequested: Boolean)

internal fun TaskWithCompletions.snapshot() = TaskSnapshot(
    id = task.id,
    title = task.title,
    recurrenceDays = task.recurrenceDays,
    archived = task.archived,
    createdAt = Instant.ofEpochMilli(task.createdAt),
    snoozedUntil = task.snoozedUntil?.let(Instant::ofEpochMilli),
    completions = completions.sortedByDescending { it.completedAt }
        .map { CompletionRecord(it.id, Instant.ofEpochMilli(it.completedAt), LocalDate.parse(it.localDate)) },
    recurrenceHours = task.recurrenceHours,
)

internal fun ReminderSettingsEntity.core() = ReminderSettings(startHour, endHour, intervalMinutes)

/** Applies the shared completion and recurrence rules as seen on [today]. */
fun TaskSnapshot.reminderTask(today: LocalDate): ReminderTask {
    val latest = completions.maxByOrNull { it.completedAt }
    return ReminderTask(
        id = id,
        createdAt = createdAt,
        recurrenceDays = recurrenceDays,
        recurrenceHours = recurrenceHours,
        archived = archived,
        lastCompletedAt = latest?.completedAt,
        lastCompletedDate = latest?.localDate,
        completedToday = completions.any { it.localDate == today },
        snoozedUntil = snoozedUntil,
    )
}

/** Same as [TaskSnapshot.reminderTask], parsing only the latest completion for engine checks inside the lock. */
internal fun TaskWithCompletions.reminderTask(today: LocalDate): ReminderTask {
    val latest = completions.maxByOrNull { it.completedAt }
    val todayText = today.toString()
    return ReminderTask(
        id = task.id,
        createdAt = Instant.ofEpochMilli(task.createdAt),
        recurrenceDays = task.recurrenceDays,
        recurrenceHours = task.recurrenceHours,
        archived = task.archived,
        lastCompletedAt = latest?.completedAt?.let(Instant::ofEpochMilli),
        lastCompletedDate = latest?.localDate?.let(LocalDate::parse),
        completedToday = completions.any { it.localDate == todayText },
        snoozedUntil = task.snoozedUntil?.let(Instant::ofEpochMilli),
    )
}

internal fun TaskWithCompletions.backupTask() = BackupTask(
    title = task.title,
    recurrenceDays = task.recurrenceDays,
    recurrenceHours = task.recurrenceHours,
    createdAt = Instant.ofEpochMilli(task.createdAt),
    archived = task.archived,
    snoozedUntil = task.snoozedUntil?.let(Instant::ofEpochMilli),
    completions = completions.sortedWith(compareBy({ it.completedAt }, { it.localDate }))
        .map { BackupCompletion(Instant.ofEpochMilli(it.completedAt), LocalDate.parse(it.localDate)) },
)

internal fun BackupCompletion.entity(taskId: Long) =
    CompletionEntity(taskId = taskId, completedAt = completedAt.toEpochMilli(), localDate = localDate.toString())

internal fun BackupTask.entity() = TaskEntity(
    title = title,
    recurrenceDays = recurrenceDays,
    recurrenceHours = recurrenceHours,
    createdAt = createdAt.toEpochMilli(),
    archived = archived,
    snoozedUntil = snoozedUntil?.toEpochMilli(),
)
