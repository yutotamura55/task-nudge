package dev.tasknudge

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.reminders.AndroidNotificationGateway
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeviceTestApplication

    @Before fun setUp() {
        app.resetFixture()
        app.getSystemService(NotificationManager::class.java).cancel(AndroidNotificationGateway.NOTIFICATION_ID)
    }

    @After fun tearDown() {
        app.getSystemService(NotificationManager::class.java).cancel(AndroidNotificationGateway.NOTIFICATION_ID)
        app.closeFixture()
    }

    @Test fun titleOnlyAddDetailAndManualCompletionWorkInActivity() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("タスクを追加").performClick()
            compose.onNodeWithText("タイトル").performTextInput("首のストレッチ")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("実行履歴（0回）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("完了").performClick()
            compose.onNodeWithText("実行履歴（1回）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("今日完了済み").assertIsNotEnabled()
            runBlocking { assertEquals(1, app.container.repository.snapshots.first().tasks.single().completions.size) }
        }
    }

    @Test fun rotationKeepsDraftAndLargeFontKeepsActionsReachable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                compose.onNodeWithContentDescription("タスクを追加").performClick()
                compose.onNodeWithText("タイトル").performTextInput("回転しても残る下書き")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(10_000) { var landscape = false; scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }; landscape }
                compose.onNodeWithText("回転しても残る下書き").assertExists()
                compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(10_000) { var portrait = false; scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }; portrait }
            }
        }
        // Apply a system font scale and recreate a real Activity, then restore it.
        shell("settings put system font_scale 1.8")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                compose.waitUntil(10_000) {
                    var enlarged = false
                    scenario.onActivity { enlarged = it.resources.configuration.fontScale >= 1.7f }
                    enlarged
                }
                compose.onNodeWithContentDescription("タスクを追加").performClick()
                compose.onNodeWithText("タイトル").performTextInput("大きな文字でも追加")
                compose.onNodeWithText("保存").assertIsDisplayed()
                compose.onNodeWithText("保存").performClick()
                compose.onNodeWithText("実行履歴（0回）").assertIsDisplayed()
            }
        } finally { shell("settings put system font_scale 1.0") }
    }

    @Test fun deniedNotificationPermissionStillAllowsManualTracking() {
        app.resetFixture(notificationsEnabled = false)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("通知が無効です").assertIsDisplayed()
            compose.onNodeWithContentDescription("タスクを追加").performClick()
            compose.onNodeWithText("タイトル").performTextInput("通知なしでも記録")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText("実行履歴（0回）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("完了").performClick()
            compose.onNodeWithText("実行履歴（1回）").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun actualScreenshotsShowTasksHistoryAndSettings() = runBlocking {
        val taskId = app.container.repository.saveTask(null, "首のストレッチ", 1)
        app.container.repository.saveTask(null, "水を飲む", 1)
        app.container.repository.completeTask(taskId)
        assertEquals(2, app.container.repository.snapshots.first().tasks.size)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("首のストレッチ"))
            compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
            capture("tasks")
            compose.onNodeWithText("首のストレッチ").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("実行履歴（1回）"))
            compose.onNodeWithText("実行履歴（1回）").assertIsDisplayed()
            capture("history")
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithContentDescription("通知設定").performClick()
            compose.onNodeWithText("通知する時間").assertIsDisplayed()
            capture("settings")
        }
    }

    @Test fun notificationTapRoutesToDetailOnColdAndExistingActivity() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.container.repository.saveSettings(ReminderSettings(0, 24, 30))
        val taskId = app.container.repository.saveTask(null, "通知から開く", 1)
        app.container.engine.evaluate()
        val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == AndroidNotificationGateway.NOTIFICATION_ID }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            notification.notification.contentIntent.send()
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("Notification tap should launch MainActivity", activity)
            compose.onNodeWithText("実行履歴（0回）").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
            notification.notification.contentIntent.send()
            compose.onNodeWithText("実行履歴（0回）").performScrollTo().assertIsDisplayed()
            assertEquals(taskId, notification.notification.extras.getLong("taskId"))
        } finally {
            activity?.runOnUiThread { activity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(app.getExternalFilesDir("screenshots"))
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
