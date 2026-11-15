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

class RecurrenceWaitEndTest {
    // 12:00 on 2026-09-30 in Tokyo.
    private val now = Instant.parse("2026-09-30T03:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private val created = Instant.parse("2026-09-01T00:00:00Z")
    private val today = LocalDate.parse("2026-09-30")

    @Test fun noWaitIsLeftOnceTheRecurrenceAllowsTheTask() {
        val tasks = listOf(
            ReminderTask(1, created),
            ReminderTask(2, created, recurrenceHours = 2, lastCompletedAt = now.minus(Duration.ofHours(3))),
            ReminderTask(3, created, recurrenceHours = 2, lastCompletedAt = now.minus(Duration.ofHours(2))),
            ReminderTask(4, created, recurrenceDays = 2, lastCompletedDate = LocalDate.parse("2026-09-28")),
        )
        tasks.forEach { assertNull("task ${it.id}", it.recurrenceWaitEnd(now, zone)) }
    }

    @Test fun hourlyWaitEndsWhenTheIntervalHasPassedSinceTheLastCompletion() {
        val task = ReminderTask(
            1, created, recurrenceHours = 3,
            lastCompletedAt = now.minus(Duration.ofHours(1)), lastCompletedDate = today, completedToday = true,
        )
        assertEquals(now.plus(Duration.ofHours(2)), task.recurrenceWaitEnd(now, zone))
    }

    @Test fun dailyWaitEndsAtLocalMidnightOfTheNextDueDate() {
        val task = ReminderTask(1, created, recurrenceDays = 3, lastCompletedDate = LocalDate.parse("2026-09-29"))
        // 2026-10-02 00:00 in Tokyo.
        assertEquals(Instant.parse("2026-10-01T15:00:00Z"), task.recurrenceWaitEnd(now, zone))
    }

    @Test fun dailyTaskCompletedTodayWaitsForItsNextDueDateRatherThanTomorrow() {
        val everyThreeDays =
            ReminderTask(1, created, recurrenceDays = 3, lastCompletedDate = today, completedToday = true)
        // 2026-10-03 00:00 in Tokyo.
        assertEquals(Instant.parse("2026-10-02T15:00:00Z"), everyThreeDays.recurrenceWaitEnd(now, zone))
        val everyDay = everyThreeDays.copy(recurrenceDays = 1)
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), everyDay.recurrenceWaitEnd(now, zone))
    }

    @Test fun completionTodayWaitsForTomorrowEvenWhenTheLatestCompletionHasAnEarlierDate() {
        // After a time zone change, the latest completion can carry an earlier local date than today's.
        val task = ReminderTask(
            1, created, recurrenceDays = 1, lastCompletedDate = LocalDate.parse("2026-09-29"), completedToday = true,
        )
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), task.recurrenceWaitEnd(now, zone))
    }

    @Test fun snoozesAndArchivingLeaveTheWaitUnchanged() {
        val later = now.plus(Duration.ofHours(30))
        val hourly = ReminderTask(1, created, recurrenceHours = 2, lastCompletedAt = now, snoozedUntil = later)
        assertEquals(now.plus(Duration.ofHours(2)), hourly.recurrenceWaitEnd(now, zone))
        assertEquals(now.plus(Duration.ofHours(2)), hourly.copy(archived = true).recurrenceWaitEnd(now, zone))
        val daily = ReminderTask(2, created, recurrenceDays = 1, lastCompletedDate = LocalDate.parse("2026-09-29"))
        assertNull(daily.copy(snoozedUntil = later).recurrenceWaitEnd(now, zone))
    }

    @Test fun waitEndIsTheFirstInstantAtWhichAnUnsnoozedTaskIsDue() {
        // completedToday describes only today, so these tasks are not completed today.
        val tasks = listOf(
            ReminderTask(1, created, recurrenceHours = 3, lastCompletedAt = now.minus(Duration.ofHours(1))),
            ReminderTask(2, created, recurrenceDays = 3, lastCompletedDate = LocalDate.parse("2026-09-29")),
        )
        tasks.forEach { task ->
            val end = requireNotNull(task.recurrenceWaitEnd(now, zone))
            assertTrue("task ${task.id} at $end", task.isDue(end, end.atZone(zone).toLocalDate()))
            val before = end.minusMillis(1)
            assertFalse("task ${task.id} at $before", task.isDue(before, before.atZone(zone).toLocalDate()))
        }
    }
}
