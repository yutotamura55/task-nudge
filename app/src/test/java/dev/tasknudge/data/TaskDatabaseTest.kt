package dev.tasknudge.data

import android.app.Application
import androidx.room.Room
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.reminders.*
import java.time.*
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
class TaskDatabaseTest {
    private lateinit var db: TaskDatabase
    private lateinit var repository: TaskRepository
    private var zone = ZoneId.of("Asia/Tokyo")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).addCallback(TaskDatabase.initializeDefaults).allowMainThreadQueries().build()
        repository = ReminderEngine(db, Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC), { zone }, SilentGateway(), SilentScheduler()).repository
    }
    @After fun close() { db.close() }
    @Test fun emptyDatabaseEmitsDefaults() = runTest {
        val snapshot = repository.snapshots.first()
        assertTrue(snapshot.tasks.isEmpty())
        assertEquals(ReminderSettings(), snapshot.settings)
        assertFalse(snapshot.permissionRequested)
    }
    @Test fun duplicateSameDayCompletionIsIgnored() = runTest {
        val id = repository.saveTask(null, " 首のストレッチ ")
        repository.completeTask(id); repository.completeTask(id)
        val task = repository.snapshots.first().tasks.single()
        assertEquals("首のストレッチ", task.title)
        assertEquals(1, task.completions.size)
        assertEquals(LocalDate.parse("2026-10-01"), task.completions.single().localDate)
    }
    @Test fun editsAndArchivePreserveHistoryAndOriginalDay() = runTest {
        val id = repository.saveTask(null, "首")
        repository.completeTask(id)
        zone = ZoneId.of("Pacific/Kiritimati")
        repository.saveTask(id, "首を伸ばす", 3)
        repository.setArchived(id, true)
        val task = repository.snapshots.first().tasks.single()
        assertTrue(task.archived)
        assertEquals(3, task.recurrenceDays)
        assertEquals(LocalDate.parse("2026-10-01"), task.completions.single().localDate)
    }
    @Test fun invalidInputsNeverWrite() = runTest {
        for (title in listOf(" ", "a".repeat(101))) {
            try { repository.saveTask(null, title); fail("invalid title accepted") } catch (_: IllegalArgumentException) { }
        }
        try { repository.saveTask(null, "首", 0); fail("invalid interval accepted") } catch (_: IllegalArgumentException) { }
        try { repository.saveSettings(ReminderSettings(startHour = 22)); fail("invalid hours accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.snapshots.first().tasks.isEmpty())
        assertEquals(ReminderSettings(), repository.snapshots.first().settings)
    }
    @Test fun sqliteUniqueConstraintRejectsDuplicateOutsideRepository() = runTest {
        val id = repository.saveTask(null, "首")
        assertTrue(db.dao().insertCompletion(CompletionEntity(taskId = id, completedAt = 1, localDate = "2026-10-01")) > 0)
        assertEquals(-1L, db.dao().insertCompletion(CompletionEntity(taskId = id, completedAt = 2, localDate = "2026-10-01")))
        assertEquals(1, repository.snapshots.first().tasks.single().completions.size)
    }
    @Test fun diskReopenRetainsTasksCompletionAndSettings() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "task2-persistence.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name).addCallback(TaskDatabase.initializeDefaults).allowMainThreadQueries().build()
        try {
            var stored = open()
            try {
                val repo = ReminderEngine(stored, gateway = SilentGateway(), scheduler = SilentScheduler()).repository
                val id = repo.saveTask(null, "腰", 3); repo.completeTask(id)
                repo.saveSettings(ReminderSettings(8, 23, 15)); repo.recordPermissionRequest()
            } finally { stored.close() }
            stored = open()
            try {
                val snapshot = ReminderEngine(stored, gateway = SilentGateway(), scheduler = SilentScheduler()).repository.snapshots.first()
                assertEquals("腰", snapshot.tasks.single().title)
                assertEquals(1, snapshot.tasks.single().completions.size)
                assertEquals(ReminderSettings(8, 23, 15), snapshot.settings)
                assertTrue(snapshot.permissionRequested)
            } finally { stored.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun settingsAndPermissionPersistAcrossEngineRecreation() = runTest {
        repository.saveSettings(ReminderSettings(8, 23, 60)); repository.recordPermissionRequest()
        val reloaded = ReminderEngine(db, gateway = SilentGateway(), scheduler = SilentScheduler()).repository.snapshots.first()
        assertEquals(ReminderSettings(8, 23, 60), reloaded.settings)
        assertTrue(reloaded.permissionRequested)
    }
}
private class SilentGateway : NotificationGateway {
    override fun canNotify() = false
    override fun activeToken(): String? = null
    override fun post(taskId: Long, token: String, title: String) = Unit
    override fun cancel() = Unit
}
private class SilentScheduler : ReminderScheduler {
    override fun ensurePeriodicWork() = Unit
    override fun requestEvaluation(delay: Duration) = Unit
}
