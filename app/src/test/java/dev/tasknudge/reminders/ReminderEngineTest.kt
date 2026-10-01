package dev.tasknudge.reminders

import android.app.Application
import androidx.room.Room
import dev.tasknudge.core.ReminderSettings
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
    @Test fun concurrentEvaluationsPostOneReminder() = runTest {
        repo.saveTask(null, "首")
        coroutineScope { repeat(10) { launch(Dispatchers.Default) { engine.evaluate() } } }
        assertEquals(1, gateway.posts)
        assertNotNull(db.dao().state().deliveredAt)
    }
    @Test fun concurrentCompletionsRecordOneDay() = runTest {
        val id = repo.saveTask(null, "首")
        coroutineScope { repeat(10) { launch(Dispatchers.Default) { repo.completeTask(id) } } }
        assertEquals(1, repo.snapshots.first().tasks.single().completions.size)
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
        assertNull(db.dao().state().activeToken)
        gateway.allowed = true; engine.evaluate()
        assertEquals(1, gateway.posts)
    }
    @Test fun postFailureClearsReservationAndRetries() = runTest {
        repo.saveTask(null, "首"); gateway.failure = SecurityException("denied"); engine.evaluate()
        assertNull(db.dao().state().activeToken); assertNull(db.dao().state().lastNotifiedAt)
        gateway.failure = null; engine.evaluate()
        assertNotNull(gateway.visibleToken)
        assertEquals(clock.millis(), db.dao().state().lastNotifiedAt)
    }
    @Test fun explicitPostErrorAlsoDoesNotConsumeCadence() = runTest {
        repo.saveTask(null, "首"); gateway.failure = IllegalStateException("post failed"); engine.evaluate()
        assertNull(db.dao().state().lastNotifiedAt)
        gateway.failure = null; engine.evaluate(); assertNotNull(gateway.visibleToken)
    }
    @Test fun swipeSnoozesOneHourAndDueSnoozeOutranksNormal() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate()
        engine.handleAction(id, gateway.visibleToken!!, ReminderAction.DISMISS)
        assertNull(gateway.visibleToken)
        assertEquals(clock.instant().plusSeconds(3600), repo.snapshots.first().tasks.single().snoozedUntil)
        clock.advance(Duration.ofHours(1)); repo.saveTask(null, "腕"); engine.evaluate()
        assertEquals(id, gateway.taskId)
    }
    @Test fun existingActiveBlocksAdditionalReminder() = runTest {
        repo.saveTask(null, "首"); engine.evaluate(); val token = gateway.visibleToken
        clock.advance(Duration.ofHours(1)); repo.saveTask(null, "腕"); engine.evaluate()
        assertEquals(token, gateway.visibleToken); assertEquals(1, gateway.posts)
    }
    @Test fun midnightInvalidatesActiveBeforeMissingOsSnooze() = runTest {
        val id = repo.saveTask(null, "首"); engine.evaluate(); gateway.visibleToken = null
        clock.advance(Duration.ofHours(14)); engine.evaluate()
        assertNull(db.dao().state().activeToken)
        assertNull(repo.snapshots.first().tasks.first { it.id == id }.snoozedUntil)
    }
    @Test fun quietHoursInvalidateActive() = runTest {
        repo.saveTask(null, "首"); engine.evaluate()
        repo.saveSettings(ReminderSettings(11, 21, 30)); engine.evaluate()
        assertNull(gateway.visibleToken); assertNull(db.dao().state().activeToken)
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
        newEngine().evaluate(); assertEquals(0, gateway.posts)
        assertEquals(clock.instant().plusSeconds(3600), repo.snapshots.first().tasks.single().snoozedUntil)
        assertNull(db.dao().state().activeToken)
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
        assertEquals(clock.instant().plusSeconds(3600), repo.snapshots.first().tasks.single().snoozedUntil)
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
        assertNull(db.dao().state().activeToken)
    }
    @Test fun timezoneSwitchPreservesCompletionDateAndUsesNewZoneForNextCompletion() = runTest {
        val id = repo.saveTask(null, "首"); repo.completeTask(id)
        zone = ZoneId.of("America/Los_Angeles"); repo.completeTask(id)
        assertEquals(setOf(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-09-30")), repo.snapshots.first().tasks.single().completions.map { it.localDate }.toSet())
    }
    private suspend fun reserve(id: Long, token: String, delivered: Boolean) {
        db.dao().saveState(ReminderStateEntity(activeTaskId = id, activeToken = token, activeLocalDate = "2026-10-01", preparedAt = clock.millis(), deliveredAt = if (delivered) clock.millis() else null, lastNotifiedAt = if (delivered) clock.millis() else null))
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
    var visibleToken: String? = null
    var posts = 0
    var cancels = 0
    var taskId: Long? = null
    var title: String? = null
    var failure: RuntimeException? = null
    override fun canNotify() = allowed
    override fun activeToken() = visibleToken
    override fun post(taskId: Long, token: String, title: String) {
        failure?.let { throw it }; posts++; visibleToken = token; this.taskId = taskId; this.title = title
    }
    override fun cancel() { cancels++; visibleToken = null }
}
private class FakeScheduler : ReminderScheduler {
    override fun ensurePeriodicWork() = Unit
    override fun requestEvaluation(delay: Duration) = Unit
}
