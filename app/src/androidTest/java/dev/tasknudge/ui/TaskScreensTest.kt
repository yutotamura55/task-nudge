package dev.tasknudge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import android.widget.EditText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.graphics.Rect
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.uiautomator.UiDevice
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.core.TaskInput
import dev.tasknudge.data.*
import dev.tasknudge.DeviceTestApplication
import dev.tasknudge.FailureDiagnostics
import dev.tasknudge.awaitKeyboardAreaSettled
import dev.tasknudge.closeKeyboard
import dev.tasknudge.keyboardTopInWindow
import dev.tasknudge.showKeyboard
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TaskScreensTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val diagnostics = FailureDiagnostics()
    private fun launch(repo: ScreenRepository, enabled: Boolean = true, initial: Long? = null, enable: () -> Unit = {}) {
        compose.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(repo, enabled, enable, initial) } }
    }
    private fun enterBulkText(value: String) {
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput").setText(value)
        }
        compose.onNodeWithText("追加予定：${value.lines().count { it.isNotBlank() }}件").assertExists()
    }
    private fun dismissKeyboard() = compose.closeKeyboard()
    private fun launchEdgeToEdge(repo: ScreenRepository) {
        compose.runOnUiThread {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            (activity as ComponentActivity).enableEdgeToEdge()
        }
        launch(repo)
    }
    /** Shows the keyboard for the focused field; requires one on the emulator, skips elsewhere. */
    private fun requireKeyboard() {
        val shown = compose.showKeyboard()
        assertTrue("The emulator must show a keyboard area for a focused field", shown || !isEmulator())
        assumeTrue("This device showed no soft keyboard", shown)
    }
    // Unclipped bottom edge: boundsInWindow is clipped by the scroll viewport, so a partly
    // hidden node would still look inside it.
    private fun bottomInWindow(node: SemanticsNodeInteraction): Float {
        val semantics = node.fetchSemanticsNode()
        return semantics.positionInWindow.y + semantics.size.height
    }
    /**
     * The check proves something only when the keyboard would cover the node if the screen
     * did not avoid it. Required on the emulator (CI's 320x640 screen); skipped on taller screens.
     */
    private fun requireCoveredWithoutAvoidance(bottomWithoutKeyboard: Float, name: String) {
        val top = requireNotNull(keyboardTopInWindow()) { "The keyboard area closed before checking $name" }
        val covered = bottomWithoutKeyboard > top
        assertTrue("On the emulator the keyboard must cover $name without avoidance: bottom=$bottomWithoutKeyboard keyboardTop=$top", covered || !isEmulator())
        assumeTrue("The keyboard does not reach $name on this screen", covered)
    }
    private fun assertAboveKeyboard(node: SemanticsNodeInteraction, name: String) {
        val semantics = node.fetchSemanticsNode()
        val bottom = bottomInWindow(node)
        val top = requireNotNull(keyboardTopInWindow()) { "The keyboard area closed before checking $name" }
        assertTrue("$name must show above the keyboard: top=${semantics.positionInWindow.y} bottom=$bottom keyboardTop=$top", semantics.size.height > 0 && bottom <= top + 1f)
    }
    private fun tapSave(label: String = "保存") {
        dismissKeyboard()
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
    }
    @Test fun settingsOfferSleepNotificationAccessWhenItIsUnavailable() {
        var requests = 0
        compose.setContent {
            TaskNudgeTheme(dynamicColor = false) {
                SettingsScreen(
                    SettingsContent(ReminderSettings(9, 21, 30), permissionRequested = false, notificationsEnabled = true, exactAlarmAccessGranted = false),
                    TaskUiState(),
                    SettingsActions(onEnableNotifications = {}, onSave = { _, _, _ -> }, onEnableExactAlarm = { requests++ }),
                )
            }
        }
        compose.onNodeWithText("アラームとリマインダーを許可").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun fourTasksRemainVisibleWithoutScrollingInACompactList() {
        val tasks = listOf(
            sampleTask(),
            sampleTask().copy(id = 2, title = "肩"),
            sampleTask().copy(id = 3, title = "足"),
            sampleTask().copy(id = 4, title = "腰"),
        )
        var openedTask: Long? = null
        compose.setContent {
            TaskNudgeTheme(dynamicColor = false) {
                Box(Modifier.size(360.dp, 560.dp)) {
                    TaskListScreen(listContent(tasks), listActions(onOpenTask = { openedTask = it }))
                }
            }
        }
        compose.onNodeWithText("腰").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(4L, openedTask) }
    }
    @Test fun searchingTaskTitlesFiltersListAndKeepsQueryAfterOpeningDetail() {
        val repo = ScreenRepository(listOf(
            sampleTask().copy(id = 1, title = "首のストレッチ"),
            sampleTask().copy(id = 2, title = "肩の運動"),
            sampleTask().copy(id = 3, title = "腕のストレッチ"),
        ))
        launch(repo)
        compose.onNodeWithText("検索").performClick()
        compose.onNodeWithText("タスクを検索").performTextInput("ストレッチ")
        dismissKeyboard()
        compose.onNodeWithText("検索結果 2件").assertIsDisplayed()
        compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
        compose.onNodeWithText("腕のストレッチ").assertIsDisplayed()
        compose.onNodeWithText("肩の運動").assertDoesNotExist()
        compose.onNodeWithText("首のストレッチ").performClick()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("肩の運動").assertDoesNotExist()
        compose.onNodeWithText("タスクを検索").assertTextContains("ストレッチ")
    }
    @Test fun searchShowsNoResultsAndClearingRestoresTasks() {
        val repo = ScreenRepository(listOf(sampleTask().copy(title = "首のストレッチ")))
        launch(repo)
        compose.onNodeWithText("検索").performClick()
        compose.onNodeWithText("タスクを検索").performTextInput("肩")
        dismissKeyboard()
        compose.onNodeWithText("検索結果 0件").assertIsDisplayed()
        compose.onNodeWithText("一致するタスクがありません").assertIsDisplayed()
        compose.onNodeWithText("首のストレッチ").assertDoesNotExist()
        compose.onNodeWithText("タスクを検索").performTextClearance()
        dismissKeyboard()
        compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
    }
    @Test fun largeTextKeepsTaskNameReadableWithCompletionAction() {
        val title = "首と肩のストレッチをゆっくり丁寧に続ける"
        compose.setContent {
            TaskNudgeTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.size(360.dp, 560.dp)) {
                        TaskListScreen(listContent(listOf(sampleTask().copy(title = title))), listActions())
                    }
                }
            }
        }
        val nameWidth = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        assertTrue("Large text must leave at least 200dp for the task name; width was $nameWidth", nameWidth >= 200f)
        compose.onNodeWithText("完了").assertIsDisplayed()
    }
    @Test fun injectedClockAndZoneShowTodaysCompletionOnListAndDetail() {
        val clock = Clock.fixed(Instant.parse("2030-04-03T15:30:00Z"), ZoneOffset.UTC)
        val completion = CompletionRecord(2, Instant.parse("2030-04-03T15:00:00Z"), LocalDate.of(2030, 4, 4))
        val repo = ScreenRepository(listOf(sampleTask().copy(completions = listOf(completion))))
        compose.setContent {
            TaskNudgeTheme(dynamicColor = false) {
                TaskNudgeApp(repo, true, {}, clock = clock, zoneProvider = { ZoneId.of("Asia/Tokyo") })
            }
        }
        compose.onNodeWithText("今日の完了 1 / 1").assertIsDisplayed()
        compose.onNodeWithText("首").performClick()
        compose.onNodeWithText("今日完了済み").assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun titleOnlyAddSavesDailyTask() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("首のストレッチ")
        tapSave()
        try {
            compose.waitUntil(10_000) { repo.data.value.tasks.size == 1 }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("saveCalls=${repo.saveCalls}; UI=${compose.onRoot().printToString()}", e)
        }
        assertEquals("首のストレッチ", repo.data.value.tasks.single().title)
        assertEquals(1, repo.data.value.tasks.single().recurrenceDays)
        compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
    }
    @Test fun pastedLinesCreateTasksWithOneSharedRecurrence() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        enterBulkText(" 首 \n\n 腕 ")
        compose.onNodeWithText("繰り返し（日）").performTextReplacement("3")
        compose.onNodeWithText("追加予定：2件").assertExists()
        compose.onNodeWithText("3").assertExists()
        tapSave("まとめて保存")
        try {
            compose.waitUntil(10_000) { repo.data.value.tasks.size == 2 }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("saveCalls=${repo.saveCalls}; UI=${compose.onRoot().printToString()}", e)
        }
        assertEquals(listOf("首", "腕"), repo.data.value.tasks.map { it.title })
        assertEquals(listOf(3, 3), repo.data.value.tasks.map { it.recurrenceDays })
        compose.onNodeWithText("首").assertIsDisplayed()
    }
    @Test fun longTaskStaysOnOneVisualLineWhileExplicitNewlineCreatesSecondTask() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        val longTitle = "首と肩のストレッチをゆっくり丁寧に続けるための長いタスク名"
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertNotNull("The bulk input must support unwrapped multiline editing", editor)
            editor.setText("$longTitle\n腕")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertEquals(2, editor.layout.lineCount)
            assertTrue(editor.isHorizontallyScrollable)
        }
        compose.awaitKeyboardAreaSettled()
        val bounds = Rect()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput").getGlobalVisibleRect(bounds)
        }
        val swipeY = bounds.top + minOf(70, bounds.height() / 2)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(bounds.right - 80, swipeY, bounds.left + 80, swipeY, 20)
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertTrue("A horizontal swipe must reveal the end of the long line: width=${editor.width}, lineRight=${editor.layout.getLineRight(0)}, scrollX=${editor.scrollX}", editor.scrollX > 0)
        }
        compose.onNodeWithText("追加予定：2件").assertIsDisplayed()
        tapSave("まとめて保存")
        compose.waitUntil(10_000) { repo.data.value.tasks.size == 2 }
        assertEquals(listOf(longTitle, "腕"), repo.data.value.tasks.map { it.title })
    }
    @Test fun pastedNewlinesRemainSeparateTasks() {
        assumeTrue("Clipboard paste is verified on the isolated CI emulator", isEmulator())
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            compose.runOnIdle {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
                clipboard.setPrimaryClip(ClipData.newPlainText("tasks", "首の長いストレッチ\n腕のストレッチ"))
                editor.requestFocus()
                assertTrue(editor.onTextContextMenuItem(android.R.id.paste))
            }
            dismissKeyboard()
            compose.onNodeWithText("追加予定：2件").assertIsDisplayed()
            tapSave("まとめて保存")
            compose.waitUntil(10_000) { repo.data.value.tasks.size == 2 }
            assertEquals(listOf("首の長いストレッチ", "腕のストレッチ"), repo.data.value.tasks.map { it.title })
        } finally {
            compose.runOnIdle { if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() }
        }
    }
    @Test fun multilineBulkInputCanScrollVerticallyAndSelectText() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        enterBulkText((1..20).joinToString("\n") { "タスク$it" })
        compose.awaitKeyboardAreaSettled()
        val bounds = Rect()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertEquals(20, editor.layout.lineCount)
            editor.setSelection(0, 3)
            assertEquals(0, editor.selectionStart)
            assertEquals(3, editor.selectionEnd)
            editor.getGlobalVisibleRect(bounds)
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(bounds.centerX(), bounds.bottom - 90, bounds.centerX(), bounds.top + 90, 20)
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertTrue("Vertical swipe must scroll the multiline editor: height=${editor.height}, layoutHeight=${editor.layout.height}, scrollY=${editor.scrollY}, bounds=$bounds", editor.scrollY > 0)
        }
    }
    // The screen pads itself by the keyboard area (imePadding). With the keyboard shown,
    // the action below the field must, after scrolling, sit above that area. The test
    // window is made edge-to-edge like MainActivity so the app, not the window, avoids it.
    @Test fun editorKeepsTitleAndSaveAboveTheKeyboard() {
        val repo = ScreenRepository(); launchEdgeToEdge(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        val saveBottomWithoutKeyboard = bottomInWindow(compose.onNodeWithText("保存"))
        compose.onNodeWithText("タイトル").performClick()
        requireKeyboard()
        requireCoveredWithoutAvoidance(saveBottomWithoutKeyboard, "保存")
        assertAboveKeyboard(compose.onNodeWithText("タイトル"), "タイトル")
        compose.onNodeWithText("保存").performScrollTo()
        compose.awaitKeyboardAreaSettled()
        assertAboveKeyboard(compose.onNodeWithText("保存"), "保存")
        dismissKeyboard()
    }
    @Test fun bulkEditorKeepsInputAndCountAboveTheKeyboard() {
        val repo = ScreenRepository(); launchEdgeToEdge(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        val countBottomWithoutKeyboard = bottomInWindow(compose.onNodeWithText("追加予定：0件"))
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput").requestFocus()
        }
        requireKeyboard()
        requireCoveredWithoutAvoidance(countBottomWithoutKeyboard, "追加予定")
        // A View inside Compose reports no Compose clipping, so check that its first line,
        // where typing starts, is above the keyboard area.
        var firstLineBottom = 0
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            val location = IntArray(2).also { editor.getLocationInWindow(it) }
            firstLineBottom = location[1] + editor.totalPaddingTop + editor.lineHeight
        }
        val top = requireNotNull(keyboardTopInWindow()) { "The keyboard area closed before the check" }
        assertTrue("The first line of the bulk input must show above the keyboard: bottom=$firstLineBottom keyboardTop=$top", firstLineBottom <= top)
        compose.onNodeWithText("追加予定：0件").performScrollTo()
        compose.awaitKeyboardAreaSettled()
        assertAboveKeyboard(compose.onNodeWithText("追加予定：0件"), "追加予定")
        dismissKeyboard()
    }
    @Test fun japaneseCompositionRemainsActiveAcrossScreenRecomposition() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            editor.requestFocus()
            val info = EditorInfo()
            connection = editor.onCreateInputConnection(info)
            assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            assertTrue(connection.setComposingText("しゅ", 1))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertEquals("しゅ", editor.text.toString())
            assertTrue("Recomposition must preserve Japanese composing text", BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            assertTrue(connection.commitText("首", 1))
        }
        dismissKeyboard()
        compose.onNodeWithText("追加予定：1件").assertIsDisplayed()
        tapSave("まとめて保存")
        compose.waitUntil(10_000) { repo.data.value.tasks.size == 1 }
        assertEquals("首", repo.data.value.tasks.single().title)
    }
    @Test fun composingAtRightEdgeRemainsVisibleBeforeCommit() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            editor.setText("首と肩のストレッチをゆっくり丁寧に続けるための長いタスク名")
            editor.requestFocus()
            editor.setSelection(editor.text.length)
            connection = editor.onCreateInputConnection(EditorInfo())
            assertTrue(connection.setComposingText("しゅう", 0))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(editor.text)
            val caretX = editor.layout.getPrimaryHorizontal(composingEnd) + editor.totalPaddingLeft - editor.scrollX
            val visibleRight = editor.width - editor.totalPaddingRight
            assertTrue("Composing text is outside the input: right=$caretX visibleRight=$visibleRight scrollX=${editor.scrollX}", caretX <= visibleRight + 2)
        }
    }
    @Test fun longCompositionKeepsImeCaretVisible() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            editor.setText("首と肩のストレッチをゆっくり丁寧に続けるための長いタスク名")
            editor.requestFocus()
            editor.setSelection(editor.text.length)
            assertTrue(editor.onCreateInputConnection(EditorInfo()).setComposingText("しゅう".repeat(20), 0))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            val caretX = editor.layout.getPrimaryHorizontal(editor.selectionEnd) + editor.totalPaddingLeft - editor.scrollX
            val composingEnd = BaseInputConnection.getComposingSpanEnd(editor.text)
            val composingRight = editor.layout.getPrimaryHorizontal(composingEnd) + editor.totalPaddingLeft - editor.scrollX
            assertTrue("This case must contain text wider than the viewport", composingRight > editor.width - editor.totalPaddingRight)
            assertTrue("IME caret moved off the left edge: caretX=$caretX", caretX >= editor.totalPaddingLeft)
            assertTrue("IME caret moved off the right edge: caretX=$caretX", caretX <= editor.width - editor.totalPaddingRight)
        }
    }
    @Test fun focusedBulkInputCanPanBackWithoutMovingCaret() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        val bounds = Rect()
        var initialScroll = 0
        var caret = 0
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            editor.setText("首と肩のストレッチをゆっくり丁寧に続けるための長いタスク名")
            editor.requestFocus()
            editor.setSelection(editor.text.length)
        }
        compose.awaitKeyboardAreaSettled()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            initialScroll = editor.scrollX
            caret = editor.selectionEnd
            editor.getGlobalVisibleRect(bounds)
        }
        assertTrue("The caret must start beyond the first viewport: scrollX=$initialScroll", initialScroll > 0)
        val swipeY = bounds.top + minOf(70, bounds.height() / 2)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(bounds.left + 80, swipeY, bounds.right - 80, swipeY, 20)
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            assertNotNull("The editor should remain open after swiping", editor)
            assertTrue("Swipe should reveal earlier text: before=$initialScroll after=${editor.scrollX}", editor.scrollX < initialScroll)
            assertEquals("Panning should not change the edit position", caret, editor.selectionEnd)
            assertTrue(editor.onCreateInputConnection(EditorInfo()).commitText("確認", 1))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val editor = activity.window.decorView.findViewWithTag<EditText>("bulkTaskInput")
            val caretX = editor.layout.getPrimaryHorizontal(editor.selectionEnd) + editor.totalPaddingLeft - editor.scrollX
            assertTrue("Typing after panning must reveal the insertion point: caretX=$caretX", caretX in editor.totalPaddingLeft.toFloat()..(editor.width - editor.totalPaddingRight).toFloat())
        }
    }
    @Test fun pastedLinesReachTheRealDatabaseAndReturnToTheList() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeviceTestApplication
        app.resetFixture(notificationsEnabled = false)
        try {
            compose.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(app.container.repository, false, {}) } }
            compose.onNodeWithContentDescription("まとめて追加").performClick()
            enterBulkText("首\n腕")
            tapSave("まとめて保存")
            compose.waitUntil(10_000) { runBlocking { app.database!!.dao().tasks().size == 2 } }
            compose.onNodeWithText("今日の完了 0 / 2").assertIsDisplayed()
            val tasks = runBlocking { app.database!!.dao().tasks() }
            assertEquals(listOf("首", "腕"), tasks.map { it.task.title })
            assertEquals(listOf(1, 1), tasks.map { it.task.recurrenceDays })
        } finally { app.closeFixture() }
    }
    @Test fun bulkInputErrorLeavesAllTasksUnsaved() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("まとめて追加").performClick()
        enterBulkText("首\n${"長".repeat(101)}")
        tapSave("まとめて保存")
        compose.onNodeWithText("各タスク名は前後の空白を除き100文字以内で入力してください").assertExists()
        compose.runOnIdle { assertTrue(repo.data.value.tasks.isEmpty()) }
    }
    @Test fun committedAddWaitsForDisplayDataWithoutOfferingDuplicateSave() {
        val repo = ScreenRepository(publishSaves = false); launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("首のストレッチ")
        tapSave()
        compose.waitUntil(10_000) { repo.pendingTask != null }
        compose.onNodeWithText("保存済み・データ表示待ち").assertIsDisplayed()
        compose.onNodeWithText("タイトル").assertIsDisplayed()
        compose.onNodeWithText("保存済み").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, repo.saveCalls) }
        compose.runOnIdle { repo.data.value = repo.data.value.copy(tasks = listOf(repo.pendingTask!!)) }
        compose.onNodeWithText("実行履歴（0回）").assertIsDisplayed()
    }
    @Test fun committedAddCanLeaveWhileDisplayDataIsDelayed() {
        val repo = ScreenRepository(publishSaves = false); launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("首")
        tapSave()
        compose.waitUntil(10_000) { repo.pendingTask != null }
        compose.onNodeWithText("保存済み・データ表示待ち").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
        compose.runOnIdle { repo.data.value = repo.data.value.copy(tasks = listOf(repo.pendingTask!!)) }
        compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, repo.saveCalls) }
    }
    @Test fun suspendedSaveCannotDiscardAnotherEditorDraft() {
        val repo = ScreenRepository().apply { gate = CompletableDeferred() }; launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("首")
        tapSave()
        compose.waitUntil { repo.saveCalls == 1 }
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("腰の下書き")
        dismissKeyboard()
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitUntil { repo.data.value.tasks.size == 1 }
        compose.onNodeWithText("タイトル").assertIsDisplayed()
        compose.onNodeWithText("腰の下書き").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsEnabled()
        assertEquals("首", repo.data.value.tasks.single().title)
    }
    @Test fun oldEditorSaveCallbackCannotWriteTheNewlySelectedTask() {
        val repo = ScreenRepository(listOf(sampleTask(), sampleTask().copy(id = 2, title = "腰")))
        launch(repo, initial = 1)
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("首の下書き")
        dismissKeyboard()
        val oldSave = compose.onNodeWithText("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("腰").performClick()
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("腰の下書き")
        dismissKeyboard()
        compose.runOnIdle { oldSave() }
        compose.onNodeWithText("腰の下書き").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(0, repo.saveCalls)
            assertEquals("腰", repo.data.value.tasks.single { it.id == 2L }.title)
        }
    }
    @Test fun oldSettingsSaveCallbackCannotClaimNewEditorSession() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("通知設定").performClick()
        compose.onNodeWithText("開始（時）").performTextReplacement("0")
        dismissKeyboard()
        val oldSave = compose.onNodeWithText("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("新しい下書き")
        dismissKeyboard()
        compose.runOnIdle { oldSave() }
        compose.onNodeWithText("新しい下書き").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.runOnIdle { assertEquals(ReminderSettings(), repo.data.value.settings) }
    }
    @Test fun oldDetailCompleteCallbackCannotRunInAnotherScreenSession() {
        val repo = ScreenRepository(listOf(sampleTask(), sampleTask().copy(id = 2, title = "腰")))
        launch(repo, initial = 1)
        val oldComplete = compose.onNode(hasText("完了") and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("腰").performClick()
        compose.runOnIdle { oldComplete() }
        compose.onNode(hasText("完了") and hasClickAction()).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, repo.data.value.tasks.single { it.id == 1L }.completions.size) }
    }
    @Test fun oldListCompleteCallbackCannotRunAfterOpeningSettings() {
        val repo = ScreenRepository(listOf(sampleTask())); launch(repo)
        val oldComplete = compose.onNodeWithText("完了").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("通知設定").performClick()
        compose.runOnIdle { oldComplete() }
        compose.onNodeWithText("開始（時）").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, repo.data.value.tasks.single().completions.size) }
    }
    @Test fun inputErrorsRemainInlineAndDoNotSave() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        tapSave()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("タイトルは1〜100文字で入力してください", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("タイトルは1〜100文字で入力してください").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("タイトル").performTextInput("a".repeat(101))
        tapSave()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("タイトルは1〜100文字で入力してください", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("タイトルは1〜100文字で入力してください").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("タイトル").performTextReplacement("腕")
        dismissKeyboard()
        compose.onNodeWithText("繰り返しを設定").performClick()
        compose.onNodeWithText("繰り返し（日）").performTextReplacement("0")
        tapSave()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("繰り返しは1〜365日の整数で入力してください", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("繰り返しは1〜365日の整数で入力してください").performScrollTo().assertIsDisplayed()
        assertTrue(repo.data.value.tasks.isEmpty())
    }
    @Test fun deniedPermissionIsExplainedAndOnlyExplicitButtonRequests() {
        var requests = 0
        val repo = ScreenRepository(); launch(repo, false, enable = { requests++ })
        compose.onNodeWithText("通知が無効です").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("通知を有効にする").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun stoppedTaskCanReopenWithHistoryAndDoesNotCountAsActive() {
        val repo = ScreenRepository(listOf(sampleTask())); launch(repo)
        compose.onNodeWithText("首").performClick()
        compose.onNodeWithText("2026-09-30").assertIsDisplayed()
        compose.onNodeWithText("停止する").performClick()
        compose.onNodeWithText("再開する").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("今日の完了 0 / 0").assertIsDisplayed()
        compose.onNodeWithText("停止中も表示").performClick()
        compose.onNodeWithText("首").performClick()
        compose.onNodeWithText("2026-09-30").assertIsDisplayed()
        compose.onNodeWithText("再開する").performClick()
        compose.waitUntil { !repo.data.value.tasks.single().archived }
        assertEquals(1, repo.data.value.tasks.single().completions.size)
    }
    @Test fun settingsValidateThenAcceptZeroToTwentyFourHours() {
        val repo = ScreenRepository(); launch(repo)
        compose.onNodeWithContentDescription("通知設定").performClick()
        compose.onNodeWithText("開始（時）").performTextReplacement("24")
        compose.onNodeWithText("終了（時）").performTextReplacement("24")
        assertEquals("24", compose.onNodeWithText("開始（時）").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("24", compose.onNodeWithText("終了（時）").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        dismissKeyboard()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        val message = "開始は0〜23時、終了は1〜24時の整数で、開始より後に設定してください。間隔は5・10・15・30・60・120分です。"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settingsError", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertEquals(ReminderSettings(), repo.data.value.settings)
        compose.onNodeWithText("開始（時）").performTextReplacement("0")
        dismissKeyboard()
        compose.onNodeWithText("120分").performScrollTo().performClick()
        tapSave()
        runCatching { compose.waitUntil(10_000) { repo.data.value.settings == ReminderSettings(0, 24, 120) } }
            .onFailure { throw AssertionError("Saved settings: ${repo.data.value.settings}", it) }
    }
    @Test fun editDraftSurvivesStateRestoration() {
        val repo = ScreenRepository()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(repo, true, {}) } }
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("腰のストレッチ")
        dismissKeyboard()
        compose.onNodeWithText("繰り返しを設定").performClick()
        compose.onNodeWithText("繰り返し（日）").performTextReplacement("7")
        dismissKeyboard()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("腰のストレッチ").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        tapSave()
        compose.waitUntil { repo.data.value.tasks.size == 1 }
        assertEquals(7, repo.data.value.tasks.single().recurrenceDays)
    }
    @Test fun taskEditorSavesHourlyRecurrence() {
        val repo = ScreenRepository()
        launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("水を飲む")
        dismissKeyboard()
        compose.onNodeWithText("繰り返しを設定").performClick()
        compose.onNodeWithText("時間ごと").performClick()
        compose.onNodeWithText("繰り返し（時間）").performTextReplacement("2")
        compose.onNodeWithText("2").assertExists()
        tapSave()
        try {
            compose.waitUntil(10_000) { repo.data.value.tasks.singleOrNull()?.recurrenceHours == 2 }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("saveCalls=${repo.saveCalls}; tasks=${repo.data.value.tasks}; UI=${compose.onRoot().printToString()}", e)
        }
        assertEquals(1, repo.data.value.tasks.single().recurrenceDays)
    }
    @Test fun notificationRoutesToDetailAndMissingTaskReturnsSafely() {
        val repo = ScreenRepository(listOf(sampleTask())); launch(repo, initial = 1)
        compose.onNodeWithText("実行履歴（1回）").assertIsDisplayed()
        compose.runOnIdle { repo.data.value = repo.data.value.copy(tasks = emptyList()) }
        compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
    }
    @Test fun notificationRouteDoesNotOverrideRestoredList() {
        val repo = ScreenRepository(listOf(sampleTask()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(repo, true, {}, 1) } }
        compose.onNodeWithText("実行履歴（1回）").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("タスクを追加").assertIsDisplayed()
    }
    @Test fun editUpdatesTitleAndKeepsHistory() {
        val repo = ScreenRepository(listOf(sampleTask())); launch(repo, initial = 1)
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("腕")
        tapSave()
        compose.waitUntil { repo.data.value.tasks.single().title == "腕" }
        compose.onNodeWithText("2026-09-30").assertIsDisplayed()
    }
}

private fun listContent(tasks: List<TaskSnapshot>) = TaskListContent(
    tasks = tasks, showArchived = false, query = "", notificationsEnabled = true, permissionRequested = false,
    canSubmit = true, now = Instant.parse("2026-10-01T10:00:00Z"), zone = ZoneId.of("Asia/Tokyo"),
)
private fun listActions(onOpenTask: (Long) -> Unit = {}) =
    TaskListActions(onShowArchived = {}, onQueryChange = {}, onEnableNotifications = {}, onOpenTask = onOpenTask, onComplete = {})
private fun sampleTask() = TaskSnapshot(1, "首", 1, false, Instant.EPOCH, null, listOf(CompletionRecord(1, Instant.parse("2026-09-30T10:00:00Z"), LocalDate.of(2026, 9, 30))))
private class ScreenRepository(tasks: List<TaskSnapshot> = emptyList(), private val publishSaves: Boolean = true) : TaskRepository {
    var pendingTask: TaskSnapshot? = null
    var saveCalls = 0
    var gate: CompletableDeferred<Unit>? = null
    val data = MutableStateFlow(AppSnapshot(tasks, ReminderSettings(), false))
    override val snapshots = data
    override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int, recurrenceHours: Int?): Long {
        saveCalls++
        gate?.await()
        val taskId = id ?: (data.value.tasks.maxOfOrNull { it.id } ?: 0) + 1
        val task = data.value.tasks.find { it.id == taskId }?.copy(title = title, recurrenceDays = recurrenceDays, recurrenceHours = recurrenceHours)
            ?: TaskSnapshot(taskId, title, recurrenceDays, false, Instant.now(), null, emptyList(), recurrenceHours)
        pendingTask = task
        if (publishSaves) data.value = data.value.copy(tasks = data.value.tasks.filterNot { it.id == taskId } + task)
        return taskId
    }
    override suspend fun saveTasks(inputs: List<TaskInput>): List<Long> {
        saveCalls++
        gate?.await()
        val startId = (data.value.tasks.maxOfOrNull { it.id } ?: 0) + 1
        val added = inputs.mapIndexed { index, input -> TaskSnapshot(startId + index, input.title, input.recurrenceDays, false, Instant.now(), null, emptyList()) }
        if (publishSaves) data.value = data.value.copy(tasks = data.value.tasks + added)
        return added.map { it.id }
    }
    override suspend fun setArchived(id: Long, archived: Boolean) { data.value = data.value.copy(tasks = data.value.tasks.map { if (it.id == id) it.copy(archived = archived) else it }) }
    override suspend fun completeTask(id: Long) {
        data.value = data.value.copy(tasks = data.value.tasks.map { if (it.id == id) it.copy(completions = it.completions + CompletionRecord(2, Instant.now(), LocalDate.now())) else it })
    }
    override suspend fun saveSettings(settings: ReminderSettings) { data.value = data.value.copy(settings = settings) }
    override suspend fun recordPermissionRequest() { data.value = data.value.copy(permissionRequested = true) }
}

private fun isEmulator() = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")
