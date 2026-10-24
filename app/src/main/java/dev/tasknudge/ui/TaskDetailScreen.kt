package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tasknudge.data.TaskSnapshot
import java.time.Instant
import java.time.ZoneId

class TaskDetailActions(
    val onEdit: () -> Unit = {},
    val onComplete: () -> Unit = {},
    val onArchive: (Boolean) -> Unit = {},
)

@Composable
fun TaskDetailScreen(task: TaskSnapshot, saving: Boolean, now: Instant, zone: ZoneId, actions: TaskDetailActions) {
    val canComplete = taskCanComplete(task, now, zone)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(task.title, style = MaterialTheme.typography.headlineMedium)
            Text(taskStatus(task, now, zone), color = MaterialTheme.colorScheme.primary)
            Text("${task.recurrenceHours?.let { "${it}時間ごと" } ?: "${task.recurrenceDays}日ごと"}・完了 ${task.completions.size}回")
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!task.archived) Button(onClick = actions.onComplete, enabled = !saving && canComplete, modifier = Modifier.fillMaxWidth()) { Text(completeActionLabel(task, canComplete)) }
                OutlinedButton(onClick = actions.onEdit, modifier = Modifier.fillMaxWidth()) { Text("編集") }
                TextButton(onClick = { actions.onArchive(!task.archived) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (task.archived) "再開する" else "停止する") }
                Text("停止しても実行履歴は残ります。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("実行履歴（${task.completions.size}回）", style = MaterialTheme.typography.titleLarge) }
        if (task.completions.isEmpty()) item { Text("まだ実行履歴がありません。") }
        items(task.completions.sortedByDescending { it.completedAt }, key = { it.id }) { record ->
            ListItem(headlineContent = { Text(if (task.recurrenceHours != null)
                formatDateTime(record.completedAt, zone)
                else record.localDate.toString()) }, supportingContent = { Text("完了") })
            HorizontalDivider()
        }
    }
}
