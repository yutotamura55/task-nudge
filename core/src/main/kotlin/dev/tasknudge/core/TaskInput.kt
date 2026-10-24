package dev.tasknudge.core

data class TaskInput(val title: String, val recurrenceDays: Int)

val titleLengthRange = 1..100
val dailyRecurrenceRange = 1..365
val hourlyRecurrenceRange = 1..24
const val maxBulkTasks = 100
val startHourRange = 0..23
val endHourRange = 1..24
val notificationIntervalOptions = listOf(5, 10, 15, 30, 60, 120)

/** Why user input was rejected; the UI chooses the wording. */
enum class InputError { TITLE_LENGTH, DAILY_RECURRENCE, HOURLY_RECURRENCE, BULK_EMPTY, BULK_TOO_MANY, BULK_TITLE_LENGTH, SETTINGS }

fun titleError(title: String): InputError? =
    if (title.trim().length in titleLengthRange) null else InputError.TITLE_LENGTH

/** [recurrence] is null when the entered text is not an integer. */
fun recurrenceError(recurrence: Int?, hourly: Boolean = false): InputError? = when {
    hourly -> if (recurrence != null && recurrence in hourlyRecurrenceRange) null else InputError.HOURLY_RECURRENCE
    else -> if (recurrence != null && recurrence in dailyRecurrenceRange) null else InputError.DAILY_RECURRENCE
}

/** One task per non-blank line, without surrounding whitespace. */
fun bulkTitles(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)

fun bulkTitlesError(titles: List<String>): InputError? = when {
    titles.isEmpty() -> InputError.BULK_EMPTY
    titles.size > maxBulkTasks -> InputError.BULK_TOO_MANY
    titles.any { titleError(it) != null } -> InputError.BULK_TITLE_LENGTH
    else -> null
}

fun settingsError(settings: ReminderSettings): InputError? =
    if (settings.startHour in startHourRange && settings.endHour in endHourRange &&
        settings.startHour < settings.endHour && settings.intervalMinutes in notificationIntervalOptions
    ) null else InputError.SETTINGS

fun validateTaskInput(title: String, recurrenceDays: Int = 1): TaskInput {
    require(titleError(title) == null) { "Title must contain $titleLengthRange characters" }
    require(recurrenceError(recurrenceDays) == null) { "Recurrence must be $dailyRecurrenceRange days" }
    return TaskInput(title.trim(), recurrenceDays)
}

fun validateBulkTaskInput(text: String, recurrenceDays: Int = 1): List<TaskInput> {
    val titles = bulkTitles(text)
    require(titles.isNotEmpty() && titles.size <= maxBulkTasks) { "Bulk input must contain 1 to $maxBulkTasks tasks" }
    return titles.map { validateTaskInput(it, recurrenceDays) }
}

fun validateSettings(settings: ReminderSettings): ReminderSettings {
    require(settingsError(settings) == null) { "Unsupported reminder settings: $settings" }
    return settings
}
