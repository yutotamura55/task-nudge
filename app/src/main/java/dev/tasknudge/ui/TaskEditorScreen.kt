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
import dev.tasknudge.core.*
import dev.tasknudge.data.TaskSnapshot

@Composable
fun TaskEditorScreen(task: TaskSnapshot?, state: TaskUiState, onSave: (String, String, Boolean) -> Unit) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title ?: "") }
    var recurrence by rememberSaveable(task?.id) { mutableStateOf((task?.recurrenceHours ?: task?.recurrenceDays ?: 1).toString()) }
    var hourly by rememberSaveable(task?.id) { mutableStateOf(task?.recurrenceHours != null) }
    var showRecurrence by rememberSaveable(task?.id) { mutableStateOf(task != null && (task.recurrenceDays != 1 || task.recurrenceHours != null)) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("タイトルだけで追加できます。思い出したい行動をひとつ。", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("タイトル") },
            modifier = Modifier.fillMaxWidth(), isError = state.titleError != null,
            supportingText = { Text(state.titleError ?: "前後の空白を除き${titleLengthRange.first}〜${titleLengthRange.last}文字") })
        TextButton(onClick = { showRecurrence = !showRecurrence }) { Text(if (showRecurrence) "繰り返しを閉じる" else "繰り返しを設定") }
        if (showRecurrence) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !hourly, onClick = { hourly = false; recurrence = "1" }, label = { Text("日ごと") })
                FilterChip(selected = hourly, onClick = { hourly = true; recurrence = "3" }, label = { Text("時間ごと") })
            }
            OutlinedTextField(value = recurrence, onValueChange = { recurrence = it }, label = { Text(if (hourly) "繰り返し（時間）" else "繰り返し（日）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.recurrenceError != null, supportingText = { Text(state.recurrenceError ?: if (hourly) "${hourlyRecurrenceRange.first}〜${hourlyRecurrenceRange.last}時間。最後の完了時刻から数えます。" else "${dailyRecurrenceRange.first}〜${dailyRecurrenceRange.last}日。最後に完了した日から数えます。") })
        } else {
            Text("繰り返し：${recurrence}${if (hourly) "時間" else "日"}ごと", style = MaterialTheme.typography.bodyMedium)
            state.recurrenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(onClick = { onSave(title, recurrence, hourly) }, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.saving) "保存中…" else if (!state.canSubmit) "保存済み" else "保存")
        }
    }
}
