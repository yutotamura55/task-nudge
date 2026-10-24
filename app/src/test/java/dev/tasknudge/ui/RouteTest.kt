package dev.tasknudge.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTest {
    private val routes = listOf(Route.TaskList, Route.Detail(7), Route.Editor(null), Route.Editor(7), Route.Bulk, Route.Settings)

    @Test fun everyRouteSurvivesStateRestoration() {
        val scope = SaverScope { true }
        for (route in routes) {
            val saved = with(Route.saver) { scope.save(route) }!!
            assertEquals(route, Route.saver.restore(saved))
        }
    }

    @Test fun editingReturnsToItsTaskAndOtherScreensReturnToTheList() {
        assertEquals(Route.Detail(7), Route.Editor(7).back)
        for (route in listOf(Route.Editor(null), Route.Detail(7), Route.Bulk, Route.Settings)) {
            assertEquals(Route.TaskList, route.back)
        }
    }

    @Test fun onlyTaskScreensRequireTheirTask() {
        assertEquals(7L, Route.Detail(7).requiredTaskId)
        assertEquals(7L, Route.Editor(7).requiredTaskId)
        for (route in listOf(Route.TaskList, Route.Editor(null), Route.Bulk, Route.Settings)) assertNull(route.requiredTaskId)
    }

    @Test fun titlesDistinguishAddingFromEditing() {
        assertEquals(listOf("タスク", "タスクの詳細", "タスクを追加", "タスクを編集", "まとめて追加", "通知設定"), routes.map { it.title })
    }
}
