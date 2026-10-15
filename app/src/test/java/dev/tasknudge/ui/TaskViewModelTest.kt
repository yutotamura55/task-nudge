package dev.tasknudge.ui

import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.TaskInput
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
    @Test fun bulkSavePassesTrimmedLinesAndOneIntervalAsOneCommand() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTasks(" 首 \n\n 腕 ", "3"); runCurrent()
        assertEquals(listOf(TaskInput("首", 3), TaskInput("腕", 3)), repo.bulkSaved.single())
        assertEquals(OperationState.Committed(OperationResult.TasksSaved(2)), vm.state.value.operation.state)
        vm.saveTasks("首\n腕", "3"); runCurrent()
        assertEquals(1, repo.bulkSaved.size)
    }
    @Test fun invalidBulkInputCannotPartlyWrite() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTasks("首\n${"長".repeat(101)}", "1"); runCurrent()
        assertNotNull(vm.state.value.bulkError)
        vm.saveTasks("首\n腕", "0"); runCurrent()
        assertNotNull(vm.state.value.recurrenceError)
        assertTrue(repo.bulkSaved.isEmpty())
    }
    @Test fun oldBulkSaveResultCannotReachNewScreen() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }; val vm = TaskViewModel(repo)
        val old = vm.beginScreenSession()
        vm.saveTasks("首\n腕", "1", old); runCurrent()
        val current = vm.beginScreenSession()
        repo.gate!!.complete(Unit); runCurrent()
        assertEquals(ScreenOperation(current), vm.state.value.operation)
    }
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
        assertEquals(1L, vm.savedTaskId)
        assertFalse(vm.state.value.saving)
    }
    @Test fun editingKeepsTaskIdentityAndRecurrence() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTask(9, "腕", "3"); runCurrent()
        assertEquals(SavedInput(9, "腕", 3), repo.saved.single())
        assertEquals(9L, vm.savedTaskId)
    }
    @Test fun hourlyRecurrenceUsesHoursAndRejectsOutOfRangeInput() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveTask(null, "水を飲む", "25", hourly = true); runCurrent()
        assertNotNull(vm.state.value.recurrenceError)
        assertTrue(repo.saved.isEmpty())
        vm.saveTask(null, "水を飲む", "2", hourly = true); runCurrent()
        assertEquals(SavedInput(null, "水を飲む", 1, 2), repo.saved.single())
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
    @Test fun committedAddCannotBeResubmittedWhileDisplayDataIsDelayed() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.savedTaskId)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertEquals(1, repo.saved.size)
        repo.publishPending(); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.savedTaskId)
    }
    @Test fun editCommitDoesNotWaitForMatchingObservedFields() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        repo.data.emit(AppSnapshot(listOf(task(1)), ReminderSettings(), false)); runCurrent()
        vm.saveTask(1, "腕", "3"); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.savedTaskId)
        repo.publishPending(); runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals(1L, vm.savedTaskId)
    }
    @Test fun leavingOriginEditorInvalidatesSuspendedSaveNavigation() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.beginScreenSession() // Leaving A and opening B starts a different feedback session.
        repo.gate!!.complete(Unit); runCurrent()
        assertNull(vm.savedTaskId)
        assertEquals("首", vm.state.value.snapshot!!.tasks.single().title)
        assertFalse(vm.state.value.saving)
    }
    @Test fun leavingCommittedEditorDoesNotDeliverResultToNewScreen() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.beginScreenSession()
        repo.publishPending(); runCurrent()
        assertNull(vm.savedTaskId)
        assertFalse(vm.state.value.saving)
    }
    @Test fun repositoryFailureIsVisibleAndCanRetry() = runTest(dispatcher) {
        val repo = FakeRepository().apply { fail = true }; val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertNotNull((vm.state.value.operation.state as? OperationState.Failed)?.message)
        assertNull(vm.savedTaskId)
        assertFalse(vm.state.value.saving)
        repo.fail = false
        vm.saveTask(null, "首", "1"); runCurrent()
        assertNull((vm.state.value.operation.state as? OperationState.Failed)?.message)
        assertEquals(1L, vm.savedTaskId)
    }
    @Test fun observationFailureAroundCommitDoesNotOfferDuplicateSaveRetry() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred(); publishSaves = false }
        val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        repo.observationFailures.emit(Unit); runCurrent()
        repo.gate!!.complete(Unit); runCurrent()
        assertEquals(1L, vm.savedTaskId)
        assertFalse(vm.state.value.saving)
        vm.saveTask(null, "首", "1"); runCurrent()
        assertEquals(1, repo.saved.size)
    }
    @Test fun settingsCommitBelongsToItsOriginatingScreenAcrossNavigation() = runTest(dispatcher) {
        val repo = FakeRepository().apply { settingsGate = CompletableDeferred() }
        val vm = TaskViewModel(repo)
        vm.saveSettings("0", "24", 120); runCurrent()
        vm.beginScreenSession()
        vm.saveTask(null, "new draft", "1"); runCurrent()
        repo.settingsGate!!.complete(Unit); runCurrent()
        assertFalse(vm.settingsSaved)
        assertEquals(SavedInput(null, "new draft", 1), repo.saved.single())
    }
    @Test fun committedSettingsCannotBeSubmittedTwiceInSameScreen() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        vm.saveSettings("0", "24", 120); runCurrent()
        vm.saveSettings("0", "24", 120); runCurrent()
        assertEquals(1, repo.settings.size)
    }
    @Test fun oldTaskAndSettingsFailuresDoNotAppearOnANewScreen() = runTest(dispatcher) {
        for (settings in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepository().apply { this.gate = gate; settingsGate = gate; failAfterGate = true }
            val vm = TaskViewModel(repo)
            if (settings) vm.saveSettings("0", "24", 120) else vm.saveTask(null, "首", "1")
            runCurrent(); vm.beginScreenSession()
            gate.complete(Unit); runCurrent()
            assertNull((vm.state.value.operation.state as? OperationState.Failed)?.message)
        }
    }
    @Test fun streamFailureStopsLoadingAndShowsError() = runTest(dispatcher) {
        val repo = FakeRepository(emitFailure = true); val vm = TaskViewModel(repo)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.observation.status is ObservationStatus.Failed)
    }
    @Test fun settingsAcceptEndOfDayAndRejectInvalidHoursAndIntervals() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        for ((start, end, interval) in listOf(Triple("-1", "21", 30), Triple("24", "24", 30), Triple("9", "0", 30), Triple("9", "25", 30), Triple("21", "9", 30), Triple("no", "21", 30), Triple("9", "21", 16))) {
            vm.saveSettings(start, end, interval); runCurrent()
            assertNotNull(vm.state.value.settingsError)
        }
        assertTrue(repo.settings.isEmpty())
        vm.saveSettings("0", "24", 120); runCurrent()
        assertEquals(ReminderSettings(0, 24, 120), repo.settings.single())
        assertTrue(vm.settingsSaved)
    }
    @Test fun readRetryRecoversCommittedTaskAndKeepsOneSubscription() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }
        val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        repo.observationFailures.emit(Unit); runCurrent()
        assertTrue(vm.state.value.observation.status is ObservationStatus.Failed)
        assertEquals(1L, vm.savedTaskId)
        vm.retryObservation(); vm.retryObservation(); runCurrent()
        repo.publishPending(); runCurrent()
        assertEquals(ObservationStatus.Ready, vm.state.value.observation.status)
        assertEquals(1L, vm.state.value.snapshot!!.tasks.single().id)
        assertEquals(1, repo.activeSubscriptions)
        assertEquals(1, repo.maxSubscriptions)
        assertEquals(1, repo.saved.size)
    }
    @Test fun readingFailureSurvivesNavigationValidationAndCommandFeedbackDismissal() = runTest(dispatcher) {
        val repo = FakeRepository(emitFailure = true).apply { fail = true }
        val vm = TaskViewModel(repo); runCurrent()
        vm.beginScreenSession()
        vm.saveTask(null, "", "1"); runCurrent()
        assertTrue(vm.state.value.observation.status is ObservationStatus.Failed)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.dismissOperationError(vm.state.value.operation.sessionId)
        assertEquals(OperationState.Idle, vm.state.value.operation.state)
        assertTrue(vm.state.value.observation.status is ObservationStatus.Failed)
        repo.failObservation = false
        vm.retryObservation(); runCurrent()
        repo.data.emit(AppSnapshot(emptyList(), ReminderSettings(), false)); runCurrent()
        assertEquals(ObservationStatus.Ready, vm.state.value.observation.status)
    }
    @Test fun delayedCommittedDataDoesNotBlockANewScreenCommand() = runTest(dispatcher) {
        val repo = FakeRepository().apply { publishSaves = false }
        val vm = TaskViewModel(repo)
        vm.saveTask(null, "首", "1"); runCurrent()
        vm.beginScreenSession()
        vm.saveSettings("0", "24", 120); runCurrent()
        assertTrue(vm.settingsSaved)
        assertEquals(1, repo.saved.size)
        assertEquals(1, repo.settings.size)
    }
    @Test fun oldListAndDetailFailuresCannotChangeNewOwnerOperation() = runTest(dispatcher) {
        for (archive in listOf(false, true)) {
            val repo = FakeRepository().apply { updateGate = CompletableDeferred(); failAfterGate = true }
            val vm = TaskViewModel(repo)
            repo.data.emit(AppSnapshot(listOf(task(1)), ReminderSettings(), false)); runCurrent()
            if (archive) vm.setArchived(1, true) else vm.completeTask(1)
            runCurrent(); val current = vm.beginScreenSession()
            repo.updateGate!!.complete(Unit); runCurrent()
            assertEquals(ScreenOperation(current), vm.state.value.operation)
        }
    }
    @Test fun restoringSameSessionKeepsSuspendedAndCommittedOperationOwnership() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred(); publishSaves = false }
        val vm = TaskViewModel(repo); val owner = vm.beginScreenSession()
        vm.saveTask(null, "首", "1", owner); runCurrent()
        assertEquals(owner, vm.beginScreenSession(owner))
        assertTrue(vm.state.value.saving)
        repo.gate!!.complete(Unit); runCurrent()
        assertEquals(owner, vm.beginScreenSession(owner))
        vm.consumeOperation(owner)
        vm.saveTask(null, "首", "1", owner); runCurrent()
        assertEquals(1L, vm.savedTaskId)
        assertEquals(1, repo.saved.size)
    }
    @Test fun staleUiCallbackCannotStartACommandOnNewScreen() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        val old = vm.beginScreenSession(); val current = vm.beginScreenSession()
        vm.saveTask(null, "首", "1", old); vm.saveSettings("0", "24", 120, old)
        vm.completeTask(1, old); vm.setArchived(1, true, old); runCurrent()
        assertTrue(repo.saved.isEmpty()); assertTrue(repo.settings.isEmpty())
        assertEquals(ScreenOperation(current), vm.state.value.operation)
    }
    @Test fun completedCountsExcludeArchivedAndHistorySurvivesStopAndRestart() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = TaskViewModel(repo)
        repo.data.emit(AppSnapshot(listOf(task(1), task(2).copy(archived = true)), ReminderSettings(), false)); runCurrent()
        vm.completeTask(1); runCurrent()
        vm.consumeOperation(vm.state.value.operation.sessionId)
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
        vm.setArchived(1, true); runCurrent()
        vm.consumeOperation(vm.state.value.operation.sessionId)
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
        assertTrue(vm.state.value.snapshot!!.tasks.first().archived)
        vm.setArchived(1, false); runCurrent()
        assertFalse(vm.state.value.snapshot!!.tasks.first().archived)
        assertEquals(1, vm.state.value.snapshot!!.tasks.first().completions.size)
    }
}

