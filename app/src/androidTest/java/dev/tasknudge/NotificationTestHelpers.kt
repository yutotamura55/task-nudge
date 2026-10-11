package dev.tasknudge

import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import dev.tasknudge.reminders.AndroidNotificationGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

// notify() returning does not mean the OS notification list already contains it.
// Match the committed generation so a previous notification cannot satisfy the wait.
internal suspend fun DeviceTestApplication.awaitActiveReminder(): StatusBarNotification {
    val token = requireNotNull(requireNotNull(database).dao().state().activeToken)
    val manager = getSystemService(NotificationManager::class.java)
    fun matching() = manager.activeNotifications.singleOrNull {
        it.id == AndroidNotificationGateway.NOTIFICATION_ID && it.notification.extras.getString("token") == token
    }
    return withTimeout(10_000) {
        var notification = matching()
        while (notification == null) {
            delay(50)
            notification = matching()
        }
        notification
    }
}
