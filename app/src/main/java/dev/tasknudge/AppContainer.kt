package dev.tasknudge

import android.content.Context
import androidx.room.Room
import dev.tasknudge.data.TaskDatabase
import dev.tasknudge.data.TaskRepository
import dev.tasknudge.reminders.*
import java.time.Clock
import java.time.ZoneId

class AppContainer(
    context: Context,
    database: TaskDatabase = Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "task-nudge.db").addMigrations(TaskDatabase.MIGRATION_1_2, TaskDatabase.MIGRATION_2_3).addCallback(TaskDatabase.initializeDefaults).build(),
    val notificationGateway: NotificationGateway = AndroidNotificationGateway(context.applicationContext),
    val scheduler: ReminderScheduler = AndroidReminderScheduler(context.applicationContext),
    val clock: Clock = Clock.systemUTC(),
    val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val engine = ReminderEngine(database, clock, zoneProvider, notificationGateway, scheduler)
    val repository: TaskRepository = engine.repository
}
