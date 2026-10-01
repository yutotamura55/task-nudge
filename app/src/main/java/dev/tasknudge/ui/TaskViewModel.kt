package dev.tasknudge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.validateSettings
import dev.tasknudge.core.validateTaskInput
import dev.tasknudge.data.AppSnapshot
import dev.tasknudge.data.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskUiState(
    val loading: Boolean = true,
    val snapshot: AppSnapshot? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val titleError: String? = null,
    val recurrenceError: String? = null,
    val settingsError: String? = null,
    val savedTaskId: Long? = null,
    val settingsSaved: Boolean = false,
)

class TaskViewModel(private val repository: TaskRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.snapshots.catch {
                mutableState.update { it.copy(loading = false, error = "データを読み込めませんでした。アプリを開き直してください。") }
            }.collect { snapshot -> mutableState.update { it.copy(loading = false, snapshot = snapshot) } }
        }
    }

    fun saveTask(id: Long?, title: String, recurrence: String) {
        if (state.value.saving) return
        val days = recurrence.toIntOrNull()
        val titleError = if (title.trim().length !in 1..100) "タイトルは1〜100文字で入力してください" else null
        val recurrenceError = if (days == null || days !in 1..365) "繰り返しは1〜365日の整数で入力してください" else null
        mutableState.update { it.copy(titleError = titleError, recurrenceError = recurrenceError, error = null) }
        if (titleError != null || recurrenceError != null) return
        val input = validateTaskInput(title, days!!)
        mutate {
            val savedId = repository.saveTask(id, input.title, input.recurrenceDays)
            mutableState.update { it.copy(savedTaskId = savedId) }
        }
    }

    fun saveSettings(start: String, end: String, interval: Int) {
        if (state.value.saving) return
        val startHour = start.toIntOrNull()
        val endHour = end.toIntOrNull()
        val settings = if (startHour != null && endHour != null) ReminderSettings(startHour, endHour, interval) else null
        val valid = settings != null && runCatching { validateSettings(settings) }.isSuccess
        mutableState.update { it.copy(settingsError = if (valid) null else "開始は0〜23時、終了は1〜24時の整数で、開始より後に設定してください。間隔は30・60・120分です。", error = null) }
        if (!valid) return
        mutate {
            repository.saveSettings(settings!!)
            mutableState.update { it.copy(settingsSaved = true) }
        }
    }

    fun completeTask(id: Long) = mutate { repository.completeTask(id) }
    fun setArchived(id: Long, archived: Boolean) = mutate { repository.setArchived(id, archived) }
    fun clearFeedback() = mutableState.update { it.copy(error = null, titleError = null, recurrenceError = null, settingsError = null, savedTaskId = null, settingsSaved = false) }

    private fun mutate(action: suspend () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "保存できませんでした。もう一度お試しください。") } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
