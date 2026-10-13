package dev.tasknudge

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.tasknudge.core.ReminderSettings
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
        manager.cancelAll()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.resetFixture()
        app.container.repository.saveSettings(ReminderSettings(0, 24, 30))
    }

    @After fun tearDown() {
        manager.cancelAll()
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
        await { dao.activeReminders().any { it.token == nextToken } }
        assertEquals(nextToken, app.awaitActiveReminder().notification.extras.getString("token"))
        assertEquals(1, dao.taskWithCompletions(first)?.completions?.size)
    }

    @Test fun snoozeActionDefersForOneDayAndDuplicateDoesNotExtendIt() = runBlocking {
        val taskId = app.container.repository.saveTask(null, "背筋を伸ばす", 1)
        app.container.engine.evaluate()
        val original = app.awaitActiveReminder()
        val snooze = original.notification.actions.first { it.title.toString() == "保留" }.actionIntent
        val expectedUntil = app.fixedClock.instantValue.plus(Duration.ofDays(1)).toEpochMilli()
        snooze.send()
        snooze.send()
        await { dao.task(taskId)?.snoozedUntil == expectedUntil && activeReminderCount() == 0 }
        assertEquals(expectedUntil, dao.task(taskId)?.snoozedUntil)
        assertTrue(dao.taskWithCompletions(taskId)?.completions.isNullOrEmpty())

        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59))
        app.container.engine.evaluate()
        assertEquals(0, activeReminderCount())
        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(1))
        app.container.engine.evaluate()
        val resumed = app.awaitActiveReminder()
        assertEquals(1, activeReminderCount())
        assertNotEquals(original.notification.extras.getString("token"), resumed.notification.extras.getString("token"))
    }

    @Test fun noticesAccumulateAcrossIntervalsAndCompletionClearsOnlyMatchingTask() = runBlocking {
        app.container.repository.saveSettings(ReminderSettings(0, 24, 15))
        val first = app.container.repository.saveTask(null, "首", 1)
        val second = app.container.repository.saveTask(null, "腕", 1)
        app.container.engine.evaluate()
        val firstNotice = app.awaitActiveReminder()
        assertEquals(first, firstNotice.notification.extras.getLong("taskId"))

        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(16))
        app.container.engine.evaluate()
        val secondNotice = app.awaitActiveReminder()
        await { activeReminderCount() == 2 }
        assertEquals(second, secondNotice.notification.extras.getLong("taskId"))

        app.fixedClock.instantValue = app.fixedClock.instantValue.plus(Duration.ofMinutes(16))
        app.container.engine.evaluate()
        await { activeReminderCount() == 3 }
        val complete = firstNotice.notification.actions.first { it.title.toString() == "完了" }.actionIntent
        complete.send()
        await { dao.taskWithCompletions(first)?.completions?.size == 1 && activeReminderCount() == 1 }
        val remaining = manager.activeNotifications.single { it.notification.extras.getString("token") != null }
        assertEquals(secondNotice.id, remaining.id)
        assertEquals(second, remaining.notification.extras.getLong("taskId"))
    }

    private fun activeReminderCount(): Int = manager.activeNotifications.count { it.notification.extras.getString("token") != null }
    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(50) }
    }
}
