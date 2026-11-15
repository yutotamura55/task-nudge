package dev.tasknudge.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextDueAtTest {
    // 12:00 on 2026-09-30 in Tokyo.
    private val now = Instant.parse("2026-09-30T03:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private val created = Instant.parse("2026-09-01T00:00:00Z")
    private val today = LocalDate.parse("2026-09-30")

    @Test fun archivedTaskNeverBecomesDue() {
        assertNull(ReminderTask(1, created, archived = true).nextDueAt(now, zone))
    }

    @Test fun tasksThatAreDueAlreadyAreDueNow() {
        val due = listOf(
            ReminderTask(1, created),
            ReminderTask(2, created, recurrenceHours = 2, lastCompletedAt = now.minus(Duration.ofHours(3))),
            ReminderTask(3, created, recurrenceDays = 2, lastCompletedDate = LocalDate.parse("2026-09-27")),
            ReminderTask(4, created, snoozedUntil = now.minusSeconds(1)),
        )
        due.forEach { assertEquals("task ${it.id}", now, it.nextDueAt(now, zone)) }
    }

    @Test fun hourlyTaskIsDueWhenItsIntervalHasPassedSinceTheLastCompletion() {
        val task = ReminderTask(
            1, created, recurrenceHours = 3,
            lastCompletedAt = now.minus(Duration.ofHours(1)), lastCompletedDate = today, completedToday = true,
        )
        assertEquals(now.plus(Duration.ofHours(2)), task.nextDueAt(now, zone))
    }

    @Test fun dailyTaskIsDueAtLocalMidnightOfItsNextDueDate() {
        val task = ReminderTask(1, created, recurrenceDays = 3, lastCompletedDate = LocalDate.parse("2026-09-29"))
        // 2026-10-02 00:00 in Tokyo.
        assertEquals(Instant.parse("2026-10-01T15:00:00Z"), task.nextDueAt(now, zone))
    }

    @Test fun dailyTaskCompletedTodayIsDueOnItsNextDueDateRatherThanTomorrow() {
        val everyThreeDays =
            ReminderTask(1, created, recurrenceDays = 3, lastCompletedDate = today, completedToday = true)
        // 2026-10-03 00:00 in Tokyo.
        assertEquals(Instant.parse("2026-10-02T15:00:00Z"), everyThreeDays.nextDueAt(now, zone))
        val everyDay = everyThreeDays.copy(recurrenceDays = 1)
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), everyDay.nextDueAt(now, zone))
    }

    @Test fun completionTodayWaitsForTomorrowEvenWhenTheLatestCompletionHasAnEarlierDate() {
        // After a time zone change, the latest completion can carry an earlier local date than today's.
        val task = ReminderTask(
            1, created, recurrenceDays = 1, lastCompletedDate = LocalDate.parse("2026-09-29"), completedToday = true,
        )
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), task.nextDueAt(now, zone))
    }

    @Test fun snoozeDelaysTheNextDueTimeOnlyWhenItEndsLater() {
        val snoozed = ReminderTask(1, created, snoozedUntil = now.plus(Duration.ofHours(5)))
        assertEquals(now.plus(Duration.ofHours(5)), snoozed.nextDueAt(now, zone))
        val dueDateLater = ReminderTask(
            2, created, recurrenceDays = 3, lastCompletedDate = LocalDate.parse("2026-09-29"),
            snoozedUntil = now.plus(Duration.ofHours(1)),
        )
        assertEquals(Instant.parse("2026-10-01T15:00:00Z"), dueDateLater.nextDueAt(now, zone))
        val hourlySnoozedLonger = ReminderTask(
            3, created, recurrenceHours = 1, lastCompletedAt = now, snoozedUntil = now.plus(Duration.ofHours(4)),
        )
        assertEquals(now.plus(Duration.ofHours(4)), hourlySnoozedLonger.nextDueAt(now, zone))
    }

    @Test fun nextDueTimeIsTheFirstInstantAtWhichTheTaskIsDue() {
        // completedToday describes only today, so these tasks are not completed today.
        val tasks = listOf(
            ReminderTask(1, created, recurrenceHours = 3, lastCompletedAt = now.minus(Duration.ofHours(1))),
            ReminderTask(2, created, recurrenceDays = 3, lastCompletedDate = LocalDate.parse("2026-09-29")),
            ReminderTask(3, created, snoozedUntil = now.plus(Duration.ofHours(5))),
        )
        tasks.forEach { task ->
            val dueAt = requireNotNull(task.nextDueAt(now, zone))
            assertTrue("task ${task.id} at $dueAt", task.isDue(dueAt, dueAt.atZone(zone).toLocalDate()))
            val before = dueAt.minusMillis(1)
            assertFalse("task ${task.id} at $before", task.isDue(before, before.atZone(zone).toLocalDate()))
        }
    }
}
