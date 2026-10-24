package dev.tasknudge.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** The screen shown by [TaskNudgeApp]; task IDs travel with the screens that need them. */
sealed interface Route {
    data object TaskList : Route
    data class Detail(val taskId: Long) : Route
    /** [taskId] is null when adding a task. */
    data class Editor(val taskId: Long?) : Route
    data object Bulk : Route
    data object Settings : Route

    val title: String
        get() = when (this) {
            TaskList -> "タスク"
            is Detail -> "タスクの詳細"
            is Editor -> if (taskId == null) "タスクを追加" else "タスクを編集"
            Bulk -> "まとめて追加"
            Settings -> "通知設定"
        }

    /** Editing an existing task returns to its detail; every other screen returns to the list. */
    val back: Route
        get() = if (this is Editor && taskId != null) Detail(taskId) else TaskList

    /** The task this screen cannot be shown without. */
    val requiredTaskId: Long?
        get() = when (this) {
            is Detail -> taskId
            is Editor -> taskId
            else -> null
        }

    companion object {
        val saver: Saver<Route, Any> = listSaver(
            save = { route ->
                when (route) {
                    TaskList -> listOf("list")
                    is Detail -> listOf("detail", route.taskId)
                    is Editor -> listOfNotNull("editor", route.taskId)
                    Bulk -> listOf("bulk")
                    Settings -> listOf("settings")
                }
            },
            restore = { saved ->
                val taskId = saved.getOrNull(1) as? Long
                when (saved.firstOrNull()) {
                    "detail" -> taskId?.let(::Detail) ?: TaskList
                    "editor" -> Editor(taskId)
                    "bulk" -> Bulk
                    "settings" -> Settings
                    else -> TaskList
                }
            },
        )
    }
}
