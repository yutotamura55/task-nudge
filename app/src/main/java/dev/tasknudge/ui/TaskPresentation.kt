package dev.tasknudge.ui

import dev.tasknudge.data.TaskSnapshot
import dev.tasknudge.data.reminderTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatDateTime(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(dateTimeFormat)

fun taskStatus(task: TaskSnapshot, now: Instant, zone: ZoneId): String {
    if (task.archived) return "停止中"
    val today = now.atZone(zone).toLocalDate()
    val rules = task.reminderTask(today)
    if (!rules.canComplete(now)) return rules.nextCompletableAt?.let { "次回対象 ${formatDateTime(it, zone)}" } ?: "今日完了"
    rules.nextDueDate?.takeIf { it.isAfter(today) }?.let { return "次回対象日 $it" }
    if (rules.isSnoozed(now)) return "保留中・${formatDateTime(task.snoozedUntil!!, zone)}まで"
    return "実行できます"
}

fun taskCanComplete(task: TaskSnapshot, now: Instant, zone: ZoneId): Boolean =
    !task.archived && task.reminderTask(now.atZone(zone).toLocalDate()).canComplete(now)

fun completeActionLabel(task: TaskSnapshot, canComplete: Boolean): String = when {
    canComplete -> "完了"
    task.recurrenceHours != null -> "次回まで待機"
    else -> "今日完了済み"
}

fun visibleTasks(tasks: List<TaskSnapshot>, showArchived: Boolean, query: String): List<TaskSnapshot> {
    val keyword = query.trim()
    return tasks.filter { (showArchived || !it.archived) && it.title.contains(keyword, ignoreCase = true) }
}
