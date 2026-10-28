package dev.tasknudge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** [currentTaskCount] is what an import would replace. */
data class BackupContent(val operation: OperationState, val currentTaskCount: Int) {
    val canStart get() = operation == OperationState.Idle || operation is OperationState.Failed
}

class BackupActions(
    /** Opens the system picker to choose where to save. */
    val onExport: () -> Unit,
    /** Opens the system picker to choose a file to read. */
    val onImport: () -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelImport: () -> Unit,
    val onDismissResult: () -> Unit,
)

@Composable
fun BackupScreen(content: BackupContent, actions: BackupActions) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Shown first, like errors, because a small screen would push it below the buttons it disables.
        if (content.operation == OperationState.Saving) Text("処理中…")
        val message = when (val result = (content.operation as? OperationState.Committed)?.result) {
            is OperationResult.BackupExported -> "タスク ${result.taskCount} 件を書き出しました。"
            is OperationResult.BackupImported -> "タスク ${result.taskCount} 件を読み込みました。"
            else -> null
        }
        message?.let { BackupResult(it, actions.onDismissResult) }
        Text("書き出す", style = MaterialTheme.typography.titleLarge)
        Text(
            "タスク・完了履歴・通知の時間と間隔を、1 つのファイル（JSON 形式）に保存します。" +
                "ファイルにはタスク名と完了日時がそのまま入り、暗号化はしません。保存先は自分で管理してください。",
        )
        Button(onClick = actions.onExport, enabled = content.canStart, modifier = Modifier.fillMaxWidth()) {
            Text("ファイルに書き出す")
        }
        HorizontalDivider()
        Text("読み込む", style = MaterialTheme.typography.titleLarge)
        Text("書き出したファイルを選ぶと、今のタスク・完了履歴・通知の時間と間隔を、ファイルの内容に置き換えます。置き換えた後は元に戻せません。")
        OutlinedButton(onClick = actions.onImport, enabled = content.canStart, modifier = Modifier.fillMaxWidth()) {
            Text("ファイルから読み込む")
        }
    }
    (content.operation as? OperationState.ConfirmingImport)?.let {
        ConfirmImportDialog(it.preview, content.currentTaskCount, actions)
    }
}

@Composable
private fun ConfirmImportDialog(preview: ImportPreview, currentTaskCount: Int, actions: BackupActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelImport,
        title = { Text("データを置き換えますか？") },
        text = {
            Text(
                "ファイルにあるタスク ${preview.taskCount} 件・完了記録 ${preview.completionCount} 件で、" +
                    "今のタスク $currentTaskCount 件と完了履歴、通知の時間と間隔を置き換えます。元に戻せません。",
            )
        },
        confirmButton = { TextButton(onClick = actions.onConfirmImport) { Text("置き換える") } },
        dismissButton = { TextButton(onClick = actions.onCancelImport) { Text("やめる") } },
    )
}

@Composable
private fun BackupResult(message: String, onDismiss: () -> Unit) {
    Text(message, modifier = Modifier.testTag("backupResult"))
    TextButton(onClick = onDismiss) { Text("閉じる") }
}
