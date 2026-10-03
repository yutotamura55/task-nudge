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
    database: TaskDatabase = Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "task-nudge.db").addCallback(TaskDatabase.initializeDefaults).build(),
    val notificationGateway: NotificationGateway = AndroidNotificationGateway(context.applicationContext),
    val scheduler: ReminderScheduler = WorkManagerReminderScheduler(context.applicationContext),
    val clock: Clock = Clock.systemUTC(),
    val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val engine = ReminderEngine(database, clock, zoneProvider, notificationGateway, scheduler)
    val repository: TaskRepository = engine.repository
}
