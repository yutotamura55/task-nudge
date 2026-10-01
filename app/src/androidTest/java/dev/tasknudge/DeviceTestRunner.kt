package dev.tasknudge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import dev.tasknudge.data.TaskDatabase
import dev.tasknudge.reminders.AndroidNotificationGateway
import dev.tasknudge.reminders.NotificationGateway
import dev.tasknudge.reminders.ReminderScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, DeviceTestApplication::class.java.name, context)
}

class DeviceTestApplication : TaskNudgeApplication() {
    var database: TaskDatabase? = null
        private set
    val fixedClock = MutableDeviceClock()

    // The test application intentionally avoids the production startup scheduler.
    // The receiver and Activity still use the same AppContainer and engine.
    override fun onCreate() {
        resetFixture()
    }

    fun resetFixture(notificationsEnabled: Boolean = true) {
        database?.close()
        fixedClock.instantValue = Instant.parse("2026-10-01T10:00:00Z")
        val db = Room.inMemoryDatabaseBuilder(this, TaskDatabase::class.java)
            .addCallback(TaskDatabase.initializeDefaults).build()
        database = db
        val gateway = if (notificationsEnabled) AndroidNotificationGateway(this) else DisabledDeviceGateway
        container = AppContainer(this, db, gateway, NoopDeviceScheduler, fixedClock) { ZoneId.of("Asia/Tokyo") }
    }

    fun closeFixture() {
        container.notificationGateway.cancel()
        database?.close()
        database = null
    }
}

class MutableDeviceClock(var instantValue: Instant = Instant.parse("2026-10-01T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instantValue, zone)
    override fun instant(): Instant = instantValue
}

private object NoopDeviceScheduler : ReminderScheduler {
    override fun ensurePeriodicWork() = Unit
    override fun requestEvaluation(delay: Duration) = Unit
}

private object DisabledDeviceGateway : NotificationGateway {
    override fun canNotify() = false
    override fun activeToken(): String? = null
    override fun post(taskId: Long, token: String, title: String): Nothing = error("Notifications are disabled in this fixture")
    override fun cancel() = Unit
}
