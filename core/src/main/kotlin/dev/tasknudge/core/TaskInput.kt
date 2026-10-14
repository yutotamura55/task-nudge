package dev.tasknudge.core

data class TaskInput(val title: String, val recurrenceDays: Int)

fun validateTaskInput(title: String, recurrenceDays: Int = 1): TaskInput {
    val trimmedTitle = title.trim()
    require(trimmedTitle.length in 1..100) { "Title must contain 1 to 100 characters" }
    require(recurrenceDays in 1..365) { "Recurrence must be 1 to 365 days" }
    return TaskInput(trimmedTitle, recurrenceDays)
}

fun validateBulkTaskInput(text: String, recurrenceDays: Int = 1): List<TaskInput> {
    val titles = text.lines().map(String::trim).filter(String::isNotEmpty)
    require(titles.size in 1..100) { "Bulk input must contain 1 to 100 tasks" }
    return titles.map { validateTaskInput(it, recurrenceDays) }
}

fun validateSettings(settings: ReminderSettings): ReminderSettings {
    require(settings.startHour in 0..23) { "Start hour must be 0 to 23" }
    require(settings.endHour in 1..24) { "End hour must be 1 to 24" }
    require(settings.startHour < settings.endHour) { "Start must precede end" }
    require(settings.intervalMinutes in listOf(15, 30, 60, 120)) { "Unsupported notification interval" }
    return settings
}
