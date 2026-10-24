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
import java.time.Clock
import java.time.ZoneId

enum class NotificationEnableAction { REQUEST, SETTINGS }
fun notificationEnableAction(api: Int, granted: Boolean, requested: Boolean): NotificationEnableAction =
    if (api >= 33 && !granted && !requested) NotificationEnableAction.REQUEST else NotificationEnableAction.SETTINGS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskNudgeApp(
    repository: TaskRepository,
    notificationsEnabled: Boolean,
    onEnableNotifications: () -> Unit,
    initialTaskId: Long? = null,
    clock: Clock = Clock.systemUTC(),
    zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    exactAlarmAccessGranted: Boolean = true,
    onEnableExactAlarm: () -> Unit = {},
) {
    val factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TaskViewModel(repository) as T
        }
    }
    val vm: TaskViewModel = viewModel(factory = factory)
    var screen by rememberSaveable { mutableStateOf(if (initialTaskId == null) "list" else "detail") }
    var selectedId by rememberSaveable { mutableStateOf(initialTaskId) }
    var routedTaskId by rememberSaveable { mutableStateOf(initialTaskId) }
    var sessionId by rememberSaveable { mutableLongStateOf(vm.beginScreenSession()) }
    // A retained ViewModel keeps its operation on rotation; a new ViewModel after
    // process recreation adopts the restored route's owner before receiving input.
    remember(vm, sessionId) { vm.beginScreenSession(sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    fun navigate(destination: String, id: Long? = selectedId) {
        sessionId = vm.beginScreenSession()
        selectedId = id
        screen = destination
    }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var now by remember(clock) { mutableStateOf(clock.instant()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, clock) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = clock.instant(); delay(30_000) }
        }
    }
    val zone = zoneProvider()
    LaunchedEffect(initialTaskId) {
        if (initialTaskId != null && initialTaskId != routedTaskId) {
            routedTaskId = initialTaskId; navigate("detail", initialTaskId)
        }
    }
    val snapshot = state.snapshot
    val task = snapshot?.tasks?.find { it.id == selectedId }
    LaunchedEffect(screen, selectedId, state.observation) {
        if (state.observation.status == ObservationStatus.Ready && selectedId != null && task == null && (screen == "detail" || screen == "editor")) {
            navigate("list", null)
        }
    }
    LaunchedEffect(state.operation, snapshot, sessionId) {
        if (state.operation.sessionId == sessionId && vm.state.value.operation.sessionId == sessionId) {
            when (val result = (state.operation.state as? OperationState.Committed)?.result) {
                is OperationResult.TaskSaved -> if (screen == "editor" && snapshot?.tasks?.any { it.id == result.id } == true) navigate("detail", result.id)
                is OperationResult.TasksSaved -> if (screen == "bulk") navigate("list", null)
                OperationResult.SettingsSaved -> if (screen == "settings") navigate("list")
                OperationResult.Updated -> vm.consumeOperation(sessionId)
                null -> Unit
            }
        }
    }
    val owner = sessionId
    val shownScreen = screen
    val shownTaskId = selectedId
    fun navigateFrom(ownerId: Long, destination: String, id: Long?) {
        if (sessionId == ownerId) navigate(destination, id)
    }
    fun backFrom(ownerId: Long, source: String, taskId: Long?) {
        navigateFrom(ownerId, if (source == "editor" && taskId != null) "detail" else "list", taskId)
    }
    BackHandler(enabled = screen != "list") { backFrom(owner, shownScreen, shownTaskId) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(when (screen) { "editor" -> if (selectedId == null) "タスクを追加" else "タスクを編集"; "bulk" -> "まとめて追加"; "detail" -> "タスクの詳細"; "settings" -> "通知設定"; else -> "タスク" }) },
                navigationIcon = {
                    if (screen != "list") TextButton(onClick = { backFrom(owner, shownScreen, shownTaskId) }, modifier = Modifier.semantics { contentDescription = "戻る" }) { Text("戻る") }
                },
                actions = {
                    if (screen == "list") {
                        TextButton(onClick = { navigateFrom(owner, "bulk", null) }, modifier = Modifier.semantics { contentDescription = "まとめて追加" }) { Text("まとめて追加") }
                        TextButton(onClick = { navigateFrom(owner, "settings", shownTaskId) }, modifier = Modifier.semantics { contentDescription = "通知設定" }) { Text("設定") }
                    }
                })
        },
        floatingActionButton = {
            if (screen == "list" && snapshot != null) FloatingActionButton(onClick = { navigateFrom(owner, "editor", null) }, modifier = Modifier.semantics { contentDescription = "タスクを追加" }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                (state.observation.status as? ObservationStatus.Failed)?.let { failure ->
                    Text(failure.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = vm::retryObservation) { Text("再読み込み") }
                }
                (state.operation.state as? OperationState.Failed)?.let { failure ->
                    Text(failure.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = { vm.dismissOperationError(owner) }) { Text("エラーを閉じる") }
                }
                // Keep an existing screen/draft visible during read retry or failure.
                key(sessionId) {
                    when {
                        snapshot == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "読み込み中" }) }
                        snapshot == null -> Text("データを表示できません。再読み込みしてください。", modifier = Modifier.padding(16.dp))
                        screen == "editor" -> {
                            val taskId = shownTaskId
                            val savedId = ((state.operation.state as? OperationState.Committed)?.result as? OperationResult.TaskSaved)?.id
                            if (savedId != null && snapshot.tasks.none { it.id == savedId }) Text("保存済み・データ表示待ち", modifier = Modifier.padding(16.dp))
                            TaskEditorScreen(task, state) { title, recurrence, hourly -> vm.saveTask(taskId, title, recurrence, owner, hourly) }
                        }
                        screen == "bulk" -> TaskBulkEditorScreen(state) { text, recurrence -> vm.saveTasks(text, recurrence, owner) }
                        screen == "settings" -> SettingsScreen(snapshot.settings, snapshot.permissionRequested, notificationsEnabled, state,
                            { if (sessionId == owner) onEnableNotifications() },
                            { start, end, interval -> vm.saveSettings(start, end, interval, owner) },
                            exactAlarmAccessGranted, { if (sessionId == owner) onEnableExactAlarm() })
                        screen == "detail" && task != null -> {
                            val taskId = task.id
                            TaskDetailScreen(task, !state.canSubmit, now, zone, { navigateFrom(owner, "editor", taskId) }, { vm.completeTask(taskId, owner) }, { vm.setArchived(taskId, it, owner) })
                        }
                        else -> TaskListScreen(snapshot.tasks, showArchived, { if (sessionId == owner) showArchived = it }, searchQuery, { if (sessionId == owner) searchQuery = it }, notificationsEnabled, snapshot.permissionRequested, { if (sessionId == owner) onEnableNotifications() }, !state.canSubmit, now, zone, { navigateFrom(owner, "detail", it) }, { vm.completeTask(it, owner) })
                    }
                }
            }
        }
    }
}
