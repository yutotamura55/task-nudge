package dev.tasknudge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TaskEntity::class, CompletionEntity::class, ReminderStateEntity::class, ActiveReminderEntity::class, ReminderSettingsEntity::class], version = 2, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao
    companion object {
        val initializeDefaults = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO reminder_state (id, lastNotifiedAt) VALUES (1, NULL)")
                db.execSQL("INSERT INTO reminder_settings (id, startHour, endHour, intervalMinutes, permissionRequested) VALUES (1, 9, 21, 30, 0)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS active_reminders (notificationId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId INTEGER NOT NULL, token TEXT NOT NULL, localDate TEXT NOT NULL, preparedAt INTEGER NOT NULL, deliveredAt INTEGER, FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_active_reminders_taskId ON active_reminders (taskId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_active_reminders_token ON active_reminders (token)")
                db.execSQL("INSERT INTO active_reminders (notificationId, taskId, token, localDate, preparedAt, deliveredAt) SELECT 1, activeTaskId, activeToken, activeLocalDate, preparedAt, deliveredAt FROM reminder_state WHERE activeTaskId IS NOT NULL AND activeToken IS NOT NULL AND activeLocalDate IS NOT NULL AND preparedAt IS NOT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS reminder_state_new (id INTEGER NOT NULL, lastNotifiedAt INTEGER, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO reminder_state_new (id, lastNotifiedAt) SELECT id, lastNotifiedAt FROM reminder_state")
                db.execSQL("DROP TABLE reminder_state")
                db.execSQL("ALTER TABLE reminder_state_new RENAME TO reminder_state")
            }
        }
    }
}
