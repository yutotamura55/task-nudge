package dev.tasknudge

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.tasknudge.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as TaskNudgeApplication).container
    private var notificationsEnabled by mutableStateOf(false)
    private var exactAlarmAccessGranted by mutableStateOf(true)
    private var initialTaskId by mutableStateOf<Long?>(null)
    private var routeRequest by mutableIntStateOf(0)
    private var enabling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialTaskId = taskId(intent)
        routeRequest = savedInstanceState?.getInt("routeRequest") ?: 0
        notificationsEnabled = container.notificationGateway.canNotify()
        refreshExactAlarmAccess()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshNotifications()
            }
            TaskNudgeTheme {
                key(routeRequest) {
                    TaskNudgeApp(container.repository, notificationsEnabled, {
                        if (!enabling) {
                            enabling = true
                            lifecycleScope.launch {
                                try {
                                    val requested = container.repository.snapshots.first().permissionRequested
                                    val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                    when (notificationEnableAction(Build.VERSION.SDK_INT, granted, requested)) {
                                        NotificationEnableAction.REQUEST -> {
                                            // Persist before the dialog, including a denial or process restart.
                                            container.repository.recordPermissionRequest()
                                            if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        NotificationEnableAction.SETTINGS -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "通知設定を開けませんでした。もう一度お試しください。", Toast.LENGTH_LONG).show()
                                } finally { enabling = false }
                            }
                        }
                    }, initialTaskId, clock = container.clock, zoneProvider = container.zoneProvider,
                        exactAlarmAccessGranted = exactAlarmAccessGranted,
                        onEnableExactAlarm = {
                            try {
                                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName")))
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "アラームとリマインダーの設定を開けませんでした。", Toast.LENGTH_LONG).show()
                            }
                        })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotifications()
        refreshExactAlarmAccess()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("routeRequest", routeRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialTaskId = taskId(intent)
        routeRequest++
    }

    private fun refreshNotifications() {
        notificationsEnabled = container.notificationGateway.canNotify()
        lifecycleScope.launch {
            try { container.engine.evaluate() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                Toast.makeText(this@MainActivity, "通知を確認できませんでした。アプリを開き直してください。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshExactAlarmAccess() {
        exactAlarmAccessGranted = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun taskId(intent: Intent): Long? = intent.getLongExtra("taskId", -1).takeIf { it > 0 }
}
