package dev.tasknudge.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import dev.tasknudge.core.ReminderSettings
import dev.tasknudge.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TaskScreensTest {
    @get:Rule val compose = createComposeRule()
    private fun launch(repo: ScreenRepository, enabled: Boolean = true, initial: Long? = null, enable: () -> Unit = {}) {
        compose.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(repo, enabled, enable, initial) } }
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
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.data.value.tasks.size == 1 }
        assertEquals("首のストレッチ", repo.data.value.tasks.single().title)
        assertEquals(1, repo.data.value.tasks.single().recurrenceDays)
        compose.onNodeWithText("首のストレッチ").assertIsDisplayed()
    }
    @Test fun committedAddWaitsForDisplayDataWithoutOfferingDuplicateSave() {
        val repo = ScreenRepository(publishSaves = false); launch(repo)
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("首のストレッチ")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.pendingTask != null }
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
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.pendingTask != null }
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
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.saveCalls == 1 }
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("腰の下書き")
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
        val oldSave = compose.onNodeWithText("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("腰").performClick()
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithText("タイトル").performTextReplacement("腰の下書き")
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
        val oldSave = compose.onNodeWithText("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("新しい下書き")
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
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("タイトルは1〜100文字で入力してください", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("タイトルは1〜100文字で入力してください").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("タイトル").performTextInput("a".repeat(101))
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("タイトルは1〜100文字で入力してください", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("タイトルは1〜100文字で入力してください").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("タイトル").performTextReplacement("腕")
        compose.onNodeWithText("繰り返しを設定").performClick()
        compose.onNodeWithText("繰り返し（日）").performTextReplacement("0")
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
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
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        val message = "開始は0〜23時、終了は1〜24時の整数で、開始より後に設定してください。間隔は30・60・120分です。"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settingsError", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertEquals(ReminderSettings(), repo.data.value.settings)
        compose.onNodeWithText("開始（時）").performTextReplacement("0")
        compose.onNodeWithText("120分").performClick()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil { repo.data.value.settings == ReminderSettings(0, 24, 120) }
    }
    @Test fun editDraftSurvivesStateRestoration() {
        val repo = ScreenRepository()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TaskNudgeTheme(dynamicColor = false) { TaskNudgeApp(repo, true, {}) } }
        compose.onNodeWithContentDescription("タスクを追加").performClick()
        compose.onNodeWithText("タイトル").performTextInput("腰のストレッチ")
        compose.onNodeWithText("繰り返しを設定").performClick()
        compose.onNodeWithText("繰り返し（日）").performTextReplacement("7")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("腰のストレッチ").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.data.value.tasks.size == 1 }
        assertEquals(7, repo.data.value.tasks.single().recurrenceDays)
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
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil { repo.data.value.tasks.single().title == "腕" }
        compose.onNodeWithText("2026-09-30").assertIsDisplayed()
    }
}

private fun sampleTask() = TaskSnapshot(1, "首", 1, false, Instant.EPOCH, null, listOf(CompletionRecord(1, Instant.parse("2026-09-30T10:00:00Z"), LocalDate.of(2026, 9, 30))))
private class ScreenRepository(tasks: List<TaskSnapshot> = emptyList(), private val publishSaves: Boolean = true) : TaskRepository {
    var pendingTask: TaskSnapshot? = null
    var saveCalls = 0
    var gate: CompletableDeferred<Unit>? = null
    val data = MutableStateFlow(AppSnapshot(tasks, ReminderSettings(), false))
    override val snapshots = data
    override suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int): Long {
        saveCalls++
        gate?.await()
        val taskId = id ?: (data.value.tasks.maxOfOrNull { it.id } ?: 0) + 1
        val task = data.value.tasks.find { it.id == taskId }?.copy(title = title, recurrenceDays = recurrenceDays)
            ?: TaskSnapshot(taskId, title, recurrenceDays, false, Instant.now(), null, emptyList())
        pendingTask = task
        if (publishSaves) data.value = data.value.copy(tasks = data.value.tasks.filterNot { it.id == taskId } + task)
        return taskId
    }
    override suspend fun setArchived(id: Long, archived: Boolean) { data.value = data.value.copy(tasks = data.value.tasks.map { if (it.id == id) it.copy(archived = archived) else it }) }
    override suspend fun completeTask(id: Long) {
        data.value = data.value.copy(tasks = data.value.tasks.map { if (it.id == id) it.copy(completions = it.completions + CompletionRecord(2, Instant.now(), LocalDate.now())) else it })
    }
    override suspend fun saveSettings(settings: ReminderSettings) { data.value = data.value.copy(settings = settings) }
    override suspend fun recordPermissionRequest() { data.value = data.value.copy(permissionRequested = true) }
}
