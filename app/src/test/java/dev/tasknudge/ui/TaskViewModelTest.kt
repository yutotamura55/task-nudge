package dev.tasknudge.ui

import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun loadingIsDifferentFromLoadedEmpty() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = TaskViewModel(repo)
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.snapshot)
        repo.data.emit(AppSnapshot(emptyList(), ReminderSettings(), false))
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(emptyList<TaskSnapshot>(), vm.state.value.snapshot!!.tasks)
    }
    @Test fun titleOnlyAddTrimsAndDefaultsToDaily() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTask(null, " 首のストレッチ ", "1"); runCurrent()
        assertEquals(SavedInput(null, "首のストレッチ", 1), repo.saved.single())
        assertEquals(1L, vm.state.value.savedTaskId)
        assertFalse(vm.state.value.saving)
    }
    @Test fun editingKeepsTaskIdentityAndRecurrence() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTask(9, "腕", "3"); runCurrent()
        assertEquals(SavedInput(9, "腕", 3), repo.saved.single())
        assertEquals(9L, vm.state.value.savedTaskId)
    }
    @Test fun rejectsBlankAndLongTitlesBeforeWriting() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        for (title in listOf("  ", "a".repeat(101))) {
            vm.saveTask(null, title, "1"); runCurrent()
            assertNotNull(vm.state.value.titleError)
        }
        assertTrue(repo.saved.isEmpty())
    }
    @Test fun rejectsMalformedOrOutOfRangeRecurrence() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        for (days in listOf("", "1.5", "0", "366", "999999999999")) {
            vm.saveTask(null, "腰", days); runCurrent()
            assertNotNull(vm.state.value.recurrenceError)
        }
        assertTrue(repo.saved.isEmpty())
    }
    @Test fun preventsDuplicateWritesDuringSuspendedSave() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); vm.saveTask(null, "首", "1"); runCurrent()
        assertTrue(vm.state.value.saving)
        assertEquals(1, repo.saved.size)
        repo.gate!!.complete(Unit); runCurrent()
        assertFalse(vm.state.value.saving)
    }
    @Test fun preventsDuplicateAddsUntilSnapshotAcknowledgesSavedTask() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertTrue(vm.state.value.saving)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertEquals(1, repo.saved.size)
        repo.publishPending(); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.state.value.savedTaskId)
    }
    @Test fun editAcknowledgementRequiresSavedValues() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        repo.data.emit(AppSnapshot(listOf(task(1)), ReminderSettings(), false)); runCurrent()
        vm.saveTask(1, "腕", "3"); runCurrent()
        assertTrue(vm.state.value.saving)
        assertNull(vm.state.value.savedTaskId)
        repo.publishPending(); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.state.value.savedTaskId)
    }
    @Test fun leavingOriginEditorInvalidatesSuspendedSaveNavigation() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.clearFeedback() // Leaving A and opening B starts a different feedback session.
        repo.gate!!.complete(Unit); runCurrent()
        assertNull(vm.state.value.savedTaskId)
        assertEquals("首", vm.state.value.snapshot!!.tasks.single().title)
        assertFalse(vm.state.value.saving)
    }
    @Test fun leavingOriginEditorWhileWaitingForSnapshotInvalidatesNavigation() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.clearFeedback()
        repo.publishPending(); runCurrent()
        assertNull(vm.state.value.savedTaskId)
        assertFalse(vm.state.value.saving)
    }
    @Test fun repositoryFailureIsVisibleAndCanRetry() = runTest(dispatcher) {
        val repo = FakeRepository().apply { fail = true }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.savedTaskId)
        assertFalse(vm.state.value.saving)
        repo.fail = false
        vm.saveTask(null, "首", "1"); runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(1L, vm.state.value.savedTaskId)
    }
    @Test fun streamFailureStopsLoadingAndShowsError() = runTest(dispatcher) {
        val repo = FakeRepository(emitFailure = true); val vm = TaskViewModel(repo)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
    }
    @Test fun settingsAcceptEndOfDayAndRejectInvalidHoursAndIntervals() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        for ((start, end, interval) in listOf(Triple("-1", "21", 30), Triple("24", "24", 30), Triple("9", "0", 30), Triple("9", "25", 30), Triple("21", "9", 30), Triple("no", "21", 30), Triple("9", "21", 15))) {
            vm.saveSettings(start, end, interval); runCurrent()
            assertNotNull(vm.state.value.settingsError)
        }
        assertTrue(repo.settings.isEmpty())
        vm.saveSettings("0", "24", 120); runCurrent()
        assertEquals(ReminderSettings(0, 24, 120), repo.settings.single())
        assertTrue(vm.state.value.settingsSaved)
    }
    @Test fun completedCountsExcludeArchivedAndHistorySurvivesStopAndRestart() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        repo.data.emit(AppSnapshot(listOf(task(1), task(2).copy(archived = true)), ReminderSettings(), false)); runCurrent()
        vm.completeTask(1); runCurrent()
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
        vm.setArchived(1, true); runCurrent()
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
        assertTrue(vm.state.value.snapshot!!.tasks.first().archived)
        vm.setArchived(1, false); runCurrent()
        assertFalse(vm.state.value.snapshot!!.tasks.first().archived)
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
    }
}

private data class SavedInput(val id: Long?, val title: String, val days: Int)
private fun task(id: Long) = TaskSnapshot(id, "首", 1, false, Instant.EPOCH, null, emptyList())
private class FakeRepository(private val emitFailure: Boolean = false) : TaskRepository {
    val data = MutableSharedFlow<AppSnapshot>(replay = 1)
    override val snapshots: Flow<AppSnapshot> = if (emitFailure) flow { error("disk") } else data
    val saved = mutableListOf<SavedInput>()
    val settings = mutableListOf<ReminderSettings>()
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    var publishSaves = true
    var pending: TaskSnapshot? = null
    override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int): Long {
        if (fail) error("disk")
        saved += SavedInput(id, title, recurrenceDays); gate?.await()
        val savedId = id ?: 1
        pending = task(savedId).copy(title = title, recurrenceDays = recurrenceDays)
        if (publishSaves) publishPending()
        return savedId
    }
    suspend fun publishPending() {
        val snapshot = data.replayCache.lastOrNull() ?: AppSnapshot(emptyList(), ReminderSettings(), false)
        data.emit(snapshot.copy(tasks = snapshot.tasks.filterNot { it.id == pending!!.id } + pending!!))
    }
    override suspend fun completeTask(id: Long) {
        data.emit(data.replayCache.last().let { it.copy(tasks = it.tasks.map { task -> if (task.id == id) task.copy(completions = listOf(CompletionRecord(1, Instant.EPOCH, LocalDate.of(2026, 10, 1)))) else task }) })
    }
    override suspend fun setArchived(id: Long, archived: Boolean) {
        data.emit(data.replayCache.last().let { it.copy(tasks = it.tasks.map { task -> if (task.id == id) task.copy(archived = archived) else task }) })
    }
    override suspend fun saveSettings(settings: ReminderSettings) { this.settings += settings }
    override suspend fun recordPermissionRequest() {}
}
