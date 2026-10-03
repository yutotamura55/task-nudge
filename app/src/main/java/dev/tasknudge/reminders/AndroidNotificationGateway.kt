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

    override fun activeToken(): String? = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.notification?.extras?.getString("token")

    override fun post(taskId: Long, token: String, title: String) {
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
            .setDeleteIntent(actionIntent(taskId, token, ReminderAction.DISMISS))
            .addAction(0, "完了", actionIntent(taskId, token, ReminderAction.COMPLETE))
            .addAction(0, "保留", actionIntent(taskId, token, ReminderAction.SNOOZE))
            .addExtras(android.os.Bundle().apply { putString("token", token); putLong("taskId", taskId) })
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
    override fun cancel() { manager.cancel(NOTIFICATION_ID) }
    private fun actionIntent(taskId: Long, token: String, action: ReminderAction): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setData("tasknudge://reminder/$token/${action.name}".toUri())
            .putExtra("taskId", taskId).putExtra("token", token).putExtra("action", action.name)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }
    companion object {
        const val CHANNEL_ID = "task_reminders"
        const val NOTIFICATION_ID = 1
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
