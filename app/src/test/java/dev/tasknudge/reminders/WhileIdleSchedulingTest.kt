package dev.tasknudge.reminders

import android.app.AlarmManager
import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WhileIdleSchedulingTest {
    @Test fun usesExactWakeupOnlyWhenSpecialAccessIsGranted() {
        val context = RuntimeEnvironment.getApplication()
        WorkManager.initialize(context, Configuration.Builder().build())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = AndroidReminderScheduler(context)
        val deadline = Instant.parse("2030-01-01T10:15:00Z")
        scheduler.scheduleWhileIdle(deadline)
        val alarm = requireNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(deadline.toEpochMilli(), alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
        assertEquals(0L, alarm.windowLengthMs)
        assertNull(alarm.showIntent)
        assertEquals(ReminderAlarmReceiver::class.java.name, shadowOf(alarm.operation).savedIntent.component!!.className)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.scheduleWhileIdle(deadline.plusSeconds(900))
        val fallback = requireNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
        assertTrue(fallback.allowWhileIdle)
        assertNotEquals(0L, fallback.windowLengthMs)
    }
}
