package dev.tasknudge

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.reminders.AndroidNotificationGateway
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val diagnostics = FailureDiagnostics()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeviceTestApplication

    @Before fun setUp() {
        app.resetFixture()
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After fun tearDown() {
        app.getSystemService(NotificationManager::class.java).cancelAll()
        app.closeFixture()
    }

    @Test fun titleOnlyAddDetailAndManualCompletionWorkInActivity() {
        launchMain {
            clickAddFab()
            compose.onNodeWithText("タイトル").performTextInput("首のストレッチ")
            saveVisibleEditor()
            scrollToVisibleText("実行履歴（0回）")
            compose.onNode(hasText("完了") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
            waitForCompletionShown(1)
            scrollToVisibleText("実行履歴（1回）")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("今日完了済み"))
            compose.onNodeWithText("今日完了済み").assertIsNotEnabled()
            runBlocking { assertEquals(1, app.container.repository.snapshots.first().tasks.single().completions.size) }
        }
    }

    @Test fun rotationKeepsDraftAndLargeFontKeepsActionsReachable() {
        launchMain { scenario ->
            try {
                clickAddFab()
                compose.onNodeWithText("タイトル").performTextInput("回転しても残る下書き")
                compose.closeKeyboard()
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
            launchMain { scenario ->
                scenario.recreate()
                compose.waitUntil(10_000) {
                    var enlarged = false
                    scenario.onActivity { enlarged = it.resources.configuration.fontScale >= 1.7f }
                    enlarged
                }
                clickAddFab()
                compose.onNodeWithText("タイトル").performTextInput("大きな文字でも追加")
                saveVisibleEditor()
                scrollToVisibleText("実行履歴（0回）")
            }
        } finally { shell("settings put system font_scale 1.0") }
    }

    @Test fun deniedNotificationPermissionStillAllowsManualTracking() {
        app.resetFixture(notificationsEnabled = false)
        launchMain {
            scrollToVisibleText("通知が無効です")
            clickAddFab()
            compose.onNodeWithText("タイトル").performTextInput("通知なしでも記録")
            saveVisibleEditor()
            scrollToVisibleText("実行履歴（0回）")
            compose.onNode(hasText("完了") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
            waitForCompletionShown(1)
            scrollToVisibleText("実行履歴（1回）")
        }
    }

    @Test fun actualScreenshotsShowTasksHistoryAndSettings() = runBlocking {
        val taskId = app.container.repository.saveTask(null, "首のストレッチ", 1)
        app.container.repository.saveTask(null, "水を飲む", 1)
        app.container.repository.completeTask(taskId)
        assertEquals(2, app.container.repository.snapshots.first().tasks.size)
        launchMain {
            waitForAddFabVisible()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("首のストレッチ"))
            compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
            capture("tasks")
            compose.onNodeWithText("首のストレッチ").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("実行履歴（1回）"))
            compose.onNodeWithText("実行履歴（1回）").assertIsDisplayed()
            compose.onNodeWithText("タスクの詳細").assertIsDisplayed()
            capture("history")
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithContentDescription("通知設定").performClick()
            compose.onNodeWithText("通知する時間").assertIsDisplayed()
            capture("settings")
        }
    }

    @Test fun fifteenMinuteIntervalPersistsAndRemainsSelectedAfterReopening() {
        launchMain { scenario ->
            waitForAddFabVisible()
            compose.onNodeWithContentDescription("通知設定").performClick()
            compose.onNodeWithText("15分").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("15分").assertIsSelected()
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                runBlocking { app.container.repository.snapshots.first().settings.intervalMinutes == 15 }
            }
            waitForAddFabVisible()
            compose.onNodeWithContentDescription("通知設定").performClick()
            compose.onNodeWithText("15分").performScrollTo().assertIsSelected()
            scenario.recreate()
            compose.onNodeWithText("15分").performScrollTo().assertIsDisplayed().assertIsSelected()
            capture("settings-15-minutes")
        }
    }

    @Test fun fiveAndTenMinuteIntervalsPersistAndRemainSelectedAfterReopening() {
        launchMain { scenario ->
            waitForAddFabVisible()
            for (minutes in listOf(5, 10)) {
                compose.onNodeWithContentDescription("通知設定").performClick()
                compose.onNodeWithText("${minutes}分").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithText("${minutes}分").assertIsSelected()
                compose.onNodeWithText("保存").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    runBlocking { app.container.repository.snapshots.first().settings.intervalMinutes == minutes }
                }
                waitForAddFabVisible()
                compose.onNodeWithContentDescription("通知設定").performClick()
                compose.onNodeWithText("${minutes}分").performScrollTo().assertIsSelected()
                scenario.recreate()
                compose.onNodeWithText("${minutes}分").performScrollTo().assertIsDisplayed().assertIsSelected()
                compose.onNodeWithContentDescription("戻る").performClick()
                waitForAddFabVisible()
            }
        }
    }

    @Test fun notificationTapRoutesToDetailOnColdAndExistingActivity() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.container.repository.saveSettings(ReminderSettings(0, 24, 30))
        val taskId = app.container.repository.saveTask(null, "通知から開く", 1)
        app.container.engine.evaluate()
        val notification = app.awaitActiveReminder()
        val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            tapReminderNotification("通知から開く")
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("Notification tap should launch MainActivity", activity)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("タスクの詳細").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("タスクの詳細").assertIsDisplayed()
            scrollToVisibleText("実行履歴（0回）")
            compose.onNodeWithContentDescription("戻る").performClick()
            waitForAddFabVisible()
            tapReminderNotification("通知から開く")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("タスクの詳細").fetchSemanticsNodes().isNotEmpty() }
            scrollToVisibleText("実行履歴（0回）")
            assertEquals(taskId, notification.notification.extras.getLong("taskId"))
        } catch (e: Throwable) {
            // The cleanup below closes the screen and the shade before the rule could log them.
            diagnostics.captureNow()
            throw e
        } finally {
            activity?.runOnUiThread { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            shell("cmd statusbar collapse")
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = originalAccessibilityFlags
            }
        }
    }

    private fun tapReminderNotification(title: String) {
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Notification shade should open", device.openNotification())
        device.waitForIdle()
        val notification = device.wait(
            Until.findObject(By.pkg("com.android.systemui").text(title)), 10_000,
        )
        assertNotNull("Notification text should be visible", notification)
        requireNotNull(notification).click()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // captureToImage waits for this Compose surface to finish drawing; the
        // device-wide screenshot API can return the previous screen's frame.
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = requireNotNull(app.getExternalFilesDir("screenshots"))
        directory.mkdirs()
        val file = File(directory, "$name.png")
        file.outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        val sourceSize = file.length()
        assertTrue("Compressed screenshot $file should not be empty", sourceSize > 0)

        // UiAutomation#executeShellCommand runs Runtime.exec directly (no shell), so each
        // step must be a separate command; `&&`/`;` chaining is passed through literally.
        val sharedDir = "/sdcard/Pictures/TaskNudgeTest"
        val sharedFile = "$sharedDir/$name.png"
        shell("mkdir -p $sharedDir")
        shell("cp ${file.absolutePath} $sharedFile")
        val copiedSizeOutput = shell("stat -c %s $sharedFile").trim()
        assertEquals(
            "Expected copied screenshot $sharedFile to be $sourceSize bytes (same as $file) " +
                "but shell `stat` reported '$copiedSizeOutput'",
            sourceSize,
            copiedSizeOutput.toLongOrNull(),
        )
    }

    private fun saveVisibleEditor() {
        compose.closeKeyboard()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        // Save commits asynchronously; the app navigates to the detail screen only once
        // the committed TaskSaved result is observed in a snapshot. Wait for that
        // screen-owned transition (its title) before any history scroll, otherwise a
        // scroll can still target the stale editor's own scrollable container.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("タスクの詳細").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("タスクの詳細").assertIsDisplayed()
    }

    private fun clickAddFab() { waitForAddFabVisible(); compose.onNodeWithContentDescription("タスクを追加").performClick() }

    private fun waitForAddFabVisible() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("タスクを追加").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
    }

    // use {} closes the Activity before the diagnostics rule sees a failure, so capture the screen first.
    private inline fun launchMain(block: (ActivityScenario<MainActivity>) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try { block(scenario) } catch (e: Throwable) { diagnostics.captureNow(); throw e }
        }

    /**
     * Waits after tapping 完了 until the database holds [count] completions and the history
     * shows them. Compose waits for recomposition once new state arrives, but not for the
     * database write or for Room to deliver the re-read data, so a fixed check right after
     * the tap can come too early. (#53: suspected, not reproduced.)
     */
    private fun waitForCompletionShown(count: Int) {
        var stored: Int? = null
        try {
            compose.waitUntil(10_000) { storedCompletions()?.also { stored = it } == count }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Tapping 完了 should store $count completion(s); last count read: ${stored ?: "none (reads timed out)"}", e)
        }
        // The history header is a separate list item; bring it into the list before waiting.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("実行履歴（", substring = true))
        val history = "実行履歴（${count}回）"
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(history).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("The database has $count completion(s) but the screen did not show $history", e)
        }
    }

    // Counts across all tasks, giving up after 2 s so a stuck read cannot hang the test.
    private fun storedCompletions(): Int? = runBlocking {
        withTimeoutOrNull(2_000) { app.container.repository.snapshots.first().tasks.sumOf { it.completions.size } }
    }

    private fun scrollToVisibleText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
}
