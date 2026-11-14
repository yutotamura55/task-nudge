package dev.tasknudge.ui

import androidx.compose.runtime.saveable.Saver
import dev.tasknudge.data.TaskSnapshot
import dev.tasknudge.data.reminderTask
import java.time.Duration
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

/** The orders the task list offers, in the order its sort menu lists them. */
enum class TaskSortOrder(val label: String) {
    REGISTERED("登録順"),
    RECURRENCE_SHORTEST("繰り返しの間隔が短い順"),
    REGISTERED_NEWEST("登録日時が新しい順"),
    COMPLETIONS_MOST("完了回数が多い順"),
    ;

    companion object {
        /** Saves the name, so a renamed or removed order restores as registration order. */
        val saver: Saver<TaskSortOrder, String> = Saver(
            save = { it.name },
            restore = { name -> entries.firstOrNull { it.name == name } ?: REGISTERED },
        )
    }
}

private val registrationOrder = compareBy<TaskSnapshot>({ it.createdAt }, { it.id })

/** Sorts [tasks] by [order]. Tasks with the same value stay in registration order. */
fun sortTasks(tasks: List<TaskSnapshot>, order: TaskSortOrder): List<TaskSnapshot> = tasks.sortedWith(
    when (order) {
        TaskSortOrder.REGISTERED -> registrationOrder
        TaskSortOrder.RECURRENCE_SHORTEST -> compareBy(::recurrenceInterval).then(registrationOrder)
        TaskSortOrder.REGISTERED_NEWEST -> registrationOrder.reversed()
        TaskSortOrder.COMPLETIONS_MOST ->
            compareByDescending<TaskSnapshot> { it.completions.size }.then(registrationOrder)
    },
)

/** Compares an hourly recurrence with a daily one by length, so 24 hours equals 1 day. */
private fun recurrenceInterval(task: TaskSnapshot): Duration =
    task.recurrenceHours?.let { Duration.ofHours(it.toLong()) } ?: Duration.ofDays(task.recurrenceDays.toLong())
