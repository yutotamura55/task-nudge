package dev.tasknudge.core

import java.time.Instant
import java.time.LocalDate

/** Everything a backup file carries. Task IDs are renumbered on import; device state is not included. */
data class Backup(val settings: ReminderSettings, val tasks: List<BackupTask>)

data class BackupTask(
    val title: String,
    val recurrenceDays: Int,
    val recurrenceHours: Int?,
    val createdAt: Instant,
    val archived: Boolean,
    val snoozedUntil: Instant?,
    val completions: List<BackupCompletion>,
)

data class BackupCompletion(val completedAt: Instant, val localDate: LocalDate)

const val MAX_BACKUP_TASKS = 10_000
val backupInstantRange: ClosedRange<Instant> = Instant.EPOCH..Instant.parse("9999-12-31T23:59:59.999Z")

/** Why a backup file was rejected; the UI chooses the wording. */
enum class BackupProblem {
    NOT_A_BACKUP, NEWER_VERSION, TOO_LARGE, MALFORMED,
    SETTINGS, TOO_MANY_TASKS, TITLE, RECURRENCE, DATE_TIME, DUPLICATE_COMPLETION,
}

/**
 * [taskNumber] counts from 1 so it can be shown as is. The problem never carries the
 * file's content, so it is safe to show and to log.
 */
class InvalidBackupException(val problem: BackupProblem, val taskNumber: Int? = null, cause: Throwable? = null) :
    IllegalArgumentException(if (taskNumber == null) problem.name else "${problem.name} in task $taskNumber", cause)

/**
 * Applies the rules the screens enforce on input, so an edited file cannot store
 * what the app itself would refuse. Titles must already be trimmed.
 */
fun validateBackup(backup: Backup): Backup {
    fun check(valid: Boolean, problem: BackupProblem, taskNumber: Int? = null) {
        if (!valid) throw InvalidBackupException(problem, taskNumber)
    }
    check(settingsError(backup.settings) == null, BackupProblem.SETTINGS)
    check(backup.tasks.size <= MAX_BACKUP_TASKS, BackupProblem.TOO_MANY_TASKS)
    backup.tasks.forEachIndexed { index, task ->
        val number = index + 1
        check(titleError(task.title) == null && task.title == task.title.trim(), BackupProblem.TITLE, number)
        check(recurrenceError(task.recurrenceDays) == null, BackupProblem.RECURRENCE, number)
        val hoursValid = task.recurrenceHours == null || recurrenceError(task.recurrenceHours, hourly = true) == null
        check(hoursValid, BackupProblem.RECURRENCE, number)
        val instants = listOfNotNull(task.createdAt, task.snoozedUntil) + task.completions.map { it.completedAt }
        check(instants.all { it in backupInstantRange }, BackupProblem.DATE_TIME, number)
        // The database keeps one record per task, date and time.
        check(task.completions.distinct().size == task.completions.size, BackupProblem.DUPLICATE_COMPLETION, number)
    }
    return backup
}
