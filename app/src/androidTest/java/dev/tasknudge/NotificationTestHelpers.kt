package dev.tasknudge

import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

// notify() returning does not mean the OS notification list already contains it.
// Match the committed generation so a previous notification cannot satisfy the wait.
internal suspend fun DeviceTestApplication.awaitActiveReminder(): StatusBarNotification {
    val reminder = requireNotNull(requireNotNull(database).dao().activeReminders().lastOrNull())
    val manager = getSystemService(NotificationManager::class.java)
    fun matching() = manager.activeNotifications.singleOrNull {
        it.id == reminder.notificationId && it.notification.extras.getString("token") == reminder.token
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
