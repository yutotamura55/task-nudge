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

class ReminderEngine(
    private val database: TaskDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val gateway: NotificationGateway,
    private val scheduler: ReminderScheduler,
) {
    private val mutex = Mutex()
    private val dao = database.dao()
    private val snoozeDuration = Duration.ofDays(1)
    private val osVisibilityGrace = Duration.ofMinutes(5)
    private val maxVisibleReminders = 40

    val repository: TaskRepository = object : TaskRepository {
        override val snapshots = combine(dao.observeTasks(), dao.observeSettings()) { tasks, settings ->
            AppSnapshot(tasks.map { it.snapshot() }, settings.core(), settings.permissionRequested)
        }
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
        reconcileLocked()
        var invalidated = emptyList<ActiveReminderEntity>()
        val result = database.withTransaction {
            val value = write()
            if (invalidateTaskId != null) {
                invalidated = dao.activeReminders().filter { it.taskId == invalidateTaskId }
                dao.deleteTaskReminders(invalidateTaskId)
            }
            value
        }
        // A committed write is not made retryable by OS or scheduling failures.
        maintainAfterCommit("cancel") { cancelMatching(invalidated) }
        maintainAfterCommit("reconcile") { reconcileLocked() }
        maintainAfterCommit("schedule") { scheduler.requestEvaluation() }
        result
    }

    private suspend fun maintainAfterCommit(operation: String, action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w("ReminderEngine", "Post-commit $operation failed: ${failure.javaClass.simpleName}") }
    }

    private suspend fun complete(id: Long) {
        val task = dao.task(id) ?: return
        val now = clock.instant()
        dao.insertCompletion(CompletionEntity(taskId = id, completedAt = now.toEpochMilli(), localDate = now.atZone(zoneProvider()).toLocalDate().toString()))
        dao.updateTask(task.copy(snoozedUntil = null))
    }

    suspend fun evaluate() = mutex.withLock {
        var nextSettings: ReminderSettings? = null
        try {
        reconcileLocked()
        val now = clock.instant()
        val zone = zoneProvider()
        val settings = dao.settings().core()
        nextSettings = settings
        if (!gateway.canNotify()) return@withLock
        val reminders = dao.activeReminders()
        val lastByTask = reminders.filter { it.deliveredAt != null }
            .groupBy { it.taskId }
            .mapValues { (_, values) -> Instant.ofEpochMilli(values.maxOf { requireNotNull(it.deliveredAt) }) }
        val tasks = dao.tasks()
        val id = ReminderPolicy.select(
            tasks.map { it.policy(now.atZone(zone).toLocalDate()) }, now, zone, settings,
            dao.state().lastNotifiedAt?.let(Instant::ofEpochMilli), lastByTask,
        ) ?: return@withLock
        val task = tasks.first { it.task.id == id }.task
        // Leave room below the platform's active-notification limit.
        if (reminders.size >= maxVisibleReminders) {
            val oldest = reminders.first()
            database.withTransaction { dao.deleteActiveReminder(oldest.notificationId) }
            cancelMatching(listOf(oldest))
        }
        val token = UUID.randomUUID().toString()
        val notificationId = database.withTransaction {
            dao.insertActiveReminder(ActiveReminderEntity(
                taskId = id, token = token, localDate = now.atZone(zone).toLocalDate().toString(),
                preparedAt = now.toEpochMilli(),
            )).toInt()
        }
        try {
            gateway.post(notificationId, id, token, task.title)
        } catch (failure: RuntimeException) {
            if (failure is CancellationException) throw failure
            database.withTransaction { dao.deleteActiveReminder(notificationId) }
            if (gateway.activeTokens()[notificationId] == token) gateway.cancel(notificationId)
            return@withLock
        }
        database.withTransaction {
            dao.markDelivered(notificationId, now.toEpochMilli())
            dao.saveState(dao.state().copy(lastNotifiedAt = now.toEpochMilli()))
        }
        } finally {
            val alarmNow = clock.instant()
            val alarmSettings = nextSettings
            val next = if (alarmSettings == null) alarmNow.plus(Duration.ofMinutes(15))
                else nextEvaluationAt(alarmNow, zoneProvider(), alarmSettings, dao.state().lastNotifiedAt)
            scheduler.scheduleWhileIdle(next)
        }
    }

    private fun nextEvaluationAt(now: Instant, zone: ZoneId, settings: ReminderSettings, lastNotifiedAt: Long?): Instant {
        val local = now.atZone(zone)
        if (!ReminderPolicy.isWithinHours(now, zone, settings)) {
            val todayStart = local.toLocalDate().atTime(settings.startHour, 0).atZone(zone).toInstant()
            return if (now.isBefore(todayStart)) todayStart else local.toLocalDate().plusDays(1)
                .atTime(settings.startHour, 0).atZone(zone).toInstant()
        }
        val interval = Duration.ofMinutes(settings.intervalMinutes.toLong())
        val due = lastNotifiedAt?.let { Instant.ofEpochMilli(it).plus(interval) }
        return if (due != null && now.isBefore(due)) due else now.plus(interval)
    }

    suspend fun handleAction(taskId: Long, token: String, action: ReminderAction) = mutex.withLock {
        val reminder = dao.activeReminder(token) ?: return@withLock
        if (reminder.taskId != taskId || dao.task(taskId) == null) return@withLock
        val now = clock.instant()
        val zone = zoneProvider()
        val task = dao.taskWithCompletions(taskId)
        if (!isActiveValid(reminder, task, now, zone, dao.settings().core())) {
            database.withTransaction { dao.deleteActiveReminder(reminder.notificationId) }
            cancelMatching(listOf(reminder))
            scheduler.requestEvaluation()
            return@withLock
        }
        val related = dao.activeReminders().filter { it.taskId == taskId }
        database.withTransaction {
            when (action) {
                ReminderAction.COMPLETE -> complete(taskId)
                ReminderAction.SNOOZE, ReminderAction.DISMISS -> {
                    val current = requireNotNull(dao.task(taskId))
                    dao.updateTask(current.copy(snoozedUntil = now.plus(snoozeDuration).toEpochMilli()))
                }
            }
            if (reminder.deliveredAt == null) {
                val state = dao.state()
                val prepared = reminder.preparedAt
                dao.saveState(state.copy(lastNotifiedAt = maxOf(state.lastNotifiedAt ?: prepared, prepared)))
            }
            dao.deleteTaskReminders(taskId)
        }
        cancelMatching(related)
        scheduler.requestEvaluation()
    }

    private suspend fun reconcileLocked() {
        val now = clock.instant()
        val zone = zoneProvider()
        val settings = dao.settings().core()
        val os = gateway.activeTokens()
        val reminders = dao.activeReminders()
        val known = reminders.associate { it.notificationId to it.token }
        for ((id, token) in os) if (known[id] != token) gateway.cancel(id)
        val removedTasks = mutableSetOf<Long>()
        for (reminder in reminders) {
            if (reminder.taskId in removedTasks) continue
            val task = dao.taskWithCompletions(reminder.taskId)
            if (!isActiveValid(reminder, task, now, zone, settings) || !gateway.canNotify()) {
                database.withTransaction { dao.deleteActiveReminder(reminder.notificationId) }
                if (os[reminder.notificationId] == reminder.token) gateway.cancel(reminder.notificationId)
            } else if (os[reminder.notificationId] == reminder.token) {
                if (reminder.deliveredAt == null) database.withTransaction {
                    dao.markDelivered(reminder.notificationId, reminder.preparedAt)
                    val state = dao.state()
                    dao.saveState(state.copy(lastNotifiedAt = maxOf(state.lastNotifiedAt ?: reminder.preparedAt, reminder.preparedAt)))
                }
            } else if (os[reminder.notificationId] != null) {
                // Another generation has the same OS ID; neither token is a
                // dismissal of the saved reminder.
                database.withTransaction { dao.deleteActiveReminder(reminder.notificationId) }
            } else if (reminder.deliveredAt == null) {
                database.withTransaction { dao.deleteActiveReminder(reminder.notificationId) }
            } else if (!now.isBefore(Instant.ofEpochMilli(reminder.deliveredAt).plus(osVisibilityGrace))) {
                // The delete intent normally snoozes immediately. Keep the row while a
                // delayed broadcast may still arrive, then recover if it never does.
                val related = dao.activeReminders().filter { it.taskId == reminder.taskId }
                database.withTransaction {
                    val current = requireNotNull(dao.task(reminder.taskId))
                    dao.updateTask(current.copy(snoozedUntil = now.plus(snoozeDuration).toEpochMilli()))
                    dao.deleteTaskReminders(reminder.taskId)
                }
                for (item in related) if (os[item.notificationId] == item.token) gateway.cancel(item.notificationId)
                removedTasks += reminder.taskId
            }
        }
    }

    private fun isActiveValid(reminder: ActiveReminderEntity, task: TaskWithCompletions?, now: Instant, zone: ZoneId, settings: ReminderSettings): Boolean =
        reminder.localDate == now.atZone(zone).toLocalDate().toString() && task != null &&
            ReminderPolicy.select(listOf(task.policy(now.atZone(zone).toLocalDate())), now, zone, settings, null) == reminder.taskId

    private fun cancelMatching(reminders: List<ActiveReminderEntity>) {
        if (reminders.isEmpty()) return
        val os = gateway.activeTokens()
        for (reminder in reminders) if (os[reminder.notificationId] == reminder.token) gateway.cancel(reminder.notificationId)
    }

    private fun TaskWithCompletions.policy(today: LocalDate): ReminderTask {
        val latest = completions.maxByOrNull { it.completedAt }
        return ReminderTask(task.id, Instant.ofEpochMilli(task.createdAt), task.recurrenceDays, task.archived,
            latest?.completedAt?.let(Instant::ofEpochMilli), latest?.localDate?.let(LocalDate::parse),
            completions.any { it.localDate == today.toString() }, task.snoozedUntil?.let(Instant::ofEpochMilli))
    }
}
