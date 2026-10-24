package dev.tasknudge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasknudge.core.*
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
        mutableState.update { it.copy(operation = ScreenOperation(id), titleError = null, bulkError = null, recurrenceError = null, settingsError = null) }
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

    fun saveTask(id: Long?, title: String, recurrence: String, sessionId: Long = state.value.operation.sessionId, hourly: Boolean = false) {
        if (!canSubmit(sessionId)) return
        val value = recurrence.toIntOrNull()
        val titleError = titleError(title)
        val recurrenceError = recurrenceError(value, hourly)
        mutableState.update { it.copy(titleError = titleError?.message(), recurrenceError = recurrenceError?.message()) }
        if (titleError != null || recurrenceError != null) return
        // Hourly tasks keep the daily column at its default value.
        val input = validateTaskInput(title, if (hourly) 1 else value!!)
        mutate(sessionId) { OperationResult.TaskSaved(repository.saveTask(id, input.title, input.recurrenceDays, if (hourly) value else null)) }
    }

    fun saveTasks(text: String, recurrence: String, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        val days = recurrence.toIntOrNull()
        val bulkError = bulkTitlesError(bulkTitles(text))
        val recurrenceError = recurrenceError(days)
        mutableState.update { it.copy(bulkError = bulkError?.message(), recurrenceError = recurrenceError?.message()) }
        if (bulkError != null || recurrenceError != null) return
        val inputs = validateBulkTaskInput(text, days!!)
        mutate(sessionId) { OperationResult.TasksSaved(repository.saveTasks(inputs).size) }
    }

    fun saveSettings(start: String, end: String, interval: Int, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        val startHour = start.toIntOrNull()
        val endHour = end.toIntOrNull()
        val settings = if (startHour != null && endHour != null) ReminderSettings(startHour, endHour, interval) else null
        val error = if (settings == null) InputError.SETTINGS else settingsError(settings)
        mutableState.update { it.copy(settingsError = error?.message()) }
        if (error != null) return
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

internal fun InputError.message(): String = when (this) {
    InputError.TITLE_LENGTH -> "タイトルは${titleLengthRange.first}〜${titleLengthRange.last}文字で入力してください"
    InputError.DAILY_RECURRENCE -> "繰り返しは${dailyRecurrenceRange.first}〜${dailyRecurrenceRange.last}日の整数で入力してください"
    InputError.HOURLY_RECURRENCE -> "繰り返しは${hourlyRecurrenceRange.first}〜${hourlyRecurrenceRange.last}時間の整数で入力してください"
    InputError.BULK_EMPTY -> "タスクを1行以上入力してください"
    InputError.BULK_TOO_MANY -> "一度に追加できるタスクは${maxBulkTasks}件までです"
    InputError.BULK_TITLE_LENGTH -> "各タスク名は前後の空白を除き${titleLengthRange.last}文字以内で入力してください"
    InputError.SETTINGS -> "開始は${startHourRange.first}〜${startHourRange.last}時、終了は${endHourRange.first}〜${endHourRange.last}時の整数で、" +
        "開始より後に設定してください。間隔は${notificationIntervalOptions.joinToString("・")}分です。"
}
