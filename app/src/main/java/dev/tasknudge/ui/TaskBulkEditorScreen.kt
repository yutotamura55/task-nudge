package dev.tasknudge.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TaskBulkEditorScreen(state: TaskUiState, onSave: (String, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var recurrence by rememberSaveable { mutableStateOf("1") }
    val count = text.lines().count { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("コピーしたタスクを貼り付けてください。1行ごとに1件追加し、空行は読み飛ばします。", style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("タスク（1行に1件）", style = MaterialTheme.typography.labelLarge)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraSmall,
                border = BorderStroke(1.dp, if (state.bulkError == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error),
            ) {
                NonWrappingBulkInput(text, { text = it })
            }
            Text(
                state.bulkError ?: "一度に100件まで。各タスク名は100文字以内。",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.bulkError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
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

@Composable
private fun NonWrappingBulkInput(value: String, onValueChange: (String) -> Unit) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            BulkTaskEditText(context).apply {
                tag = "bulkTaskInput"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setHorizontallyScrolling(true)
                isHorizontalScrollBarEnabled = true
                minLines = 6
                maxLines = 12
                gravity = Gravity.TOP or Gravity.START
                background = null
                setPadding(padding, padding, padding, padding)
                hint = "タスクを入力または貼り付け"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                setText(value)
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent.requestDisallowInterceptTouchEvent(true)
                    false
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        currentOnValueChange(s?.toString().orEmpty())
                        post {
                            val composingEnd = BaseInputConnection.getComposingSpanEnd(text)
                            if (composingEnd > selectionEnd && selectionEnd >= 0) {
                                bringPointIntoView(composingEnd)
                                val caretX = layout?.getPrimaryHorizontal(selectionEnd)
                                if (caretX != null && scrollX > caretX) scrollTo(caretX.toInt().coerceAtLeast(0), scrollY)
                            } else if (selectionEnd >= 0) {
                                bringPointIntoView(selectionEnd)
                            }
                        }
                    }
                })
            }
        },
        update = { editor ->
            editor.setTextColor(textColor)
            editor.setHintTextColor(hintColor)
            if (editor.text.toString() != value) {
                val selection = editor.selectionStart.coerceIn(0, value.length)
                editor.setText(value)
                editor.setSelection(selection)
            }
        },
    )
}
