package dev.tasknudge.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    private val now = Instant.parse("2026-09-30T03:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private val settings = ReminderSettings()

    @Test fun dueSnoozeWinsOverOlderNormalTask() {
        val tasks = listOf(
            ReminderTask(1, Instant.parse("2026-09-28T03:00:00Z")),
            ReminderTask(2, Instant.parse("2026-09-29T03:00:00Z"), snoozedUntil = now),
        )
        assertEquals(2L, select(tasks))
    }

    @Test fun oldestActualCompletionWinsOverCreationOrder() {
        val tasks = listOf(
            ReminderTask(1, Instant.parse("2026-01-01T00:00:00Z"),
                lastCompletedAt = Instant.parse("2026-09-28T03:00:00Z"),
                lastCompletedDate = LocalDate.parse("2026-09-28")),
            ReminderTask(2, Instant.parse("2026-08-01T00:00:00Z"),
                lastCompletedAt = Instant.parse("2026-09-27T03:00:00Z"),
                lastCompletedDate = LocalDate.parse("2026-09-27")),
        )
        assertEquals(2L, select(tasks))
    }

    @Test fun neverCompletedUsesCreationTimestampForOrdering() {
        val tasks = listOf(
            ReminderTask(1, Instant.parse("2026-09-28T00:00:00Z"),
                lastCompletedAt = Instant.parse("2026-09-29T00:00:00Z"),
                lastCompletedDate = LocalDate.parse("2026-09-29")),
            ReminderTask(2, Instant.parse("2026-09-27T00:00:00Z")),
        )
        assertEquals(2L, select(tasks))
    }

    @Test fun equalCompletionTimesUseCreationOrder() {
        val completedAt = Instant.parse("2026-09-29T00:00:00Z")
        val date = LocalDate.parse("2026-09-29")
        val tasks = listOf(
            ReminderTask(1, Instant.parse("2026-09-28T00:00:00Z"), lastCompletedAt = completedAt, lastCompletedDate = date),
            ReminderTask(2, Instant.parse("2026-09-27T00:00:00Z"), lastCompletedAt = completedAt, lastCompletedDate = date),
        )
        assertEquals(2L, select(tasks))
    }

    @Test fun equalTimestampsUseLowestIdRegardlessOfInputOrder() {
        assertEquals(2L, select(listOf(ReminderTask(9, now), ReminderTask(2, now))))
    }

    @Test fun dueSnoozesUseCompletionOrderRatherThanSnoozeDeadline() {
        val tasks = listOf(
            ReminderTask(1, Instant.parse("2026-09-29T00:00:00Z"), snoozedUntil = now.minusSeconds(7200)),
            ReminderTask(2, Instant.parse("2026-09-28T00:00:00Z"), snoozedUntil = now),
        )
        assertEquals(2L, select(tasks))
    }

    @Test fun completedTodayExcludesTaskEvenWhenLastCompletionDateIsOlder() {
        assertEquals(2L, select(listOf(ReminderTask(1, now.minusSeconds(86400), completedToday = true), ReminderTask(2, now))))
    }

    @Test fun archivedTaskIsExcluded() {
        assertEquals(2L, select(listOf(ReminderTask(1, now.minusSeconds(86400), archived = true), ReminderTask(2, now))))
    }

    @Test fun futureSnoozeAllowsAnotherCandidate() {
        assertEquals(2L, select(listOf(ReminderTask(1, now.minusSeconds(86400), snoozedUntil = now.plusSeconds(1)), ReminderTask(2, now))))
    }

    @Test fun dailyRecurrenceWaitsUntilNextLocalDate() {
        val task = ReminderTask(1, now.minusSeconds(86400), lastCompletedAt = now.minusSeconds(3600), lastCompletedDate = LocalDate.parse("2026-09-30"))
        assertNull(select(listOf(task)))
        assertEquals(1L, select(listOf(task), Instant.parse("2026-10-01T00:00:00Z")))
    }

    @Test fun hourlyRecurrenceReturnsAfterCompletionIntervalOnSameDay() {
        val completed = now.minusSeconds(2 * 3600)
        val task = ReminderTask(1, now.minusSeconds(86400), recurrenceHours = 3,
            lastCompletedAt = completed, lastCompletedDate = LocalDate.parse("2026-09-30"), completedToday = true)
        assertNull(select(listOf(task)))
        assertEquals(1L, select(listOf(task), completed.plusSeconds(3 * 3600)))
    }

    @Test fun hourlyRecurrenceUsesElapsedTimeAcrossLocalMidnight() {
        val completed = Instant.parse("2026-09-29T14:30:00Z")
        val task = ReminderTask(1, completed.minusSeconds(3600), recurrenceHours = 2,
            lastCompletedAt = completed, lastCompletedDate = LocalDate.parse("2026-09-29"))
        val allDay = settings.copy(startHour = 0, endHour = 24)
        assertNull(ReminderPolicy.select(listOf(task), Instant.parse("2026-09-29T15:00:00Z"), zone, allDay, null))
        assertEquals(1L, ReminderPolicy.select(listOf(task), Instant.parse("2026-09-29T16:30:00Z"), zone, allDay, null))
    }

    @Test fun yearlyRecurrenceRequires365CalendarDays() {
        val task = ReminderTask(1, Instant.parse("2025-09-30T00:00:00Z"), recurrenceDays = 365,
            lastCompletedAt = Instant.parse("2025-09-30T00:00:00Z"), lastCompletedDate = LocalDate.parse("2025-09-30"))
        assertNull(select(listOf(task), Instant.parse("2026-09-29T03:00:00Z")))
        assertEquals(1L, select(listOf(task)))
    }

    @Test fun longAbsenceReturnsOneTaskWithNoBacklog() {
        val task = ReminderTask(1, Instant.parse("2020-01-01T00:00:00Z"),
            lastCompletedAt = Instant.parse("2020-01-01T00:00:00Z"), lastCompletedDate = LocalDate.parse("2020-01-01"))
        assertEquals(1L, select(listOf(task)))
        assertNull(select(listOf(task.copy(lastCompletedAt = now, lastCompletedDate = LocalDate.parse("2026-09-30"), completedToday = true))))
    }

    @Test fun neverCompletedTaskIsDueImmediatelyRegardlessOfRecurrence() {
        assertEquals(1L, select(listOf(ReminderTask(1, now, recurrenceDays = 365))))
    }

    @Test fun futurePersistedLocalDateAfterTravelWaitsUntilRecurrenceDate() {
        val task = ReminderTask(1, now.minusSeconds(86400), lastCompletedAt = now.minusSeconds(3600), lastCompletedDate = LocalDate.parse("2026-10-01"))
        assertNull(select(listOf(task)))
        assertNull(select(listOf(task), Instant.parse("2026-10-01T03:00:00Z")))
        assertEquals(1L, select(listOf(task), Instant.parse("2026-10-02T03:00:00Z")))
    }

    @Test fun springDstUsesCalendarDayEvenWhenOnly23HoursElapsed() {
        val task = ReminderTask(1, Instant.parse("2026-03-07T17:00:00Z"),
            lastCompletedAt = Instant.parse("2026-03-07T17:00:00Z"), lastCompletedDate = LocalDate.parse("2026-03-07"))
        assertEquals(1L, ReminderPolicy.select(listOf(task), Instant.parse("2026-03-08T16:00:00Z"), ZoneId.of("America/New_York"), settings, null))
    }

    @Test fun fallDstUsesCalendarDayEvenWhen25HoursElapsed() {
        val task = ReminderTask(1, Instant.parse("2026-10-31T16:00:00Z"),
            lastCompletedAt = Instant.parse("2026-10-31T16:00:00Z"), lastCompletedDate = LocalDate.parse("2026-10-31"))
        assertEquals(1L, ReminderPolicy.select(listOf(task), Instant.parse("2026-11-01T17:00:00Z"), ZoneId.of("America/New_York"), settings, null))
    }

    @Test fun notificationHoursIncludeStartAndExcludeEnd() {
        assertFalse(ReminderPolicy.isWithinHours(Instant.parse("2026-09-29T23:59:00Z"), zone, settings))
        assertTrue(ReminderPolicy.isWithinHours(Instant.parse("2026-09-30T00:00:00Z"), zone, settings))
        assertTrue(ReminderPolicy.isWithinHours(Instant.parse("2026-09-30T11:59:00Z"), zone, settings))
        assertFalse(ReminderPolicy.isWithinHours(Instant.parse("2026-09-30T12:00:00Z"), zone, settings))
    }

    @Test fun allDayHoursAllowMidnightAndLastSecondOfDate() {
        val allDay = ReminderSettings(startHour = 0, endHour = 24)
        assertTrue(ReminderPolicy.isWithinHours(Instant.parse("2026-09-29T15:00:00Z"), zone, allDay))
        assertTrue(ReminderPolicy.isWithinHours(Instant.parse("2026-09-30T14:59:59Z"), zone, allDay))
    }

    @Test fun outsideHoursBlocksDueSnooze() {
        assertNull(select(listOf(ReminderTask(1, now, snoozedUntil = now)), Instant.parse("2026-09-30T12:00:00Z")))
    }

    @Test fun globalCadenceIncludesExactThirtyMinuteBoundary() {
        val tasks = listOf(ReminderTask(1, now))
        assertNull(ReminderPolicy.select(tasks, now, zone, settings, now.minusSeconds(1799)))
        assertEquals(1L, ReminderPolicy.select(tasks, now, zone, settings, now.minusSeconds(1800)))
    }

    @Test fun fifteenMinuteSettingsRespectTheGlobalIntervalBoundary() {
        val tasks = listOf(ReminderTask(1, now.minusSeconds(86400)))
        val configured = settings.copy(intervalMinutes = 15)
        assertNull(ReminderPolicy.select(tasks, now, zone, configured, now.minusSeconds(899)))
        assertEquals(1L, ReminderPolicy.select(tasks, now, zone, configured, now.minusSeconds(900)))
    }

    @Test fun sixtyAnd120MinuteSettingsChangeGlobalCadence() {
        for ((minutes, tooRecent, ready) in listOf(Triple(60, 3599L, 3600L), Triple(120, 7199L, 7200L))) {
            val configured = settings.copy(intervalMinutes = minutes)
            val tasks = listOf(ReminderTask(1, now))
            assertNull(ReminderPolicy.select(tasks, now, zone, configured, now.minusSeconds(tooRecent)))
            assertEquals(1L, ReminderPolicy.select(tasks, now, zone, configured, now.minusSeconds(ready)))
        }
    }

    @Test fun dueSnoozeStillRespectsGlobalCadence() {
        assertNull(ReminderPolicy.select(listOf(ReminderTask(1, now, snoozedUntil = now)), now, zone, settings, now.minusSeconds(1799)))
    }

    @Test fun futureDeliveryTimestampDoesNotPermitNewNotification() {
        assertNull(ReminderPolicy.select(listOf(ReminderTask(1, now)), now, zone, settings, now.plusSeconds(3600)))
    }

    @Test fun noEligibleCandidateReturnsNull() {
        assertNull(select(emptyList()))
        assertNull(select(listOf(ReminderTask(1, now, archived = true))))
    }

    private fun select(tasks: List<ReminderTask>, at: Instant = now): Long? =
        ReminderPolicy.select(tasks, at, zone, settings, null)
}
