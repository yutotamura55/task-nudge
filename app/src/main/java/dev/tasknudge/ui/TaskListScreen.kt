package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import dev.tasknudge.data.TaskSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun taskStatus(task: TaskSnapshot, now: Instant, zone: ZoneId): String {
    if (task.archived) return "停止中"
    val today = now.atZone(zone).toLocalDate()
    if (task.completions.any { it.localDate == today }) return "今日完了"
    val last = task.completions.maxByOrNull { it.completedAt }
    val next = last?.localDate?.plusDays(task.recurrenceDays.toLong())
    if (next != null && next.isAfter(today)) return "次回対象日 $next"
    if (task.snoozedUntil?.isAfter(now) == true) return "保留中・${task.snoozedUntil.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}まで"
    return "実行できます"
}

@Composable
fun TaskListScreen(
    tasks: List<TaskSnapshot>, showArchived: Boolean, onShowArchived: (Boolean) -> Unit,
    notificationsEnabled: Boolean, permissionRequested: Boolean, onEnableNotifications: () -> Unit,
    saving: Boolean, now: Instant, zone: ZoneId,
    onOpenTask: (Long) -> Unit, onComplete: (Long) -> Unit,
) {
    val today = now.atZone(zone).toLocalDate()
    val active = tasks.filterNot { it.archived }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("今日の完了 ${active.count { task -> task.completions.any { it.localDate == today } }} / ${active.size}", style = MaterialTheme.typography.titleLarge)
        }
        if (!notificationsEnabled) item {
            NotificationNotice(permissionRequested, onEnableNotifications)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onShowArchived(!showArchived) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showArchived) "停止中を非表示" else "停止中も表示")
                }
            }
        }
        val visible = tasks.filter { showArchived || !it.archived }
        if (visible.isEmpty()) item {
            Text(if (tasks.isEmpty()) "まだタスクがありません。＋から、思い出したい行動を追加しましょう。" else "実行中のタスクがありません。停止中のタスクはいつでも再開できます。", style = MaterialTheme.typography.bodyLarge)
        }
        items(visible, key = { it.id }) { task ->
            val largeText = LocalDensity.current.fontScale >= 1.5f
            val details: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = if (largeText) 3 else 2, overflow = TextOverflow.Ellipsis)
                    Text(taskStatus(task, now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("完了 ${task.completions.size}回", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val completeAction: @Composable () -> Unit = {
                if (!task.archived) {
                    TextButton(onClick = { onComplete(task.id) }, enabled = !saving && task.completions.none { it.localDate == today }) {
                        Text(if (task.completions.any { it.localDate == today }) "今日完了済み" else "完了")
                    }
                }
            }
            Card(onClick = { onOpenTask(task.id) }, modifier = Modifier.fillMaxWidth()) {
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
        Surface { TaskListScreen(emptyList(), false, {}, false, false, {}, false, Instant.now(), ZoneId.systemDefault(), {}, {}) }
    }
}
