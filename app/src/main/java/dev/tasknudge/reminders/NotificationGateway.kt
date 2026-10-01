package dev.tasknudge.reminders

import java.time.Instant
import java.time.LocalDate

enum class ReminderAction { COMPLETE, SNOOZE, DISMISS }
data class ActiveReminder(val taskId: Long, val token: String, val localDate: LocalDate, val preparedAt: Instant, val deliveredAt: Instant?)
interface NotificationGateway {
    fun canNotify(): Boolean
    fun activeToken(): String?
    fun post(taskId: Long, token: String, title: String)
    fun cancel()
}
