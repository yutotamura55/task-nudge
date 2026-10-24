package dev.tasknudge.data

import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.ReminderTask
import java.time.Instant
import java.time.LocalDate

data class CompletionRecord(val id: Long, val completedAt: Instant, val localDate: LocalDate)
data class TaskSnapshot(val id: Long, val title: String, val recurrenceDays: Int, val archived: Boolean, val createdAt: Instant, val snoozedUntil: Instant?, val completions: List<CompletionRecord>, val recurrenceHours: Int? = null)
data class AppSnapshot(val tasks: List<TaskSnapshot>, val settings: ReminderSettings, val permissionRequested: Boolean)
internal fun TaskWithCompletions.snapshot() = TaskSnapshot(task.id, task.title, task.recurrenceDays, task.archived, Instant.ofEpochMilli(task.createdAt), task.snoozedUntil?.let(Instant::ofEpochMilli), completions.sortedByDescending { it.completedAt }.map { CompletionRecord(it.id, Instant.ofEpochMilli(it.completedAt), LocalDate.parse(it.localDate)) }, task.recurrenceHours)
internal fun ReminderSettingsEntity.core() = ReminderSettings(startHour, endHour, intervalMinutes)

/** Applies the shared completion and recurrence rules as seen on [today]. */
fun TaskSnapshot.reminderTask(today: LocalDate): ReminderTask {
    val latest = completions.maxByOrNull { it.completedAt }
    return ReminderTask(id, createdAt, recurrenceDays, recurrenceHours, archived, latest?.completedAt, latest?.localDate,
        completions.any { it.localDate == today }, snoozedUntil)
}
