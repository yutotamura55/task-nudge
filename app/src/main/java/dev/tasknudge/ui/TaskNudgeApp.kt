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
    var route by rememberSaveable(stateSaver = Route.saver) {
        mutableStateOf(if (initialTaskId == null) Route.TaskList else Route.Detail(initialTaskId))
    }
    var routedTaskId by rememberSaveable { mutableStateOf(initialTaskId) }
    var sessionId by rememberSaveable { mutableLongStateOf(vm.beginScreenSession()) }
    // A retained ViewModel keeps its operation on rotation; a new ViewModel after
    // process recreation adopts the restored route's owner before receiving input.
    remember(vm, sessionId) { vm.beginScreenSession(sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    fun navigate(destination: Route) {
        sessionId = vm.beginScreenSession()
        route = destination
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
            routedTaskId = initialTaskId; navigate(Route.Detail(initialTaskId))
        }
    }
    val snapshot = state.snapshot
    val task = route.requiredTaskId?.let { id -> snapshot?.tasks?.find { it.id == id } }
    LaunchedEffect(route, state.observation) {
        if (state.observation.status == ObservationStatus.Ready && route.requiredTaskId != null && task == null) {
            navigate(Route.TaskList)
        }
    }
    LaunchedEffect(state.operation, snapshot, sessionId) {
        if (state.operation.sessionId == sessionId && vm.state.value.operation.sessionId == sessionId) {
            when (val result = (state.operation.state as? OperationState.Committed)?.result) {
                is OperationResult.TaskSaved -> if (route is Route.Editor && snapshot?.tasks?.any { it.id == result.id } == true) navigate(Route.Detail(result.id))
                is OperationResult.TasksSaved -> if (route == Route.Bulk) navigate(Route.TaskList)
                OperationResult.SettingsSaved -> if (route == Route.Settings) navigate(Route.TaskList)
                OperationResult.Updated -> vm.consumeOperation(sessionId)
                null -> Unit
            }
        }
    }
    // Callbacks capture the session that created them. After navigation, a late
    // tap from the previous screen must not act on the newly shown screen.
    val owner = sessionId
    val shownRoute = route
    fun fromOwner(action: () -> Unit) { if (sessionId == owner) action() }
    fun navigateFromOwner(destination: Route) = fromOwner { navigate(destination) }
    BackHandler(enabled = route != Route.TaskList) { navigateFromOwner(shownRoute.back) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(route.title) },
                navigationIcon = {
                    if (route != Route.TaskList) TextButton(onClick = { navigateFromOwner(shownRoute.back) }, modifier = Modifier.semantics { contentDescription = "戻る" }) { Text("戻る") }
                },
                actions = {
                    if (route == Route.TaskList) {
                        TextButton(onClick = { navigateFromOwner(Route.Bulk) }, modifier = Modifier.semantics { contentDescription = "まとめて追加" }) { Text("まとめて追加") }
                        TextButton(onClick = { navigateFromOwner(Route.Settings) }, modifier = Modifier.semantics { contentDescription = "通知設定" }) { Text("設定") }
                    }
                })
        },
        floatingActionButton = {
            if (route == Route.TaskList && snapshot != null) FloatingActionButton(onClick = { navigateFromOwner(Route.Editor(null)) }, modifier = Modifier.semantics { contentDescription = "タスクを追加" }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
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
                    val screen = route
                    when {
                        snapshot == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "読み込み中" }) }
                        snapshot == null -> Text("データを表示できません。再読み込みしてください。", modifier = Modifier.padding(16.dp))
                        screen is Route.Editor -> {
                            val savedId = ((state.operation.state as? OperationState.Committed)?.result as? OperationResult.TaskSaved)?.id
                            if (savedId != null && snapshot.tasks.none { it.id == savedId }) Text("保存済み・データ表示待ち", modifier = Modifier.padding(16.dp))
                            TaskEditorScreen(task, state) { title, recurrence, hourly -> vm.saveTask(screen.taskId, title, recurrence, owner, hourly) }
                        }
                        screen == Route.Bulk -> TaskBulkEditorScreen(state) { text, recurrence -> vm.saveTasks(text, recurrence, owner) }
                        screen == Route.Settings -> SettingsScreen(
                            SettingsContent(
                                settings = snapshot.settings, permissionRequested = snapshot.permissionRequested,
                                notificationsEnabled = notificationsEnabled, exactAlarmAccessGranted = exactAlarmAccessGranted,
                            ),
                            state,
                            SettingsActions(
                                onEnableNotifications = { fromOwner(onEnableNotifications) },
                                onSave = { start, end, interval -> vm.saveSettings(start, end, interval, owner) },
                                onEnableExactAlarm = { fromOwner(onEnableExactAlarm) },
                            ),
                        )
                        screen is Route.Detail && task != null -> TaskDetailScreen(task, !state.canSubmit, now, zone, TaskDetailActions(
                            onEdit = { navigateFromOwner(Route.Editor(screen.taskId)) },
                            onComplete = { vm.completeTask(screen.taskId, owner) },
                            onArchive = { vm.setArchived(screen.taskId, it, owner) },
                        ))
                        else -> TaskListScreen(
                            TaskListContent(
                                tasks = snapshot.tasks, showArchived = showArchived, query = searchQuery,
                                notificationsEnabled = notificationsEnabled, permissionRequested = snapshot.permissionRequested,
                                saving = !state.canSubmit, now = now, zone = zone,
                            ),
                            TaskListActions(
                                onShowArchived = { fromOwner { showArchived = it } },
                                onQueryChange = { fromOwner { searchQuery = it } },
                                onEnableNotifications = { fromOwner(onEnableNotifications) },
                                onOpenTask = { navigateFromOwner(Route.Detail(it)) },
                                onComplete = { vm.completeTask(it, owner) },
                            ),
                        )
                    }
                }
            }
        }
    }
}
