package dev.tasknudge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tasknudge.core.*
import dev.tasknudge.data.BackupCodec
import dev.tasknudge.data.BackupDocument
import dev.tasknudge.data.TaskRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskViewModel(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC(),
    /** Reads and writes picked files off the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskUiState())
    val state = mutableState.asStateFlow()
    private val observationAttempt = MutableStateFlow(0L)
    private var nextSessionId = 0L
    private val backupCodec = BackupCodec()
    /** The validated file waiting for confirmation, owned by the session that picked it. */
    private var pendingImport: Pair<Long, Backup>? = null

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
        pendingImport = null
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

    /** Closes the export or import result so the screen accepts another file. */
    fun dismissBackupResult(sessionId: Long) {
        mutableState.update {
            val result = (it.operation.state as? OperationState.Committed)?.result
            val isBackupResult = result is OperationResult.BackupExported || result is OperationResult.BackupImported
            if (it.operation.sessionId == sessionId && isBackupResult) it.copy(operation = ScreenOperation(sessionId))
            else it
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

    /** Writes the current data to [document]. A failed write tries to remove the partial file. */
    fun exportBackup(document: BackupDocument, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        launchOperation(sessionId, "書き出せませんでした。保存先を確かめて、もう一度お試しください。") {
            val backup = repository.exportBackup()
            withContext(ioDispatcher) {
                var written = false
                try {
                    document.openOutput().use { backupCodec.write(backup, clock.instant(), it) }
                    written = true
                } finally {
                    // Deleting is best effort; the write failure is what the user sees.
                    if (!written) runCatching { document.delete() }
                }
            }
            OperationResult.BackupExported(backup.tasks.size)
        }
    }

    /** Reads and validates [document] without changing data, then asks for confirmation. */
    fun prepareImport(document: BackupDocument, sessionId: Long = state.value.operation.sessionId) {
        if (!canSubmit(sessionId)) return
        pendingImport = null
        mutableState.update { it.copy(operation = ScreenOperation(sessionId, OperationState.Saving)) }
        viewModelScope.launch {
            val next = try {
                val backup = withContext(ioDispatcher) { document.openInput().use(backupCodec::read) }
                if (state.value.operation.sessionId == sessionId) pendingImport = sessionId to backup
                OperationState.ConfirmingImport(backup.preview())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: InvalidBackupException) { OperationState.Failed(invalid.displayMessage()) }
            catch (_: Exception) { OperationState.Failed("ファイルを開けませんでした。もう一度お試しください。") }
            mutableState.update {
                if (it.operation.sessionId == sessionId) it.copy(operation = ScreenOperation(sessionId, next)) else it
            }
        }
    }

    fun confirmImport(sessionId: Long = state.value.operation.sessionId) {
        val (owner, backup) = pendingImport ?: return
        val confirming = ScreenOperation(sessionId, OperationState.ConfirmingImport(backup.preview()))
        if (owner != sessionId || state.value.operation != confirming) return
        pendingImport = null
        launchOperation(sessionId, "読み込めませんでした。データは変わっていません。") {
            repository.replaceWithBackup(backup)
            OperationResult.BackupImported(backup.tasks.size)
        }
    }

    fun cancelImport(sessionId: Long = state.value.operation.sessionId) {
        if (pendingImport?.first == sessionId) pendingImport = null
        mutableState.update {
            if (it.operation.sessionId == sessionId && it.operation.state is OperationState.ConfirmingImport)
                it.copy(operation = ScreenOperation(sessionId)) else it
        }
    }

    private fun canSubmit(sessionId: Long) = state.value.operation.sessionId == sessionId && state.value.canSubmit

    private fun mutate(sessionId: Long, action: suspend () -> OperationResult) {
        if (!canSubmit(sessionId)) return
        launchOperation(sessionId, "保存できませんでした。もう一度お試しください。", action)
    }

    private fun launchOperation(sessionId: Long, failureMessage: String, action: suspend () -> OperationResult) {
        mutableState.update { it.copy(operation = ScreenOperation(sessionId, OperationState.Saving)) }
        viewModelScope.launch {
            val result = try { OperationState.Committed(action()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OperationState.Failed(failureMessage) }
            mutableState.update { if (it.operation.sessionId == sessionId) it.copy(operation = ScreenOperation(sessionId, result)) else it }
        }
    }
}

private fun Backup.preview() = ImportPreview(tasks.size, tasks.sumOf { it.completions.size })

internal fun InvalidBackupException.displayMessage(): String {
    val where = taskNumber?.let { "${it}件目のタスクの" } ?: ""
    val reason = when (problem) {
        BackupProblem.NOT_A_BACKUP -> "このアプリで書き出したファイルではありません。"
        BackupProblem.NEWER_VERSION -> "新しい版のアプリで書き出したファイルです。アプリを更新してから読み込んでください。"
        BackupProblem.TOO_LARGE -> "ファイルが大きすぎます（上限 ${BackupCodec.MAX_MEGABYTES} MB）。"
        BackupProblem.MALFORMED -> "ファイルの形式が壊れています。"
        BackupProblem.SETTINGS -> "通知の時間または間隔が範囲外です。"
        BackupProblem.TOO_MANY_TASKS -> "タスクが${MAX_BACKUP_TASKS}件を超えています。"
        BackupProblem.TITLE -> "${where}名前が${titleLengthRange.first}〜${titleLengthRange.last}文字ではありません。"
        BackupProblem.RECURRENCE -> "${where}繰り返しが範囲外です。"
        BackupProblem.DATE_TIME -> "${where}日時が正しくありません。"
        BackupProblem.DUPLICATE_COMPLETION -> "${where}完了記録が重複しています。"
    }
    return "読み込めませんでした。${reason}データは変わっていません。"
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
