package dev.tasknudge.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction @Query("SELECT * FROM tasks ORDER BY createdAt, id") fun observeTasks(): Flow<List<TaskWithCompletions>>
    @Transaction @Query("SELECT * FROM tasks ORDER BY createdAt, id") suspend fun tasks(): List<TaskWithCompletions>
    @Transaction @Query("SELECT * FROM tasks WHERE id = :id") suspend fun taskWithCompletions(id: Long): TaskWithCompletions?
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: Long): TaskEntity?
    @Insert suspend fun insertTask(task: TaskEntity): Long
    @Update suspend fun updateTask(task: TaskEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCompletion(completion: CompletionEntity): Long
    @Insert suspend fun insertCompletions(completions: List<CompletionEntity>)
    @Query("DELETE FROM completions") suspend fun deleteAllCompletions()
    @Query("DELETE FROM tasks") suspend fun deleteAllTasks()
    @Query("SELECT * FROM reminder_state WHERE id = 1") suspend fun state(): ReminderStateEntity
    @Upsert suspend fun saveState(state: ReminderStateEntity)
    @Query("SELECT * FROM active_reminders ORDER BY notificationId") suspend fun activeReminders(): List<ActiveReminderEntity>
    @Query("SELECT * FROM active_reminders WHERE token = :token") suspend fun activeReminder(token: String): ActiveReminderEntity?
    @Insert suspend fun insertActiveReminder(reminder: ActiveReminderEntity): Long
    @Query("UPDATE active_reminders SET deliveredAt = :deliveredAt WHERE notificationId = :notificationId") suspend fun markDelivered(notificationId: Int, deliveredAt: Long)
    @Query("DELETE FROM active_reminders WHERE notificationId = :notificationId") suspend fun deleteActiveReminder(notificationId: Int)
    @Query("DELETE FROM active_reminders WHERE taskId = :taskId") suspend fun deleteTaskReminders(taskId: Long)
    @Query("DELETE FROM active_reminders") suspend fun deleteAllReminders()
    @Query("SELECT * FROM reminder_settings WHERE id = 1") suspend fun settings(): ReminderSettingsEntity
    @Query("SELECT * FROM reminder_settings WHERE id = 1") fun observeSettings(): Flow<ReminderSettingsEntity>
    @Upsert suspend fun saveSettings(settings: ReminderSettingsEntity)
}
