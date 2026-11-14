package dev.tasknudge.ui

import dev.tasknudge.data.TaskSnapshot
import dev.tasknudge.data.reminderTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

enum class TaskSortOrder(val label: String) {
    DEFAULT("登録順"),
    INTERVAL_ASCENDING("通知間隔が短い順"),
    CREATED_NEWEST("登録日時が新しい順"),
    COMPLETIONS_DESCENDING("完了回数が多い順"),
    TITLE_ASCENDING("タイトル順"),
}

fun formatDateTime(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(dateTimeFormat)

fun taskStatus(task: TaskSnapshot, now: Instant, zone: ZoneId): String {
    if (task.archived) return "停止中"
    val today = now.atZone(zone).toLocalDate()
    val rules = task.reminderTask(today)
    if (!rules.canComplete(now)) return rules.nextCompletableAt?.let { "次回対象 ${formatDateTime(it, zone)}" } ?: "今日完了"
    rules.nextDueDate?.takeIf { it.isAfter(today) }?.let { return "次回対象日 $it" }
    task.snoozedUntil?.takeIf { rules.isSnoozed(now) }?.let { return "保留中・${formatDateTime(it, zone)}まで" }
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

fun sortTasks(tasks: List<TaskSnapshot>, order: TaskSortOrder): List<TaskSnapshot> = when (order) {
    TaskSortOrder.DEFAULT -> tasks
    TaskSortOrder.INTERVAL_ASCENDING -> tasks.sortedWith(
        compareBy<TaskSnapshot> { it.recurrenceHours?.toLong() ?: it.recurrenceDays.toLong() * 24 }
            .thenByDescending { it.createdAt }
            .thenBy { it.id },
    )
    TaskSortOrder.CREATED_NEWEST -> tasks.sortedWith(compareByDescending<TaskSnapshot> { it.createdAt }.thenBy { it.id })
    TaskSortOrder.COMPLETIONS_DESCENDING -> tasks.sortedWith(
        compareByDescending<TaskSnapshot> { it.completions.size }
            .thenByDescending { it.createdAt }
            .thenBy { it.id },
    )
    TaskSortOrder.TITLE_ASCENDING -> tasks.sortedWith(
        compareBy<TaskSnapshot, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id },
    )
}
