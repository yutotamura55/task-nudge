package dev.tasknudge.data

import dev.tasknudge.core.Backup
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.TaskInput
import kotlinx.coroutines.flow.Flow

/**
 * Commands return after their database transaction commits (saveTask returns the
 * committed ID). Pre-commit failures propagate; recoverable notification maintenance
 * after commit does not turn a successful write into a retryable failure.
 * Cancellation propagates and does not imply that a write was rolled back.
 * Snapshots are independently observed display data, never command acknowledgments.
 */
interface TaskRepository {
    val snapshots: Flow<AppSnapshot>
    suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int = 1, recurrenceHours: Int? = null): Long
    /** Inserts all tasks in one transaction and returns their committed IDs in input order. */
    suspend fun saveTasks(inputs: List<TaskInput>): List<Long>
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun completeTask(id: Long)
    suspend fun saveSettings(settings: ReminderSettings)
    suspend fun recordPermissionRequest()
    /** Reads tasks, completions and settings from one consistent database state. */
    suspend fun exportBackup(): Backup
    /**
     * Replaces every task, completion and the reminder settings with [backup] in one
     * transaction; a failure leaves the previous data. Device state such as the
     * notification permission request is kept.
     */
    suspend fun replaceWithBackup(backup: Backup)
}
