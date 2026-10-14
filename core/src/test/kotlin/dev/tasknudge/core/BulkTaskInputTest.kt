package dev.tasknudge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BulkTaskInputTest {
    @Test fun eachNonblankLineBecomesATaskWithTheSharedInterval() {
        assertEquals(
            listOf(TaskInput("首", 3), TaskInput("腕", 3), TaskInput("首", 3)),
            validateBulkTaskInput(" 首 \r\n\r\n 腕\n首  ", 3),
        )
    }

    @Test fun invalidLineRejectsTheWholeInput() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBulkTaskInput("首\n${"長".repeat(101)}", 1)
        }
        assertThrows(IllegalArgumentException::class.java) { validateBulkTaskInput(" \n\t", 1) }
        assertThrows(IllegalArgumentException::class.java) { validateBulkTaskInput("首\n腕", 0) }
    }

    @Test fun moreThanOneHundredTasksIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBulkTaskInput(List(101) { "タスク$it" }.joinToString("\n"), 1)
        }
    }
}
