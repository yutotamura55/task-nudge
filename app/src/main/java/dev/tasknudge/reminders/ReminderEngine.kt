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
        override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int, recurrenceHours: Int?): Long {
            val input = validateTaskInput(title, recurrenceDays)
            require(recurrenceHours == null || recurrenceError(recurrenceHours, hourly = true) == null) { "Recurrence must be $hourlyRecurrenceRange hours" }
            return mutate(id) {
                if (id == null) dao.insertTask(TaskEntity(title = input.title, recurrenceDays = input.recurrenceDays, recurrenceHours = recurrenceHours, createdAt = clock.millis()))
                else {
                    val task = requireNotNull(dao.task(id)) { "Unknown task" }
                    dao.updateTask(task.copy(title = input.title, recurrenceDays = input.recurrenceDays, recurrenceHours = recurrenceHours))
                    id
                }
            }
        }
        override suspend fun saveTasks(inputs: List<TaskInput>): List<Long> {
            require(inputs.isNotEmpty() && inputs.size <= maxBulkTasks) { "Bulk input must contain 1 to $maxBulkTasks tasks" }
            val validated = inputs.map { validateTaskInput(it.title, it.recurrenceDays) }
            return mutate(null) {
                val createdAt = clock.millis()
                validated.map { input ->
                    dao.insertTask(TaskEntity(title = input.title, recurrenceDays = input.recurrenceDays, createdAt = createdAt))
                }
            }
        }
        override suspend fun setArchived(id: Long, archived: Boolean) { mutate(id) { dao.task(id)?.let { dao.updateTask(it.copy(archived = archived)) } } }
        override suspend fun completeTask(id: Long) { mutate(id) { complete(id) } }
        override suspend fun saveSettings(settings: ReminderSettings) {
            validateSettings(settings)
            mutate(null) { saveReminderSettings(settings) }
        }
        override suspend fun recordPermissionRequest() { mutate(null) { dao.saveSettings(dao.settings().copy(permissionRequested = true)) } }
        override suspend fun exportBackup(): Backup = database.withTransaction {
            val settings = dao.settings().core()
            Backup(settings, dao.tasks().map { it.backupTask() })
        }
        override suspend fun replaceWithBackup(backup: Backup) {
            validateBackup(backup)
            // Deleting the stored reminders lets the reconciliation after the commit
            // cancel every notification that still shows the previous data.
            mutate(null) {
                dao.deleteAllReminders()
                dao.deleteAllCompletions()
                dao.deleteAllTasks()
                for (task in backup.tasks) {
                    val id = dao.insertTask(task.entity())
                    dao.insertCompletions(task.completions.map { it.entity(id) })
                }
                saveReminderSettings(backup.settings)
            }
        }
    }

    /** Keeps the permission request, which belongs to this device rather than to the user's settings. */
    private suspend fun saveReminderSettings(settings: ReminderSettings) {
        val (startHour, endHour, interval) = settings
        dao.saveSettings(dao.settings().copy(startHour = startHour, endHour = endHour, intervalMinutes = interval))
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
        val today = now.atZone(zoneProvider()).toLocalDate()
        if (dao.taskWithCompletions(id)?.reminderTask(today)?.canComplete(now) != true) return
        dao.insertCompletion(CompletionEntity(taskId = id, completedAt = now.toEpochMilli(), localDate = today.toString()))
        dao.updateTask(task.copy(snoozedUntil = null))
    }

    suspend fun evaluate() = mutex.withLock {
        var settings: ReminderSettings? = null
        try {
            reconcileLocked()
            settings = dao.settings().core()
            postNextReminderLocked(settings)
        } finally {
            scheduleNextEvaluationLocked(settings)
        }
    }

    private suspend fun postNextReminderLocked(settings: ReminderSettings) {
        val now = clock.instant()
        val zone = zoneProvider()
        if (!gateway.canNotify()) return
        val reminders = dao.activeReminders()
        val lastByTask = reminders.filter { it.deliveredAt != null }
            .groupBy { it.taskId }
            .mapValues { (_, values) -> Instant.ofEpochMilli(values.maxOf { requireNotNull(it.deliveredAt) }) }
        val tasks = dao.tasks()
        val today = now.atZone(zone).toLocalDate()
        val id = ReminderPolicy.select(
            tasks.map { it.reminderTask(today) }, now, zone, settings,
            dao.state().lastNotifiedAt?.let(Instant::ofEpochMilli), lastByTask,
        ) ?: return
        val task = tasks.first { it.task.id == id }.task
        // Leave room below the platform's active-notification limit.
        if (reminders.size >= maxVisibleReminders) {
            val oldest = reminders.first()
            database.withTransaction { dao.deleteActiveReminder(oldest.notificationId) }
            cancelMatching(listOf(oldest))
        }
        // Reserve the row before posting so a restart can tell which OS notification is ours.
        val token = UUID.randomUUID().toString()
        val notificationId = database.withTransaction {
            dao.insertActiveReminder(ActiveReminderEntity(
                taskId = id, token = token, localDate = today.toString(), preparedAt = now.toEpochMilli(),
            )).toInt()
        }
        try {
            gateway.post(notificationId, id, token, task.title)
        } catch (failure: RuntimeException) {
            if (failure is CancellationException) throw failure
            database.withTransaction { dao.deleteActiveReminder(notificationId) }
            if (gateway.activeTokens()[notificationId] == token) gateway.cancel(notificationId)
            return
        }
        database.withTransaction {
            dao.markDelivered(notificationId, now.toEpochMilli())
            dao.saveState(dao.state().copy(lastNotifiedAt = now.toEpochMilli()))
        }
    }

    /** Without readable settings, retry after the periodic-work interval. */
    private suspend fun scheduleNextEvaluationLocked(settings: ReminderSettings?) {
        val now = clock.instant()
        val next = if (settings == null) now.plus(Duration.ofMinutes(15))
            else nextEvaluationAt(now, zoneProvider(), settings, dao.state().lastNotifiedAt)
        scheduler.scheduleWhileIdle(next)
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
                ReminderAction.SNOOZE, ReminderAction.DISMISS -> snooze(taskId, now)
            }
            // The user acted on it, so a prepared reminder was shown.
            if (reminder.deliveredAt == null) recordNotifiedAt(reminder.preparedAt)
            dao.deleteTaskReminders(taskId)
        }
        cancelMatching(related)
        scheduler.requestEvaluation()
    }

    private suspend fun snooze(taskId: Long, now: Instant) {
        val task = requireNotNull(dao.task(taskId))
        dao.updateTask(task.copy(snoozedUntil = now.plus(snoozeDuration).toEpochMilli()))
    }

    /** Never moves the shared cadence backwards. */
    private suspend fun recordNotifiedAt(at: Long) {
        val state = dao.state()
        dao.saveState(state.copy(lastNotifiedAt = maxOf(state.lastNotifiedAt ?: at, at)))
    }

    private enum class StoredReminderFate { KEEP, CONFIRM_DELIVERY, DISCARD, SNOOZE_DISMISSED }

    /** Decides how a stored reminder matches the OS notification with the same ID. */
    private fun storedReminderFate(reminder: ActiveReminderEntity, valid: Boolean, osToken: String?, now: Instant) = when {
        !valid -> StoredReminderFate.DISCARD
        osToken == reminder.token ->
            if (reminder.deliveredAt == null) StoredReminderFate.CONFIRM_DELIVERY else StoredReminderFate.KEEP
        // Another generation has the same OS ID; neither token is a dismissal of the saved reminder.
        osToken != null -> StoredReminderFate.DISCARD
        reminder.deliveredAt == null -> StoredReminderFate.DISCARD
        // The delete intent normally snoozes immediately. Keep the row while a
        // delayed broadcast may still arrive, then recover if it never does.
        now.isBefore(Instant.ofEpochMilli(reminder.deliveredAt).plus(osVisibilityGrace)) -> StoredReminderFate.KEEP
        else -> StoredReminderFate.SNOOZE_DISMISSED
    }

    private suspend fun reconcileLocked() {
        val now = clock.instant()
        val zone = zoneProvider()
        val settings = dao.settings().core()
        val os = gateway.activeTokens()
        val reminders = dao.activeReminders()
        val known = reminders.associate { it.notificationId to it.token }
        for ((id, token) in os) if (known[id] != token) gateway.cancel(id)
        val snoozedTasks = mutableSetOf<Long>()
        for (reminder in reminders) {
            if (reminder.taskId in snoozedTasks) continue
            val valid = isActiveValid(reminder, dao.taskWithCompletions(reminder.taskId), now, zone, settings) && gateway.canNotify()
            when (storedReminderFate(reminder, valid, os[reminder.notificationId], now)) {
                StoredReminderFate.KEEP -> Unit
                StoredReminderFate.CONFIRM_DELIVERY -> database.withTransaction {
                    dao.markDelivered(reminder.notificationId, reminder.preparedAt)
                    recordNotifiedAt(reminder.preparedAt)
                }
                StoredReminderFate.DISCARD -> {
                    database.withTransaction { dao.deleteActiveReminder(reminder.notificationId) }
                    cancelVisible(listOf(reminder), os)
                }
                StoredReminderFate.SNOOZE_DISMISSED -> {
                    val related = dao.activeReminders().filter { it.taskId == reminder.taskId }
                    database.withTransaction {
                        snooze(reminder.taskId, now)
                        dao.deleteTaskReminders(reminder.taskId)
                    }
                    cancelVisible(related, os)
                    snoozedTasks += reminder.taskId
                }
            }
        }
    }

    private fun isActiveValid(reminder: ActiveReminderEntity, task: TaskWithCompletions?, now: Instant, zone: ZoneId, settings: ReminderSettings): Boolean {
        val today = now.atZone(zone).toLocalDate()
        return reminder.localDate == today.toString() && task != null &&
            ReminderPolicy.isWithinHours(now, zone, settings) && task.reminderTask(today).isDue(now, today)
    }

    private fun cancelMatching(reminders: List<ActiveReminderEntity>) {
        if (reminders.isNotEmpty()) cancelVisible(reminders, gateway.activeTokens())
    }

    /** Cancels only OS notifications still showing these reminders' tokens. */
    private fun cancelVisible(reminders: List<ActiveReminderEntity>, os: Map<Int, String>) {
        for (reminder in reminders) if (os[reminder.notificationId] == reminder.token) gateway.cancel(reminder.notificationId)
    }
}
