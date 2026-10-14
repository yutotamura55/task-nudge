package dev.tasknudge.reminders

enum class ReminderAction { COMPLETE, SNOOZE, DISMISS }
interface NotificationGateway {
    fun canNotify(): Boolean
    fun activeTokens(): Map<Int, String>
    fun post(notificationId: Int, taskId: Long, token: String, title: String)
    fun cancel(notificationId: Int)
}
