package dev.tasknudge.reminders

import android.app.Application
import androidx.room.Room
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.TaskInput
import dev.tasknudge.data.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReminderEngineTest {
    private lateinit var db: TaskDatabase
    private val clock = MutableClock()
    private var zone = ZoneId.of("Asia/Tokyo")
    private val gateway = FakeGateway()
    private val scheduler = FakeScheduler()
    private lateinit var engine: ReminderEngine
    private val repo get() = engine.repository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).addCallback(TaskDatabase.initializeDefaults).allowMainThreadQueries().build()
        engine = newEngine()
    }
    private fun newEngine() = ReminderEngine(db, clock, { zone }, gateway, scheduler)
    @After fun close() { db.close() }
    @Test fun bulkSaveCommitsEveryTaskWithOneReminderRefresh() = runTest {
        val ids = repo.saveTasks(listOf(TaskInput("首", 3), TaskInput("腕", 3)))
        assertEquals(2, ids.size)
        assertEquals(listOf("首", "腕"), repo.snapshots.first().tasks.map { it.title })
        assertEquals(listOf(3, 3), repo.snapshots.first().tasks.map { it.recurrenceDays })
        assertEquals(1, scheduler.requests)
    }
    @Test fun bulkSaveRollsBackWhenAnInsertFails() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_arm BEFORE INSERT ON tasks WHEN NEW.title = '腕' BEGIN SELECT RAISE(ABORT, 'rejected'); END")
        try { repo.saveTasks(listOf(TaskInput("首", 1), TaskInput("腕", 1))); fail("Expected insert failure") }
        catch (_: Exception) { }
        assertTrue(repo.snapshots.first().tasks.isEmpty())
        assertEquals(0, scheduler.requests)
    }
    @Test fun concurrentEvaluationsPostOneReminder() = runTest {
        repo.saveTask(null, "首")
        coroutineScope { repeat(10) { launch(Dispatchers.Default) { engine.evaluate() } } }
        assertEquals(1, gateway.posts)
        assertNotNull(db.dao().activeReminders().single().deliveredAt)
    }
    @Test fun ignoredReminderDoesNotBlockLaterIntervals() = runTest {
        repo.saveSettings(ReminderSettings(0, 24, 15))
        repo.saveTask(null, "首")
        repeat(4) {
            engine.evaluate()
            clock.advance(Duration.ofMinutes(15))
        }
        assertEquals(4, gateway.posts)
    }
    @Test fun unnotifiedTaskGetsNextIntervalBeforeRepeatingFirst() = runTest {
        repo.saveSettings(ReminderSettings(0, 24, 15))
        val first = repo.saveTask(null, "首")
        val second = repo.saveTask(null, "腕")
        engine.evaluate()
        assertEquals(first, gateway.taskId)
        clock.advance(Duration.ofMinutes(15))
        engine.evaluate()
        assertEquals(second, gateway.taskId)
    }
    @Test fun evaluationSchedulesTheNextNotificationOpportunity() = runTest {
        repo.saveSettings(ReminderSettings(9, 21, 15))
        repo.saveTask(null, "首")
        engine.evaluate()
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), scheduler.nextAlarm)
        clock.advance(Duration.ofHours(12))
        engine.evaluate()
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), scheduler.nextAlarm)
    }
    @Test fun oneDelayedEvaluationDoesNotBackfillMissedIntervals() = runTest {
        repo.saveSettings(ReminderSettings(0, 24, 15))
        repo.saveTask(null, "首")
        engine.evaluate()
        clock.advance(Duration.ofHours(3))
        engine.evaluate()
        assertEquals(2, gateway.posts)
    }
    @Test fun completingOneOfSeveralRemindersClearsOnlyThatTask() = runTest {
        repo.saveSettings(ReminderSettings(0, 24, 15))
        val first = repo.saveTask(null, "首")
        val second = repo.saveTask(null, "腕")
        engine.evaluate()
        val firstToken = gateway.visibleToken!!
        clock.advance(Duration.ofMinutes(15)); engine.evaluate()
        val secondToken = gateway.visibleToken!!
        clock.advance(Duration.ofMinutes(15)); engine.evaluate()
        assertEquals(3, gateway.activeCount)
        engine.handleAction(first, firstToken, ReminderAction.COMPLETE)
        assertEquals(1, gateway.activeCount)
        assertEquals(secondToken, gateway.visibleToken)
        assertEquals(1, repo.snapshots.first().tasks.first { it.id == first }.completions.size)
        assertTrue(repo.snapshots.first().tasks.first { it.id == second }.completions.isEmpty())
    }
    @Test fun concurrentCompletionsRecordOneDay() = runTest {
        val id = repo.saveTask(null, "首")
        coroutineScope { repeat(10) { launch(Dispatchers.Default) { repo.completeTask(id) } } }
        assertEquals(1, repo.snapshots.first().tasks.single().completions.size)
    }
    @Test fun hourlyTaskCanNotifyAgainAfterCompletionCooldown() = runTest {
        repo.saveSettings(ReminderSettings(0, 24, 15))
        val id = repo.saveTask(null, "水を飲む", recurrenceHours = 2)
        engine.evaluate()
        val firstToken = gateway.visibleToken!!
        engine.handleAction(id, firstToken, ReminderAction.COMPLETE)
        clock.advance(Duration.ofHours(1))
        engine.evaluate()
        assertEquals(1, gateway.posts)
        clock.advance(Duration.ofHours(1))
        engine.evaluate()
        assertEquals(2, gateway.posts)
        assertEquals(id, gateway.taskId)
    }
    @Test fun staleActionCannotCompleteOrCancelNewReminder() = runTest {
        val first = repo.saveTask(null, "首"); engine.evaluate()
        val old = gateway.visibleToken!!
        engine.handleAction(first, old, ReminderAction.SNOOZE)
        clock.advance(Duration.ofMinutes(30)); repo.saveTask(null, "腕"); engine.evaluate()
        val current = gateway.visibleToken!!
        engine.handleAction(first, old, ReminderAction.COMPLETE)
        engine.handleAction(first, old, ReminderAction.DISMISS)
        assertEquals(current, gateway.visibleToken)
        assertTrue(repo.snapshots.first().tasks.first { it.id == first }.completions.isEmpty())
    }
    @Test fun permissionDenialDoesNotConsumeCadence() = runTest {
        repo.saveTask(null, "首"); gateway.allowed = false; engine.evaluate()
        assertNull(db.dao().state().lastNotifiedAt)
        assertTrue(db.dao().activeReminders().isEmpty())
        gateway.allowed = true; engine.evaluate()
        assertEquals(1, gateway.posts)
    }
    @Test fun postFailureClearsReservationAndRetries() = runTest {
        repo.saveTask(null, "首"); gateway.failure = SecurityException("denied"); engine.evaluate()
        assertTrue(db.dao().activeReminders().isEmpty()); assertNull(db.dao().state().lastNotifiedAt)
        gateway.failure = null; engine.evaluate()
        assertNotNull(gateway.visibleToken)
        assertEquals(clock.millis(), db.dao().state().lastNotifiedAt)
    }
    @Test fun explicitPostErrorAlsoDoesNotConsumeCadence() = runTest {
        repo.saveTask(null, "首"); gateway.failure = IllegalStateException("post failed"); engine.evaluate()
        assertNull(db.dao().state().lastNotifiedAt)
        gateway.failure = null; engine.evaluate(); assertNotNull(gateway.visibleToken)
    }
    @Test fun swipeSnoozesOneDayAndDueSnoozeOutranksNormal() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        engine.handleAction(id, gateway.visibleToken!!, ReminderAction.DISMISS)
        assertNull(gateway.visibleToken)
        assertEquals(clock.instant().plusSeconds(86400), repo.snapshots.first().tasks.single().snoozedUntil)
        clock.advance(Duration.ofDays(1)); repo.saveTask(null, "腕"); engine.evaluate()
        assertEquals(id, gateway.taskId)
    }
    @Test fun notificationSnoozeExcludesTaskForOneFullDay() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        engine.handleAction(id, gateway.visibleToken!!, ReminderAction.SNOOZE)
        val deadline = clock.instant().plus(Duration.ofDays(1))
        assertEquals(deadline, repo.snapshots.first().tasks.single().snoozedUntil)
        clock.advance(Duration.ofHours(23)); engine.evaluate()
        assertNull(gateway.visibleToken)
        clock.advance(Duration.ofHours(1)); engine.evaluate()
        assertEquals(id, gateway.taskId)
    }
    @Test fun existingActiveDoesNotBlockAdditionalReminder() = runTest {
        repo.saveTask(null, "首"); engine.evaluate(); val token = gateway.visibleToken
        clock.advance(Duration.ofHours(1)); repo.saveTask(null, "腕"); engine.evaluate()
        assertNotEquals(token, gateway.visibleToken); assertEquals(2, gateway.posts)
        assertEquals(2, gateway.activeCount)
    }
    @Test fun midnightInvalidatesActiveBeforeMissingOsSnooze() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate(); gateway.visibleToken = null
        clock.advance(Duration.ofHours(14)); engine.evaluate()
        assertTrue(db.dao().activeReminders().isEmpty())
        assertNull(repo.snapshots.first().tasks.first { it.id == id }.snoozedUntil)
    }
    @Test fun quietHoursInvalidateActive() = runTest {
        repo.saveTask(null, "首"); engine.evaluate()
        repo.saveSettings(ReminderSettings(11, 21, 30)); engine.evaluate()
        assertNull(gateway.visibleToken); assertTrue(db.dao().activeReminders().isEmpty())
    }
    @Test fun matchingEditsArchiveAndManualCompletionInvalidateNotification() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        repo.saveTask(id, "首を伸ばす", 2); assertNull(gateway.visibleToken)
        clock.advance(Duration.ofMinutes(30)); engine.evaluate(); assertEquals("首を伸ばす", gateway.title)
        repo.setArchived(id, true); assertNull(gateway.visibleToken)
        repo.setArchived(id, false); clock.advance(Duration.ofMinutes(30)); engine.evaluate()
        repo.completeTask(id); assertNull(gateway.visibleToken)
        assertEquals(1, repo.snapshots.first().tasks.single().completions.size)
    }
    @Test fun unknownTaskAndWrongTaskTokenDoNothing() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate(); val token = gateway.visibleToken!!
        engine.handleAction(id + 1, token, ReminderAction.COMPLETE)
        engine.handleAction(id, "old", ReminderAction.DISMISS)
        assertEquals(token, gateway.visibleToken)
        assertTrue(repo.snapshots.first().tasks.single().completions.isEmpty())
    }
    @Test fun completeActionRecordsHistoryAndClearsSnooze() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        engine.handleAction(id, gateway.visibleToken!!, ReminderAction.COMPLETE)
        assertNull(gateway.visibleToken)
        assertEquals(1, repo.snapshots.first().tasks.single().completions.size)
        assertNull(repo.snapshots.first().tasks.single().snoozedUntil)
    }
    @Test fun restartMatchingPreparedStateFinalizesPreparedTimestamp() = runTest {
        val id = repo.saveTask(null, "首")
        reserve(id, "prepared", false); gateway.visibleToken = "prepared"
        clock.advance(Duration.ofMinutes(5)); newEngine().evaluate()
        assertEquals(Instant.parse("2026-10-01T01:00:00Z").toEpochMilli(), db.dao().state().lastNotifiedAt)
        assertEquals("prepared", gateway.visibleToken); assertEquals(0, gateway.posts)
    }
    @Test fun restartMatchingDeliveredStateKeepsReminder() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "delivered", true); gateway.visibleToken = "delivered"
        newEngine().evaluate(); assertEquals("delivered", gateway.visibleToken); assertEquals(0, gateway.posts)
    }
    @Test fun restartPreparedWithoutOsRetriesWithoutCadenceConsumption() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "prepared", false)
        newEngine().evaluate(); assertEquals(1, gateway.posts)
        assertNull(repo.snapshots.first().tasks.single().snoozedUntil)
    }
    @Test fun restartDeliveredWithoutOsSnoozesTask() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "delivered", true)
        clock.advance(Duration.ofMinutes(4))
        newEngine().evaluate()
        assertNull(repo.snapshots.first().tasks.single().snoozedUntil)
        clock.advance(Duration.ofMinutes(1).plusSeconds(1))
        newEngine().evaluate(); assertEquals(0, gateway.posts)
        assertEquals(clock.instant().plusSeconds(86400), repo.snapshots.first().tasks.single().snoozedUntil)
        assertTrue(db.dao().activeReminders().isEmpty())
    }
    @Test fun restartOsOnlyCancelsOldAndPostsFresh() = runTest {
        repo.saveTask(null, "首"); gateway.visibleToken = "orphan"
        newEngine().evaluate(); assertEquals(1, gateway.cancels); assertEquals(1, gateway.posts)
        assertNotEquals("orphan", gateway.visibleToken)
    }
    @Test fun restartMismatchedTokenCancelsOsWithoutSnoozingTask() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "database", true); gateway.visibleToken = "other"
        clock.advance(Duration.ofMinutes(30)); newEngine().evaluate()
        assertEquals(1, gateway.cancels); assertEquals(1, gateway.posts)
        assertNull(repo.snapshots.first().tasks.single().snoozedUntil)
    }
    @Test fun preparedDeleteActionConfirmsCadenceWhenOsNotificationAlreadyGone() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "prepared", false)
        clock.advance(Duration.ofMinutes(5))
        engine.handleAction(id, "prepared", ReminderAction.DISMISS)
        assertEquals(Instant.parse("2026-10-01T01:00:00Z").toEpochMilli(), db.dao().state().lastNotifiedAt)
        assertEquals(clock.instant().plusSeconds(86400), repo.snapshots.first().tasks.single().snoozedUntil)
    }
    @Test fun manualEditOfPreparedVisibleReminderPreservesDeliveredCadence() = runTest {
        val id = repo.saveTask(null, "首"); reserve(id, "prepared", false); gateway.visibleToken = "prepared"
        repo.saveTask(id, "首を伸ばす")
        assertEquals(clock.millis(), db.dao().state().lastNotifiedAt)
        assertNull(gateway.visibleToken)
        engine.evaluate(); assertEquals(0, gateway.posts)
    }
    @Test fun yesterdayActionCannotCompleteTodayBeforePeriodicEvaluation() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate(); val old = gateway.visibleToken!!
        clock.advance(Duration.ofHours(24))
        engine.handleAction(id, old, ReminderAction.COMPLETE)
        assertTrue(repo.snapshots.first().tasks.single().completions.isEmpty())
        assertNull(gateway.visibleToken)
        assertTrue(db.dao().activeReminders().isEmpty())
    }
    @Test fun timezoneSwitchPreservesCompletionDateAndUsesNewZoneForNextCompletion() = runTest {
        val id = repo.saveTask(null, "首"); repo.completeTask(id)
        zone = ZoneId.of("America/Los_Angeles"); repo.completeTask(id)
        assertEquals(setOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-09-30")), repo.snapshots.first().tasks.single().completions.map { it.localDate }.toSet())
    }
    @Test fun notificationReadFailureAfterCommitStillReturnsSavedIdAndSchedulesRecovery() = runTest {
        gateway.failReadOnCall = 2 // Pre-commit reconciliation succeeds; follow-up fails.
        val id = repo.saveTask(null, "private title")
        assertEquals(id, repo.snapshots.first().tasks.single().id)
        assertEquals(1, scheduler.requests)
        gateway.failReadOnCall = null
        engine.evaluate()
        assertEquals(id, gateway.taskId)
    }
    @Test fun cancellationFailureAfterEditCommitDoesNotTurnItIntoRetryableSave() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        gateway.failCancel = true
        val requests = scheduler.requests
        assertEquals(id, repo.saveTask(id, "腕"))
        assertEquals("腕", repo.snapshots.first().tasks.single().title)
        assertEquals(requests + 1, scheduler.requests)
        gateway.failCancel = false
        engine.evaluate()
        assertNull(gateway.visibleToken)
        clock.advance(Duration.ofMinutes(30)); engine.evaluate()
        assertEquals("腕", gateway.title)
    }
    @Test fun schedulerFailureAfterCommitReturnsIdAndLaterEvaluationRecovers() = runTest {
        scheduler.failure = IllegalStateException("scheduler")
        val id = repo.saveTask(null, "首")
        assertEquals(id, repo.snapshots.first().tasks.single().id)
        scheduler.failure = null
        engine.evaluate()
        assertEquals(id, gateway.taskId)
        assertEquals(1, repo.snapshots.first().tasks.size)
    }
    @Test fun preCommitFailureDoesNotWriteAndStillFailsTheCommand() = runTest {
        gateway.failReadOnCall = 1
        try { repo.saveTask(null, "首"); fail("Expected command failure") }
        catch (_: IllegalStateException) { }
        assertTrue(repo.snapshots.first().tasks.isEmpty())
        assertEquals(0, scheduler.requests)
    }
    @Test fun cancellationAfterCommitIsPropagatedInsteadOfReportedAsSaveFailure() = runTest {
        scheduler.failure = CancellationException("cancelled")
        try { repo.saveTask(null, "首"); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, repo.snapshots.first().tasks.size)
    }
    private suspend fun reserve(id: Long, token: String, delivered: Boolean) {
        db.dao().insertActiveReminder(ActiveReminderEntity(taskId = id, token = token, localDate = "2026-10-01", preparedAt = clock.millis(), deliveredAt = if (delivered) clock.millis() else null))
        if (delivered) db.dao().saveState(ReminderStateEntity(lastNotifiedAt = clock.millis()))
    }
}
private class MutableClock(var now: Instant = Instant.parse("2026-10-01T01:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant() = now
    fun advance(duration: Duration) { now = now.plus(duration) }
}
private class FakeGateway : NotificationGateway {
    var allowed = true
    private val visible = mutableMapOf<Int, String>()
    var visibleToken: String?
        get() = visible.values.lastOrNull()
        set(value) { visible.clear(); if (value != null) visible[1] = value }
    val activeCount get() = visible.size
    var posts = 0
    var cancels = 0
    var taskId: Long? = null
    var title: String? = null
    var failure: RuntimeException? = null
    override fun canNotify() = allowed
    var failReadOnCall: Int? = null
    var reads = 0
    var failCancel = false
    override fun activeTokens(): Map<Int, String> {
        reads++
        if (reads == failReadOnCall) error("notification read")
        return visible.toMap()
    }
    override fun post(notificationId: Int, taskId: Long, token: String, title: String) {
        failure?.let { throw it }; posts++; visible[notificationId] = token; this.taskId = taskId; this.title = title
    }
    override fun cancel(notificationId: Int) { if (failCancel) error("cancel"); cancels++; visible.remove(notificationId) }
}
private class FakeScheduler : ReminderScheduler {
    var requests = 0
    var failure: RuntimeException? = null
    var nextAlarm: Instant? = null
    override fun ensureRecurringEvaluation() = Unit
    override fun requestEvaluation(delay: Duration) { requests++; failure?.let { throw it } }
    override fun scheduleWhileIdle(at: Instant) { nextAlarm = at }
}
