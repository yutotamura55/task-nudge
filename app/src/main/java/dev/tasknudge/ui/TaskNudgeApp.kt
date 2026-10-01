package dev.tasknudge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tasknudge.data.TaskRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

enum class NotificationEnableAction { REQUEST, SETTINGS }
fun notificationEnableAction(api: Int, granted: Boolean, requested: Boolean): NotificationEnableAction =
    if (api >= 33 && !granted && !requested) NotificationEnableAction.REQUEST else NotificationEnableAction.SETTINGS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskNudgeApp(repository: TaskRepository, notificationsEnabled: Boolean, onEnableNotifications: () -> Unit, initialTaskId: Long? = null) {
    val factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TaskViewModel(repository) as T
        }
    }
    val vm: TaskViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (initialTaskId == null) "list" else "detail") }
    var selectedId by rememberSaveable { mutableStateOf(initialTaskId) }
    var routedTaskId by rememberSaveable { mutableStateOf(initialTaskId) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(30_000) }
        }
    }
    LaunchedEffect(initialTaskId) {
        if (initialTaskId != null && initialTaskId != routedTaskId) {
            routedTaskId = initialTaskId; selectedId = initialTaskId; screen = "detail"
        }
    }
    val snapshot = state.snapshot
    val task = snapshot?.tasks?.find { it.id == selectedId }
    LaunchedEffect(screen, selectedId, snapshot) {
        if (!state.loading && snapshot != null && selectedId != null && task == null && (screen == "detail" || screen == "editor")) {
            selectedId = null; screen = "list"
        }
    }
    LaunchedEffect(state.savedTaskId, state.settingsSaved, snapshot) {
        state.savedTaskId?.takeIf { id -> snapshot?.tasks?.any { it.id == id } == true }?.let { id ->
            if (state.feedbackSession == vm.state.value.feedbackSession) {
                if (screen == "editor") { selectedId = id; screen = "detail" }
                vm.clearFeedback()
            }
        }
        if (state.settingsSaved) {
            if (screen == "settings") screen = "list"
            vm.clearFeedback()
        }
    }
    fun back() {
        screen = if (screen == "editor" && selectedId != null) "detail" else "list"
        vm.clearFeedback()
    }
    BackHandler(enabled = screen != "list") { back() }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(when (screen) { "editor" -> if (selectedId == null) "タスクを追加" else "タスクを編集"; "detail" -> "タスクの詳細"; "settings" -> "通知設定"; else -> "タスク" }) },
                navigationIcon = {
                    if (screen != "list") TextButton(onClick = { back() }, modifier = Modifier.semantics { contentDescription = "戻る" }) { Text("戻る") }
                },
                actions = {
                    if (screen == "list") TextButton(onClick = { vm.clearFeedback(); screen = "settings" }, modifier = Modifier.semantics { contentDescription = "通知設定" }) { Text("設定") }
                })
        },
        floatingActionButton = {
            if (screen == "list" && snapshot != null) FloatingActionButton(onClick = { vm.clearFeedback(); selectedId = null; screen = "editor" }, modifier = Modifier.semantics { contentDescription = "タスクを追加" }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                state.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "読み込み中" }) }
                    snapshot == null -> Text("データを表示できません。アプリを開き直してください。", modifier = Modifier.padding(16.dp))
                    screen == "editor" -> TaskEditorScreen(task, state) { title, recurrence -> vm.saveTask(selectedId, title, recurrence) }
                    screen == "settings" -> SettingsScreen(snapshot.settings, snapshot.permissionRequested, notificationsEnabled, state, onEnableNotifications, vm::saveSettings)
                    screen == "detail" && task != null -> TaskDetailScreen(task, state.saving, now, ZoneId.systemDefault(), { vm.clearFeedback(); screen = "editor" }, { vm.completeTask(task.id) }, { vm.setArchived(task.id, it) })
                    else -> TaskListScreen(snapshot.tasks, showArchived, { showArchived = it }, notificationsEnabled, snapshot.permissionRequested, onEnableNotifications, state.saving, now, ZoneId.systemDefault(), { selectedId = it; vm.clearFeedback(); screen = "detail" }, vm::completeTask)
                }
            }
        }
    }
}
