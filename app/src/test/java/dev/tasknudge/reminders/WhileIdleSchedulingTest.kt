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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WhileIdleSchedulingTest {
    @Test fun schedulesWakeupWithoutAlarmClockUiOrExactAlarmPermission() {
        val context = RuntimeEnvironment.getApplication()
        WorkManager.initialize(context, Configuration.Builder().build())
        val scheduler = AndroidReminderScheduler(context)
        val deadline = Instant.parse("2030-01-01T10:15:00Z")
        scheduler.scheduleWhileIdle(deadline)
        val alarm = requireNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(deadline.toEpochMilli(), alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
        assertNull(alarm.showIntent)
        assertEquals(ReminderAlarmReceiver::class.java.name, shadowOf(alarm.operation).savedIntent.component!!.className)
    }
}
