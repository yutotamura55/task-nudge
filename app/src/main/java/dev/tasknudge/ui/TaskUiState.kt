package dev.tasknudge.ui

import dev.tasknudge.data.AppSnapshot

sealed interface ObservationStatus {
    data object Loading : ObservationStatus
    data object Ready : ObservationStatus
    data class Failed(val message: String) : ObservationStatus
}

data class ObservationState(
    val snapshot: AppSnapshot? = null,
    val status: ObservationStatus = ObservationStatus.Loading,
)

sealed interface OperationResult {
    data class TaskSaved(val id: Long) : OperationResult
    data class TasksSaved(val count: Int) : OperationResult
    data object SettingsSaved : OperationResult
    data object Updated : OperationResult
}

sealed interface OperationState {
    data object Idle : OperationState
    data object Saving : OperationState
    data class Committed(val result: OperationResult) : OperationState
    data class Failed(val message: String) : OperationState
}

data class ScreenOperation(val sessionId: Long = 0, val state: OperationState = OperationState.Idle)

data class TaskUiState(
    val observation: ObservationState = ObservationState(),
    val operation: ScreenOperation = ScreenOperation(),
    val titleError: String? = null,
    val bulkError: String? = null,
    val recurrenceError: String? = null,
    val settingsError: String? = null,
) {
    val snapshot get() = observation.snapshot
    val loading get() = observation.status == ObservationStatus.Loading
    val saving get() = operation.state == OperationState.Saving
    val canSubmit get() = operation.state == OperationState.Idle || operation.state is OperationState.Failed
}
