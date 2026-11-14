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
    @Test fun titleSearchUsesPartialCaseInsensitiveMatchesAndIgnoresOuterWhitespace() {
        val tasks = listOf(task.copy(id = 1, title = "Morning Stretch"), task.copy(id = 2, title = "肩のストレッチ"), task.copy(id = 3, title = "読書"))
        assertEquals(listOf(1L), visibleTasks(tasks, false, "  STRETCH  ").map { it.id })
        assertEquals(listOf(2L), visibleTasks(tasks, false, "ストレッチ").map { it.id })
        assertEquals(tasks, visibleTasks(tasks, false, "  "))
    }
    @Test fun titleSearchRespectsArchivedVisibility() {
        val stopped = task.copy(id = 2, title = "肩のストレッチ", archived = true)
        val tasks = listOf(task.copy(title = "首のストレッチ"), stopped)
        assertEquals(listOf(1L), visibleTasks(tasks, false, "ストレッチ").map { it.id })
        assertEquals(listOf(1L, 2L), visibleTasks(tasks, true, "ストレッチ").map { it.id })
    }
    @Test fun dailyTaskCanBeCompletedOnTheNextDateBeforeItsRecurrenceIsDue() {
        val yesterday = task.copy(completions = listOf(record("2026-09-30")))
        assertTrue(taskCanComplete(yesterday, now, zone))
        assertEquals("次回対象日 2026-10-03", taskStatus(yesterday, now, zone))
        val today = task.copy(completions = listOf(record("2026-10-01")))
        assertFalse(taskCanComplete(today, now, zone))
        assertFalse(taskCanComplete(today.copy(archived = true), now, zone))
        assertEquals("実行できます", taskStatus(task.copy(completions = listOf(record("2026-09-28"))), now, zone))
    }
    @Test fun hourlyTaskBecomesReadyExactlyAtItsInterval() {
        val completed = CompletionRecord(1, now.minus(Duration.ofHours(2)), LocalDate.parse("2026-10-01"))
        val hourly = task.copy(recurrenceHours = 2, completions = listOf(completed))
        assertTrue(taskCanComplete(hourly, now, zone))
        assertEquals("実行できます", taskStatus(hourly, now, zone))
        assertFalse(taskCanComplete(hourly, now.minusSeconds(1), zone))
    }
    @Test fun snoozeEndsExactlyAtItsDeadline() {
        assertEquals("実行できます", taskStatus(task.copy(snoozedUntil = now), now, zone))
        assertTrue(taskCanComplete(task.copy(snoozedUntil = now.plusSeconds(3600)), now, zone))
    }
    // Each sort test expects an order that differs from the order the tasks are listed in,
    // so a sort that leaves the list as it is fails.
    @Test fun registrationOrderUsesCreationTimeThenId() {
        val tasks = listOf(
            task.copy(id = 1, createdAt = at(1)),
            task.copy(id = 2, createdAt = at(1)),
            task.copy(id = 3, createdAt = at(0)),
        )
        assertEquals(listOf(3L, 1L, 2L), sortTasks(tasks, TaskSortOrder.REGISTERED).map { it.id })
    }
    @Test fun recurrenceSortComparesHourlyAndDailyIntervalsAsDurations() {
        val tasks = listOf(
            task.copy(id = 1, createdAt = at(0), recurrenceDays = 2),
            task.copy(id = 2, createdAt = at(1), recurrenceHours = 23),
            task.copy(id = 3, createdAt = at(2), recurrenceDays = 1),
            task.copy(id = 4, createdAt = at(3), recurrenceHours = 1),
            task.copy(id = 5, createdAt = at(4), recurrenceHours = 24),
        )
        // 24 hours and 1 day tie, so registration order decides between tasks 3 and 5.
        val shortestFirst = sortTasks(tasks, TaskSortOrder.RECURRENCE_SHORTEST)
        assertEquals(listOf(4L, 2L, 3L, 5L, 1L), shortestFirst.map { it.id })
    }
    @Test fun newestRegistrationComesFirst() {
        val tasks = listOf(
            task.copy(id = 1, createdAt = at(0)),
            task.copy(id = 2, createdAt = at(1)),
            task.copy(id = 3, createdAt = at(1)),
        )
        val newestFirst = sortTasks(tasks, TaskSortOrder.REGISTERED_NEWEST)
        assertEquals(listOf(3L, 2L, 1L), newestFirst.map { it.id })
    }
    @Test fun completionCountSortPutsTheMostCompletedFirstAndKeepsRegistrationOrderForTies() {
        val tasks = listOf(
            task.copy(id = 1, createdAt = at(0), completions = listOf(record("2026-09-30"))),
            task.copy(id = 2, createdAt = at(1), completions = listOf(record("2026-09-29"), record("2026-09-30"))),
            task.copy(id = 3, createdAt = at(2), completions = listOf(record("2026-09-30"))),
            task.copy(id = 4, createdAt = at(3), completions = emptyList()),
        )
        assertEquals(listOf(2L, 1L, 3L, 4L), sortTasks(tasks, TaskSortOrder.COMPLETIONS_MOST).map { it.id })
    }
    @Test fun sortMenuStartsWithRegistrationOrder() {
        assertEquals(TaskSortOrder.REGISTERED, TaskSortOrder.entries.first())
        assertEquals("登録順", TaskSortOrder.REGISTERED.label)
    }
    @Test fun savedSortOrderRestoresByNameAndFallsBackToRegistrationOrder() {
        assertEquals(TaskSortOrder.COMPLETIONS_MOST, TaskSortOrder.saver.restore("COMPLETIONS_MOST"))
        assertEquals(TaskSortOrder.REGISTERED, TaskSortOrder.saver.restore("REMOVED_ORDER"))
    }
    private fun at(seconds: Long) = Instant.EPOCH.plusSeconds(seconds)
    private fun record(day: String) = CompletionRecord(1, Instant.EPOCH, LocalDate.parse(day))
}
