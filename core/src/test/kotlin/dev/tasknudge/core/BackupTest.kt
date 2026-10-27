package dev.tasknudge.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackupTest {
    private val completion = BackupCompletion(Instant.parse("2026-10-01T01:00:00Z"), LocalDate.of(2026, 10, 1))
    private val task = BackupTask("首", 1, null, Instant.parse("2026-09-30T00:00:00Z"), false, null, listOf(completion))
    private val backup = Backup(ReminderSettings(), listOf(task))
    private fun completedOn(date: LocalDate) = task.copy(completions = listOf(completion.copy(localDate = date)))

    @Test fun acceptsWhatTheScreensAccept() {
        assertSame(backup, validateBackup(backup))
        val longest = task.copy(title = "長".repeat(100), recurrenceDays = 365, recurrenceHours = 24)
        validateBackup(backup.copy(tasks = listOf(longest)))
        validateBackup(Backup(ReminderSettings(0, 24, 5), emptyList()))
    }

    @Test fun rejectsValuesTheScreensRefuseAndNamesTheTask() {
        val second = { changed: BackupTask -> backup.copy(tasks = listOf(task, changed)) }
        for ((invalid, problem) in listOf(
            second(task.copy(title = "")) to BackupProblem.TITLE,
            second(task.copy(title = " 首")) to BackupProblem.TITLE,
            second(task.copy(title = "長".repeat(101))) to BackupProblem.TITLE,
            second(task.copy(recurrenceDays = 0)) to BackupProblem.RECURRENCE,
            second(task.copy(recurrenceDays = 366)) to BackupProblem.RECURRENCE,
            second(task.copy(recurrenceHours = 25)) to BackupProblem.RECURRENCE,
            second(task.copy(createdAt = Instant.EPOCH.minusMillis(1))) to BackupProblem.DATE_TIME,
            second(task.copy(snoozedUntil = Instant.parse("+10000-01-01T00:00:00Z"))) to BackupProblem.DATE_TIME,
            second(task.copy(completions = listOf(completion.copy(completedAt = Instant.MIN))))
                to BackupProblem.DATE_TIME,
            second(task.copy(completions = listOf(completion, completion))) to BackupProblem.DUPLICATE_COMPLETION,
            second(task.copy(createdAt = Instant.parse("2026-09-30T00:00:00.000100Z"))) to BackupProblem.DATE_TIME,
            second(completedOn(LocalDate.MAX)) to BackupProblem.DATE_TIME,
            second(completedOn(LocalDate.of(2026, 10, 3))) to BackupProblem.DATE_TIME,
            second(completedOn(LocalDate.of(2026, 9, 29))) to BackupProblem.DATE_TIME,
        )) {
            val error = runCatching { validateBackup(invalid) }.exceptionOrNull() as InvalidBackupException
            assertEquals(problem, error.problem)
            assertEquals(2, error.taskNumber)
        }
    }

    @Test fun rejectsSettingsAndTaskCountWithoutATaskNumber() {
        for ((invalid, problem) in listOf(
            backup.copy(settings = ReminderSettings(21, 9, 30)) to BackupProblem.SETTINGS,
            backup.copy(settings = ReminderSettings(9, 21, 7)) to BackupProblem.SETTINGS,
            backup.copy(tasks = List(MAX_BACKUP_TASKS + 1) { task }) to BackupProblem.TOO_MANY_TASKS,
        )) {
            val error = runCatching { validateBackup(invalid) }.exceptionOrNull() as InvalidBackupException
            assertEquals(problem, error.problem)
            assertEquals(null, error.taskNumber)
        }
    }

    @Test fun completionDatesMayDifferFromUtcByTheTimeZone() {
        // One day either way of the UTC date covers every time zone.
        val sameTime = completion.copy(localDate = LocalDate.of(2026, 9, 30))
        val nextDay = completion.copy(localDate = LocalDate.of(2026, 10, 2))
        validateBackup(backup.copy(tasks = listOf(task.copy(completions = listOf(completion, sameTime, nextDay)))))
    }

    @Test fun completionsAtTheSameTimeOnDifferentDatesAreDistinct() {
        val sameTime = completion.copy(localDate = LocalDate.of(2026, 9, 30))
        validateBackup(backup.copy(tasks = listOf(task.copy(completions = listOf(completion, sameTime)))))
    }
}
