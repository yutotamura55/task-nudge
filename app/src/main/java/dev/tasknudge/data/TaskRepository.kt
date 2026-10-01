package dev.tasknudge.data

import dev.tasknudge.core.ReminderSettings
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    val snapshots: Flow<AppSnapshot>
    suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int = 1): Long
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun completeTask(id: Long)
    suspend fun saveSettings(settings: ReminderSettings)
    suspend fun recordPermissionRequest()
}
