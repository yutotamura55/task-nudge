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

@Composable
fun TaskDetailScreen(task: TaskSnapshot, saving: Boolean, now: Instant, zone: ZoneId, onEdit: () -> Unit, onComplete: () -> Unit, onArchive: (Boolean) -> Unit) {
    val completedToday = task.completions.any { it.localDate == now.atZone(zone).toLocalDate() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(task.title, style = MaterialTheme.typography.headlineMedium)
            Text(taskStatus(task, now, zone), color = MaterialTheme.colorScheme.primary)
            Text("${task.recurrenceDays}日ごと・完了 ${task.completions.size}回")
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!task.archived) Button(onClick = onComplete, enabled = !saving && !completedToday, modifier = Modifier.fillMaxWidth()) { Text(if (completedToday) "今日完了済み" else "完了") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("編集") }
                TextButton(onClick = { onArchive(!task.archived) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (task.archived) "再開する" else "停止する") }
                Text("停止しても実行履歴は残ります。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("実行履歴（${task.completions.size}回）", style = MaterialTheme.typography.titleLarge) }
        if (task.completions.isEmpty()) item { Text("まだ実行履歴がありません。") }
        items(task.completions.sortedByDescending { it.completedAt }, key = { it.id }) { record ->
            ListItem(headlineContent = { Text(record.localDate.toString()) }, supportingContent = { Text("完了") })
            HorizontalDivider()
        }
    }
}
