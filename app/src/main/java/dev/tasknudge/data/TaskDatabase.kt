package dev.tasknudge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TaskEntity::class, CompletionEntity::class, ReminderStateEntity::class, ReminderSettingsEntity::class], version = 1, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao
    companion object {
        val initializeDefaults = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO reminder_state (id, activeTaskId, activeToken, activeLocalDate, preparedAt, deliveredAt, lastNotifiedAt) VALUES (1, NULL, NULL, NULL, NULL, NULL, NULL)")
                db.execSQL("INSERT INTO reminder_settings (id, startHour, endHour, intervalMinutes, permissionRequested) VALUES (1, 9, 21, 30, 0)")
            }
        }
    }
}
