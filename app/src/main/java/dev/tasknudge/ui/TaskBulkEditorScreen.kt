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

@Composable
fun TaskBulkEditorScreen(state: TaskUiState, onSave: (String, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var recurrence by rememberSaveable { mutableStateOf("1") }
    val count = text.lines().count { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("コピーしたタスクを貼り付けてください。1行ごとに1件追加し、空行は読み飛ばします。", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("タスク（1行に1件）") },
            modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12,
            isError = state.bulkError != null,
            supportingText = { Text(state.bulkError ?: "一度に100件まで。各タスク名は100文字以内。") },
        )
        Text("追加予定：${count}件", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = recurrence, onValueChange = { recurrence = it }, label = { Text("繰り返し（日）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = state.recurrenceError != null,
            supportingText = { Text(state.recurrenceError ?: "全件共通。1〜365日、初期値は1日です。") },
        )
        Button(onClick = { onSave(text, recurrence) }, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.saving) "保存中…" else if (!state.canSubmit) "保存済み" else "まとめて保存")
        }
    }
}