private val TaskViewModel.savedTaskId: Long?
    get() = ((state.value.operation.state as? OperationState.Committed)?.result as? OperationResult.TaskSaved)?.id
private val TaskViewModel.settingsSaved: Boolean
    get() = state.value.operation.state == OperationState.Committed(OperationResult.SettingsSaved)

private data class SavedInput(val id: Long?, val title: String, val days: Int, val hours: Int? = null)
private fun task(id: Long) = TaskSnapshot(id, "首", 1, false, Instant.EPOCH, null, emptyList())
private class FakeRepository(emitFailure: Boolean = false) : TaskRepository {
    val data = MutableSharedFlow<AppSnapshot>(replay = 1)
    val observationFailures = MutableSharedFlow<Unit>()
    var failObservation = emitFailure
    var activeSubscriptions = 0
    var maxSubscriptions = 0
    override val snapshots: Flow<AppSnapshot> = flow {
        activeSubscriptions++
        maxSubscriptions = maxOf(maxSubscriptions, activeSubscriptions)
        try {
            if (failObservation) error("disk")
            emitAll(merge(data, observationFailures.map { error("disk") }))
        } finally { activeSubscriptions-- }
    }
    val saved = mutableListOf<SavedInput>()
    val bulkSaved = mutableListOf<List<TaskInput>>()
    val settings = mutableListOf<ReminderSettings>()
    var gate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var settingsGate: CompletableDeferred<Unit>? = null
    var failAfterGate = false
    var fail = false
    var publishSaves = true
    var pending: TaskSnapshot? = null
    override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int, recurrenceHours: Int?): Long {
        if (fail) error("disk")
        saved += SavedInput(id, title, recurrenceDays, recurrenceHours); gate?.await()
        if (failAfterGate) error("disk")
        val savedId = id ?: 1
        pending = task(savedId).copy(title = title, recurrenceDays = recurrenceDays, recurrenceHours = recurrenceHours)
        if (publishSaves) publishPending()
        return savedId
    }
    override suspend fun saveTasks(inputs: List<TaskInput>): List<Long> {
        if (fail) error("disk")
        gate?.await()
        bulkSaved += inputs
        return inputs.indices.map { it + 1L }
    }
    suspend fun publishPending() {
        val snapshot = data.replayCache.lastOrNull() ?: AppSnapshot(emptyList(), ReminderSettings(), false)
        data.emit(snapshot.copy(tasks = snapshot.tasks.filterNot { it.id == pending!!.id } + pending!!))
    }
    override suspend fun completeTask(id: Long) {
        updateGate?.await()
        if (failAfterGate) error("disk")
        data.emit(data.replayCache.last().let { it.copy(tasks = it.tasks.map { task -> if (task.id == id) task.copy(completions = listOf(CompletionRecord(1, Instant.EPOCH, LocalDate.of(2026, 10, 1)))) else task }) })
    }
    override suspend fun setArchived(id: Long, archived: Boolean) {
        updateGate?.await()
        if (failAfterGate) error("disk")
        data.emit(data.replayCache.last().let { it.copy(tasks = it.tasks.map { task -> if (task.id == id) task.copy(archived = archived) else task }) })
    }
    override suspend fun saveSettings(settings: ReminderSettings) {
        settingsGate?.await()
        if (failAfterGate) error("disk")
        this.settings += settings
    }
    override suspend fun recordPermissionRequest() {}
}
