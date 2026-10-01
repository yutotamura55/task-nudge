package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tasknudge.data.TaskSnapshot

@Composable
fun TaskEditorScreen(task: TaskSnapshot?, state: TaskUiState, onSave: (String, String) -> Unit) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var recurrence by rememberSaveable(task?.id) { mutableStateOf((task?.recurrenceDays ?: 1).toString()) }
    var showRecurrence by rememberSaveable(task?.id) { mutableStateOf(task != null && task.recurrenceDays != 1) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("タイトルだけで追加できます。毎日思い出したい行動をひとつ。", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("タイトル") },
            modifier = Modifier.fillMaxWidth(), isError = state.titleError != null,
            supportingText = { Text(state.titleError ?: "前後の空白を除き1〜100文字") })
        TextButton(onClick = { showRecurrence = !showRecurrence }) { Text(if (showRecurrence) "繰り返しを閉じる" else "繰り返しを設定") }
        if (showRecurrence) {
            OutlinedTextField(value = recurrence, onValueChange = { recurrence = it }, label = { Text("繰り返し（日）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.recurrenceError != null, supportingText = { Text(state.recurrenceError ?: "1〜365日。最後に完了した日から数えます。") })
        } else {
            Text("繰り返し：${recurrence}日ごと", style = MaterialTheme.typography.bodyMedium)
            state.recurrenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(onClick = { onSave(title, recurrence) }, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.saving) "保存中…" else if (!state.canSubmit) "保存済み" else "保存")
        }
    }
}
