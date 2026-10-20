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
import dev.tasknudge.core.ReminderSettings

@Composable
fun SettingsScreen(settings: ReminderSettings, permissionRequested: Boolean, notificationsEnabled: Boolean, state: TaskUiState, onEnable: () -> Unit, onSave: (String, String, Int) -> Unit, exactAlarmAccessGranted: Boolean = true, onEnableExactAlarm: () -> Unit = {}) {
    var start by rememberSaveable { mutableStateOf(settings.startHour.toString()) }
    var end by rememberSaveable { mutableStateOf(settings.endHour.toString()) }
    var interval by rememberSaveable { mutableIntStateOf(settings.intervalMinutes) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!notificationsEnabled) NotificationNotice(permissionRequested, onEnable)
        if (!exactAlarmAccessGranted) {
            Text("スリープ中の通知", style = MaterialTheme.typography.titleMedium)
            Text("予定時刻に近い通知には、Android の「アラームとリマインダー」の許可が必要です。許可しない場合、通知が遅れることがあります。")
            TextButton(onClick = onEnableExactAlarm) { Text("アラームとリマインダーを許可") }
        }
        Text("通知する時間", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(start, { start = it }, modifier = Modifier.fillMaxWidth(), label = { Text("開始（時）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = state.settingsError != null, supportingText = { Text("0〜23時") })
        OutlinedTextField(end, { end = it }, modifier = Modifier.fillMaxWidth(), label = { Text("終了（時）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = state.settingsError != null, supportingText = { Text("1〜24時。終了時刻は含みません。24時は一日の終わりです。") })
        Text("通知の間隔", style = MaterialTheme.typography.titleMedium)
        // Wrap chips on narrow screens or when fonts are enlarged.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(15, 30, 60, 120).forEach { minutes -> FilterChip(selected = interval == minutes, onClick = { interval = minutes }, label = { Text("${minutes}分") }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settingsError")) }
        Button(onClick = { onSave(start, end, interval) }, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "保存中…" else if (!state.canSubmit) "保存済み" else "保存") }
        HorizontalDivider()
        Text("通知は端末の状態により遅れることがあります。保留は24時間です。強制停止後は、アプリを開くと通知を再開します。")
        Text("データはこの端末内にのみ保存します。クラウド保存・端末移行・エクスポートには対応していません。アプリを削除すると記録も失われます。", style = MaterialTheme.typography.bodyMedium)
    }
}
