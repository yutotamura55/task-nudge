package dev.tasknudge.reminders

import android.util.Log
import androidx.room.withTransaction
import dev.tasknudge.core.*
import dev.tasknudge.data.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReminderEngine(private val database: TaskDatabase, private val clock: Clock = Clock.systemUTC(), private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }, private val gateway: NotificationGateway, private val scheduler: ReminderScheduler) {
    private val mutex = Mutex()
    private val dao = database.dao()
    private val snoozeDuration = Duration.ofDays(1)
    val repository: TaskRepository = object : TaskRepository {
        override val snapshots = combine(dao.observeTasks(), dao.observeSettings()) { tasks, settings -> AppSnapshot(tasks.map { it.snapshot() }, settings.core(), settings.permissionRequested) }
        override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int): Long {
            val input = validateTaskInput(title, recurrenceDays)
            return mutate(id) {
                if (id == null) dao.insertTask(TaskEntity(title = input.title, recurrenceDays = input.recurrenceDays, createdAt = clock.millis()))
                else { val task = requireNotNull(dao.task(id)) { "Unknown task" }; dao.updateTask(task.copy(title = input.title, recurrenceDays = input.recurrenceDays)); id }
            }
        }
        override suspend fun setArchived(id: Long, archived: Boolean) { mutate(id) { dao.task(id)?.let { dao.updateTask(it.copy(archived = archived)) } } }
        override suspend fun completeTask(id: Long) { mutate(id) { complete(id) } }
        override suspend fun saveSettings(settings: ReminderSettings) { validateSettings(settings); mutate(null) { dao.saveSettings(dao.settings().copy(startHour = settings.startHour, endHour = settings.endHour, intervalMinutes = settings.intervalMinutes)) } }
        override suspend fun recordPermissionRequest() { mutate(null) { dao.saveSettings(dao.settings().copy(permissionRequested = true)) } }
    }
    private suspend fun <T> mutate(invalidateTaskId: Long?, write: suspend () -> T): T = mutex.withLock {
        // Confirm a visible prepared generation before a UI write clears it.
        reconcileLocked()
        var cancelledToken: String? = null
        val result = database.withTransaction {
            val value = write()
            val state = dao.state()
            if (invalidateTaskId != null && state.activeTaskId == invalidateTaskId) {
                cancelledToken = state.activeToken
                dao.saveState(state.cleared())
            }
            value
        }
        // The transaction has committed. Maintenance cannot make retrying this
        // command safe; periodic work and startup reconciliation can recover it.
        maintainAfterCommit("cancel") {
            if (cancelledToken != null && gateway.activeToken() == cancelledToken) gateway.cancel()
        }
        maintainAfterCommit("reconcile") { reconcileLocked() }
        maintainAfterCommit("schedule") { scheduler.requestEvaluation() }
        result
    }
    private suspend fun maintainAfterCommit(operation: String, action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Exception messages may contain task text. Log only the boundary and type.
            Log.w("ReminderEngine", "Post-commit $operation failed: ${failure.javaClass.simpleName}")
        }
    }
    private suspend fun complete(id: Long) {
        val task = dao.task(id) ?: return
        val now = clock.instant()
        dao.insertCompletion(CompletionEntity(taskId = id, completedAt = now.toEpochMilli(), localDate = now.atZone(zoneProvider()).toLocalDate().toString()))
        dao.updateTask(task.copy(snoozedUntil = null))
    }
    suspend fun evaluate() = mutex.withLock {
        reconcileLocked()
        val now = clock.instant()
        val zone = zoneProvider()
        val state = dao.state()
        val settings = dao.settings().core()
        if (state.activeToken != null || !gateway.canNotify()) return@withLock
        val tasks = dao.tasks()
        val id = ReminderPolicy.select(tasks.map { it.policy(now.atZone(zone).toLocalDate()) }, now, zone, settings, state.lastNotifiedAt?.let(Instant::ofEpochMilli), false) ?: return@withLock
        val task = tasks.first { it.task.id == id }.task
        val token = UUID.randomUUID().toString()
        val reservation = state.copy(activeTaskId = id, activeToken = token, activeLocalDate = now.atZone(zone).toLocalDate().toString(), preparedAt = now.toEpochMilli(), deliveredAt = null)
        database.withTransaction { dao.saveState(reservation) }
        try {
            gateway.post(id, token, task.title)
        } catch (failure: RuntimeException) {
            if (failure is CancellationException) throw failure
            database.withTransaction { dao.saveState(reservation.cleared()) }
            if (gateway.activeToken() == token) gateway.cancel()
            return@withLock
        }
        database.withTransaction {
            dao.saveState(reservation.copy(deliveredAt = now.toEpochMilli(), lastNotifiedAt = now.toEpochMilli()))
        }
    }

    suspend fun handleAction(taskId: Long, token: String, action: ReminderAction) = mutex.withLock {
        val state = dao.state()
        // An old PendingIntent can be delivered after a new generation has replaced it.
        if (state.activeTaskId != taskId || state.activeToken != token || dao.task(taskId) == null) return@withLock
        val now = clock.instant()
        val zone = zoneProvider()
        val today = now.atZone(zone).toLocalDate()
        val task = dao.taskWithCompletions(taskId)
        if (!isActiveValid(state, task, now, zone, today, dao.settings().core())) {
            database.withTransaction { dao.saveState(state.cleared()) }
            if (gateway.activeToken() == token) gateway.cancel()
            scheduler.requestEvaluation()
            return@withLock
        }
        database.withTransaction {
            when (action) {
                ReminderAction.COMPLETE -> complete(taskId)
                ReminderAction.SNOOZE, ReminderAction.DISMISS -> {
                    val task = requireNotNull(dao.task(taskId))
                    dao.updateTask(task.copy(snoozedUntil = clock.instant().plus(snoozeDuration).toEpochMilli()))
                }
            }
            // A matching action proves delivery even if deleteIntent has already
            // removed the OS notification before a crashed process finalized it.
            val delivered = if (state.deliveredAt == null) state.copy(lastNotifiedAt = requireNotNull(state.preparedAt)) else state
            dao.saveState(delivered.cleared())
        }
        if (gateway.activeToken() == token) gateway.cancel()
        scheduler.requestEvaluation()
    }

    private suspend fun reconcileLocked() {
        val state = dao.state()
        val osToken = gateway.activeToken()
        val token = state.activeToken
        if (token == null) {
            if (osToken != null) gateway.cancel()
            return
        }
        val now = clock.instant()
        val zone = zoneProvider()
        val today = now.atZone(zone).toLocalDate()
        val task = state.activeTaskId?.let { dao.taskWithCompletions(it) }
        val valid = isActiveValid(state, task, now, zone, today, dao.settings().core())
        // Date/hour/task invalidation precedes missing-notification recovery: yesterday
        // or an archived task must never acquire a new snooze.
        if (!valid || !gateway.canNotify()) {
            database.withTransaction { dao.saveState(state.cleared()) }
            if (osToken != null) gateway.cancel()
        } else if (osToken == token) {
            if (state.deliveredAt == null) {
                val prepared = requireNotNull(state.preparedAt)
                database.withTransaction { dao.saveState(state.copy(deliveredAt = prepared, lastNotifiedAt = prepared)) }
            }
        } else {
            database.withTransaction {
                if (osToken == null && state.deliveredAt != null) {
                    dao.updateTask(requireNotNull(task).task.copy(snoozedUntil = now.plus(snoozeDuration).toEpochMilli()))
                }
                dao.saveState(state.cleared())
            }
            if (osToken != null) gateway.cancel()
        }
    }

    private fun isActiveValid(state: ReminderStateEntity, task: TaskWithCompletions?, now: Instant, zone: ZoneId, today: LocalDate, settings: ReminderSettings): Boolean =
        state.activeLocalDate == today.toString() && task != null &&
            ReminderPolicy.select(listOf(task.policy(today)), now, zone, settings, null, false) == state.activeTaskId

    private fun TaskWithCompletions.policy(today: LocalDate): ReminderTask {
        val latest = completions.maxByOrNull { it.completedAt }
        return ReminderTask(task.id, Instant.ofEpochMilli(task.createdAt), task.recurrenceDays, task.archived,
            latest?.completedAt?.let(Instant::ofEpochMilli), latest?.localDate?.let(LocalDate::parse),
            completions.any { it.localDate == today.toString() }, task.snoozedUntil?.let(Instant::ofEpochMilli))
    }
}
