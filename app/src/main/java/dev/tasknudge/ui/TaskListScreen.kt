package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import dev.tasknudge.data.TaskSnapshot
import java.time.Instant
import java.time.ZoneId

/** What the task list shows. */
data class TaskListContent(
    val tasks: List<TaskSnapshot>,
    val showArchived: Boolean,
    val query: String,
    val notificationsEnabled: Boolean,
    val permissionRequested: Boolean,
    val saving: Boolean,
    val now: Instant,
    val zone: ZoneId,
)

class TaskListActions(
    val onShowArchived: (Boolean) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onEnableNotifications: () -> Unit,
    val onOpenTask: (Long) -> Unit,
    val onComplete: (Long) -> Unit,
)

@Composable
fun TaskListScreen(content: TaskListContent, actions: TaskListActions) {
    val today = content.now.atZone(content.zone).toLocalDate()
    val active = content.tasks.filterNot { it.archived }
    val visible = visibleTasks(content.tasks, content.showArchived, content.query)
    var searchExpanded by rememberSaveable { mutableStateOf(content.query.isNotEmpty()) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("今日の完了 ${active.count { task -> task.completions.any { it.localDate == today } }} / ${active.size}", style = MaterialTheme.typography.titleLarge)
        }
        if (!content.notificationsEnabled) item {
            NotificationNotice(content.permissionRequested, actions.onEnableNotifications)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { actions.onShowArchived(!content.showArchived) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (content.showArchived) "停止中を非表示" else "停止中も表示")
                }
                TextButton(onClick = {
                    if (searchExpanded) actions.onQueryChange("")
                    searchExpanded = !searchExpanded
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (searchExpanded) "検索を閉じる" else "検索")
                }
            }
        }
        if (searchExpanded) item {
            Column {
                OutlinedTextField(
                    value = content.query,
                    onValueChange = actions.onQueryChange,
                    label = { Text("タスクを検索") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("検索結果 ${visible.size}件", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (visible.isEmpty()) item {
            Text(when {
                content.tasks.isEmpty() -> "まだタスクがありません。＋から、思い出したい行動を追加しましょう。"
                content.query.isNotBlank() -> "一致するタスクがありません"
                else -> "実行中のタスクがありません。停止中のタスクはいつでも再開できます。"
            }, style = MaterialTheme.typography.bodyLarge)
        }
        items(visible, key = { it.id }) { task ->
            val largeText = LocalDensity.current.fontScale >= 1.5f
            val canComplete = taskCanComplete(task, content.now, content.zone)
            val details: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = if (largeText) 3 else 2, overflow = TextOverflow.Ellipsis)
                    Text(taskStatus(task, content.now, content.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("完了 ${task.completions.size}回", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val completeAction: @Composable () -> Unit = {
                if (!task.archived) {
                    TextButton(onClick = { actions.onComplete(task.id) }, enabled = !content.saving && canComplete) {
                        Text(completeActionLabel(task, canComplete))
                    }
                }
            }
            Card(onClick = { actions.onOpenTask(task.id) }, modifier = Modifier.fillMaxWidth()) {
                if (largeText) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        details(Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { completeAction() }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        details(Modifier.weight(1f))
                        completeAction()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
internal fun NotificationNotice(permissionRequested: Boolean, onEnable: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("通知が無効です", style = MaterialTheme.typography.titleMedium)
            Text("通知を有効にすると、実行できるタスクをお知らせします。タスクの記録はこのまま使えます。")
            TextButton(onClick = onEnable) { Text(if (permissionRequested) "通知設定を開く" else "通知を有効にする") }
        }
    }
}

@Preview(showBackground = true, locale = "ja")
@Preview(showBackground = true, locale = "ja", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, locale = "ja", fontScale = 2f)
@Composable
private fun TaskListPreview() {
    TaskNudgeTheme(dynamicColor = false) {
        Surface { TaskListScreen(
            TaskListContent(tasks = emptyList(), showArchived = false, query = "", notificationsEnabled = false,
                permissionRequested = false, saving = false, now = Instant.now(), zone = ZoneId.systemDefault()),
            TaskListActions(onShowArchived = {}, onQueryChange = {}, onEnableNotifications = {}, onOpenTask = {}, onComplete = {}),
        ) }
    }
}
