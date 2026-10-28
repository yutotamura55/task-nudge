package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tasknudge.core.*

/** Saved settings and the device permissions the settings screen explains. */
data class SettingsContent(
    val settings: ReminderSettings,
    val permissionRequested: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarmAccessGranted: Boolean,
)

class SettingsActions(
    val onEnableNotifications: () -> Unit,
    /** Receives the entered start and end hours as typed, and the selected interval in minutes. */
    val onSave: (String, String, Int) -> Unit,
    val onEnableExactAlarm: () -> Unit,
    val onOpenBackup: () -> Unit,
)

@Composable
fun SettingsScreen(content: SettingsContent, state: TaskUiState, actions: SettingsActions) {
    var start by rememberSaveable { mutableStateOf(content.settings.startHour.toString()) }
    var end by rememberSaveable { mutableStateOf(content.settings.endHour.toString()) }
    var interval by rememberSaveable { mutableIntStateOf(content.settings.intervalMinutes) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!content.notificationsEnabled) NotificationNotice(content.permissionRequested, actions.onEnableNotifications)
        if (!content.exactAlarmAccessGranted) {
            Text("スリープ中の通知", style = MaterialTheme.typography.titleMedium)
            Text("予定時刻に近い通知には、Android の「アラームとリマインダー」の許可が必要です。許可しない場合、通知が遅れることがあります。")
            TextButton(onClick = actions.onEnableExactAlarm) { Text("アラームとリマインダーを許可") }
        }
        Text("通知する時間", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(start, { start = it }, modifier = Modifier.fillMaxWidth(), label = { Text("開始（時）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = state.settingsError != null, supportingText = { Text("${startHourRange.first}〜${startHourRange.last}時") })
        OutlinedTextField(end, { end = it }, modifier = Modifier.fillMaxWidth(), label = { Text("終了（時）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = state.settingsError != null, supportingText = { Text("${endHourRange.first}〜${endHourRange.last}時。終了時刻は含みません。24時は一日の終わりです。") })
        Text("通知の間隔", style = MaterialTheme.typography.titleMedium)
        // Wrap chips on narrow screens or when fonts are enlarged.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            notificationIntervalOptions.forEach { minutes -> FilterChip(selected = interval == minutes, onClick = { interval = minutes }, label = { Text("${minutes}分") }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settingsError")) }
        Button(onClick = { actions.onSave(start, end, interval) }, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else if (!state.canSubmit) "保存済み" else "保存") }
        HorizontalDivider()
        Text("通知は端末の状態により遅れることがあります。保留は24時間です。強制停止後は、アプリを開くと通知を再開します。")
        Text(
            "データはこの端末内にのみ保存し、クラウドには保存しません。アプリを削除すると記録も失われます。" +
                "削除する前や別の版に入れ替える前に、ファイルに書き出してください。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = actions.onOpenBackup, modifier = Modifier.fillMaxWidth()) { Text("データの書き出しと読み込み") }
    }
}
