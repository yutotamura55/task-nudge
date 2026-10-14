package dev.tasknudge.data

import androidx.room.*

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val recurrenceDays: Int = 1,
    val createdAt: Long,
    val archived: Boolean = false,
    val snoozedUntil: Long? = null,
)
@Entity(tableName = "completions", foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId"), Index(value = ["taskId", "localDate"], unique = true)])
data class CompletionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val taskId: Long, val completedAt: Long, val localDate: String)
@Entity(tableName = "reminder_state")
data class ReminderStateEntity(@PrimaryKey val id: Int = 1, val lastNotifiedAt: Long? = null)
@Entity(
    tableName = "active_reminders",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index(value = ["token"], unique = true)],
)
data class ActiveReminderEntity(
    @PrimaryKey(autoGenerate = true) val notificationId: Int = 0,
    val taskId: Long,
    val token: String,
    val localDate: String,
    val preparedAt: Long,
    val deliveredAt: Long? = null,
)
@Entity(tableName = "reminder_settings")
data class ReminderSettingsEntity(@PrimaryKey val id: Int = 1, val startHour: Int = 9, val endHour: Int = 21, val intervalMinutes: Int = 30, val permissionRequested: Boolean = false)
data class TaskWithCompletions(@Embedded val task: TaskEntity, @Relation(parentColumn = "id", entityColumn = "taskId") val completions: List<CompletionEntity>)
