package dev.tasknudge

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class TaskNudgeApplication : Application() {
    open lateinit var container: AppContainer
    val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduler.ensureRecurringEvaluation()
        container.scheduler.requestEvaluation()
    }
}
