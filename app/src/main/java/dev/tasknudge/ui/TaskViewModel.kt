package dev.tasknudge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.validateSettings
import dev.tasknudge.core.validateTaskInput
import dev.tasknudge.data.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskUiState())
    val state = mutableState.asStateFlow()
    private val observationAttempt = MutableStateFlow(0L)
    private var nextSessionId = 0L

    init {
        // collectLatest joins the previous collector before starting a retry.
        // Observation never acknowledges writes or owns screen feedback.
        viewModelScope.launch {
            observationAttempt.collectLatest {
                mutableState.update { it.copy(observation = it.observation.copy(status = ObservationStatus.Loading)) }
                try {
                    repository.snapshots.collect { snapshot ->
                        mutableState.update { it.copy(observation = ObservationState(snapshot, ObservationStatus.Ready)) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(observation = it.observation.copy(status = ObservationStatus.Failed("データを読み込めませんでした。再読み込みしてください。"))) }
                }
            }
        }
    }

    fun retryObservation() { observationAttempt.update { it + 1 } }

    /** Navigation creates a new owner; rotation restores the same owner without clearing its result. */
    fun beginScreenSession(restoredId: Long? = null): Long {
        if (restoredId == state.value.operation.sessionId) return restoredId
        val id = restoredId ?: (nextSessionId + 1)
        nextSessionId = maxOf(nextSessionId, id)
        mutableState.update { it.copy(operation = ScreenOperation(id), titleError = null, recurrenceError = null, settingsError = null) }
        return id
    }

    /** Repeated list/detail updates become available after their result is consumed.
     * Editor commits remain terminal until navigation creates another session. */
    fun consumeOperation(sessionId: Long) {
        mutableState.update {
            if (it.operation == ScreenOperation(sessionId, OperationState.Committed(OperationResult.Updated)))
                it.copy(operation = ScreenOperation(sessionId)) else it
        }
    }

    fun dismissOperationError(sessionId: Long) {
        mutableState.update {
            if (it.operation.sessionId == sessionId && it.operation.state is OperationState.Failed)
                it.copy(operation = ScreenOperation(sessionId)) else it
        }
    }

    fun saveTask(id: Long?, title: String, recurrence: String, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        val days = recurrence.toIntOrNull()
        val titleError = if (title.trim().length !in 1..100) "タイトルは1〜100文字で入力してください" else null
        val recurrenceError = if (days == null || days !in 1..365) "繰り返しは1〜365日の整数で入力してください" else null
        mutableState.update { it.copy(titleError = titleError, recurrenceError = recurrenceError) }
        if (titleError != null || recurrenceError != null) return
        val input = validateTaskInput(title, days!!)
        mutate(sessionId) { OperationResult.TaskSaved(repository.saveTask(id, input.title, input.recurrenceDays)) }
    }

    fun saveSettings(start: String, end: String, interval: Int, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        val startHour = start.toIntOrNull()
        val endHour = end.toIntOrNull()
        val settings = if (startHour != null && endHour != null) ReminderSettings(startHour, endHour, interval) else null
        val valid = settings != null && runCatching { validateSettings(settings) }.isSuccess
        mutableState.update { it.copy(settingsError = if (valid) null else "開始は0〜23時、終了は1〜24時の整数で、開始より後に設定してください。間隔は30・60・120分です。") }
        if (!valid) return
        mutate(sessionId) { repository.saveSettings(settings!!); OperationResult.SettingsSaved }
    }

    fun completeTask(id: Long, sessionId: Long = state.value.operation.sessionId) = mutate(sessionId) { repository.completeTask(id); OperationResult.Updated }
    fun setArchived(id: Long, archived: Boolean, sessionId: Long = state.value.operation.sessionId) = mutate(sessionId) { repository.setArchived(id, archived); OperationResult.Updated }

    private fun canSubmit(sessionId: Long) = state.value.operation.sessionId == sessionId && state.value.canSubmit

    private fun mutate(sessionId: Long, action: suspend () -> OperationResult) {
        if (!canSubmit(sessionId)) return
        mutableState.update { it.copy(operation = ScreenOperation(sessionId, OperationState.Saving)) }
        viewModelScope.launch {
            val result = try { OperationState.Committed(action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OperationState.Failed("保存できませんでした。もう一度お試しください。") }
            mutableState.update { if (it.operation.sessionId == sessionId) it.copy(operation = ScreenOperation(sessionId, result)) else it }
        }
    }
}
