package dev.tasknudge.reminders

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.tasknudge.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidNotificationGatewayTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private lateinit var gateway: AndroidNotificationGateway
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gateway = AndroidNotificationGateway(context)
    }
    @Test fun fixedNotificationReplacesGenerationAndPreservesActionTokens() {
        gateway.post(5, "old", "首")
        val oldDelete = manager.activeNotifications.single().notification.deleteIntent
        gateway.post(6, "fresh", "腕")
        val notification = manager.activeNotifications.single().notification
        assertEquals("fresh", gateway.activeToken())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals("腕", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("完了", "保留"), notification.actions.map { it.title.toString() })
        val complete = shadowOf(notification.actions[0].actionIntent).savedIntent
        val snooze = shadowOf(notification.actions[1].actionIntent).savedIntent
        val delete = shadowOf(notification.deleteIntent).savedIntent
        for ((intent, action) in listOf(complete to "COMPLETE", snooze to "SNOOZE", delete to "DISMISS")) {
            assertEquals(6L, intent.getLongExtra("taskId", -1))
            assertEquals("fresh", intent.getStringExtra("token"))
            assertEquals(action, intent.getStringExtra("action"))
            assertEquals("tasknudge://reminder/fresh/$action", intent.data.toString())
            assertEquals(ReminderActionReceiver::class.java.name, intent.component!!.className)
        }
        assertEquals("old", shadowOf(oldDelete).savedIntent.getStringExtra("token"))
        assertTrue(notification.actions[0].actionIntent.isImmutable)
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(6L, tap.getLongExtra("taskId", -1))
        assertEquals(MainActivity::class.java.name, tap.component!!.className)
        gateway.cancel(); assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun disabledChannelAndGlobalNotificationsAreDetected() {
        assertTrue(gateway.canNotify())
        val channel = manager.getNotificationChannel("task_reminders")
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertFalse(gateway.canNotify())
    }
    @Test fun deniedPermissionIsDetectedBeforePosting() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(gateway.canNotify())
    }
    @Test fun globallyDisabledNotificationsAreDetected() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(gateway.canNotify())
    }
}
