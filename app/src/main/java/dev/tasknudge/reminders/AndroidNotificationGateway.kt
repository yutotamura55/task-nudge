package dev.tasknudge.reminders

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.tasknudge.MainActivity
import dev.tasknudge.R

class AndroidNotificationGateway(private val context: Context) : NotificationGateway {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "タスクのリマインダー", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "タスクの完了をやさしくお知らせします"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    override fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    override fun activeTokens(): Map<Int, String> = manager.activeNotifications.mapNotNull { status ->
        status.notification.extras.getString("token")?.let { status.id to it }
    }.toMap()

    override fun post(notificationId: Int, taskId: Long, token: String, title: String) {
        // Recheck at the OS boundary: permission or the channel can change after evaluation.
        check(canNotify()) { "Notifications are disabled" }
        val tap = Intent(context, MainActivity::class.java)
            .setData("tasknudge://reminder/$token/OPEN".toUri())
            .putExtra("taskId", taskId)
            .putExtra("token", token)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("タスクを思い出しましょう")
            .setContentText(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, tap, FLAGS))
            .setDeleteIntent(actionIntent(notificationId, taskId, token, ReminderAction.DISMISS))
            .addAction(0, "完了", actionIntent(notificationId, taskId, token, ReminderAction.COMPLETE))
            .addAction(0, "保留", actionIntent(notificationId, taskId, token, ReminderAction.SNOOZE))
            .addExtras(android.os.Bundle().apply { putString("token", token); putLong("taskId", taskId) })
            .build()
        manager.notify(notificationId, notification)
    }
    override fun cancel(notificationId: Int) { manager.cancel(notificationId) }
    private fun actionIntent(notificationId: Int, taskId: Long, token: String, action: ReminderAction): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setData("tasknudge://reminder/$token/${action.name}".toUri())
            .putExtra("notificationId", notificationId)
            .putExtra("taskId", taskId).putExtra("token", token).putExtra("action", action.name)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }
    companion object {
        const val CHANNEL_ID = "task_reminders"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
