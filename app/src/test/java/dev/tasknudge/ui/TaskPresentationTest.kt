package dev.tasknudge.ui

import dev.tasknudge.data.CompletionRecord
import dev.tasknudge.data.TaskSnapshot
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class TaskPresentationTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private val task = TaskSnapshot(1, "首", 3, false, Instant.EPOCH, null, emptyList())
    @Test fun neverCompletedTaskIsReady() { assertEquals("実行できます", taskStatus(task, now, zone)) }
    @Test fun completionUsesSavedDayAndFutureDatesWaitForRecurrence() {
        val today = task.copy(completions = listOf(record("2026-10-01")))
        assertEquals("今日完了", taskStatus(today, now, zone))
        val later = task.copy(completions = listOf(record("2026-10-05")))
        assertEquals("次回対象日 2026-10-08", taskStatus(later, now, zone))
    }
    @Test fun stoppedAndSnoozedAreLabeledWithoutColor() {
        assertEquals("停止中", taskStatus(task.copy(archived = true), now, zone))
        assertEquals("保留中・2026-10-01 20:00まで", taskStatus(task.copy(snoozedUntil = now.plusSeconds(3600)), now, zone))
    }
    @Test fun hourlyTaskShowsNextTimeAndAllowsCompletionAfterInterval() {
        val completed = CompletionRecord(1, now.minus(Duration.ofHours(1)), LocalDate.parse("2026-10-01"))
        val hourly = task.copy(recurrenceDays = 1, recurrenceHours = 2, completions = listOf(completed))
        assertEquals("次回対象 2026-10-01 20:00", taskStatus(hourly, now, zone))
        assertFalse(taskCanComplete(hourly, now, zone))
        assertTrue(taskCanComplete(hourly, now.plus(Duration.ofHours(1)), zone))
    }
    @Test fun permissionOnlyRequestsOnExplicitFirstEnableWithMissingApi33Permission() {
        assertEquals(NotificationEnableAction.REQUEST, notificationEnableAction(33, false, false))
        assertEquals(NotificationEnableAction.SETTINGS, notificationEnableAction(33, false, true))
        assertEquals(NotificationEnableAction.SETTINGS, notificationEnableAction(33, true, false))
        assertEquals(NotificationEnableAction.SETTINGS, notificationEnableAction(32, false, false))
    }
    private fun record(day: String) = CompletionRecord(1, Instant.EPOCH, LocalDate.parse(day))
}
