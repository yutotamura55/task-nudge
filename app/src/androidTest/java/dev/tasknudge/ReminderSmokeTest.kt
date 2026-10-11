package dev.tasknudge

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.reminders.AndroidNotificationGateway
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReminderSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeviceTestApplication
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val dao get() = requireNotNull(app.database).dao()

    @Before fun setUp() = runBlocking {
        manager.cancel(AndroidNotificationGateway.NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.resetFixture()
        app.container.repository.saveSettings(ReminderSettings(0, 24, 30))
    }

    @After fun tearDown() {
        manager.cancel(AndroidNotificationGateway.NOTIFICATION_ID)
        app.closeFixture()
    }

    @Test fun completeActionPersistsOnceAndOldActionCannotClearNewReminder() = runBlocking {
        val first = app.container.repository.saveTask(null, "首のストレッチ", 1)
        app.container.repository.saveTask(null, "水を飲む", 1)
        app.container.engine.evaluate()
        val original = app.awaitActiveReminder()
        assertEquals(first, original.notification.extras.getLong("taskId"))
        assertEquals(1, activeReminderCount())
        app.container.engine.evaluate()
        assertEquals(1, activeReminderCount())

        val complete = original.notification.actions.first { it.title.toString() == "完了" }.actionIntent
        complete.send()
        complete.send()
        await { dao.taskWithCompletions(first)?.completions?.size == 1 && activeReminderCount() == 0 }
        assertEquals(1, dao.taskWithCompletions(first)?.completions?.size)

        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(31))
        app.container.engine.evaluate()
        val next = app.awaitActiveReminder()
        val nextToken = next.notification.extras.getString("token")
        assertNotEquals(original.notification.extras.getString("token"), nextToken)
        complete.send()
        // A stale receiver runs asynchronously; the current generation must survive it.
        await { dao.state().activeToken == nextToken }
        assertEquals(nextToken, app.awaitActiveReminder().notification.extras.getString("token"))
        assertEquals(1, dao.taskWithCompletions(first)?.completions?.size)
    }

    @Test fun snoozeActionDefersForOneHourAndDuplicateDoesNotExtendIt() = runBlocking {
        val taskId = app.container.repository.saveTask(null, "背筋を伸ばす", 1)
        app.container.engine.evaluate()
        val original = app.awaitActiveReminder()
        val snooze = original.notification.actions.first { it.title.toString() == "保留" }.actionIntent
        val expectedUntil = app.fixedClock.instantValue.plus(Duration.ofHours(1)).toEpochMilli()
        snooze.send()
        snooze.send()
        await { dao.task(taskId)?.snoozedUntil == expectedUntil && activeReminderCount() == 0 }
        assertEquals(expectedUntil, dao.task(taskId)?.snoozedUntil)
        assertTrue(dao.taskWithCompletions(taskId)?.completions.isNullOrEmpty())

        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(31))
        app.container.engine.evaluate()
        assertEquals(0, activeReminderCount())
        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(30))
        app.container.engine.evaluate()
        val resumed = app.awaitActiveReminder()
        assertEquals(1, activeReminderCount())
        assertNotEquals(original.notification.extras.getString("token"), resumed.notification.extras.getString("token"))
    }

    private fun activeReminderCount(): Int = manager.activeNotifications.count { it.id == AndroidNotificationGateway.NOTIFICATION_ID }
    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(50) }
    }
}
