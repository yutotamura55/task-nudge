package dev.tasknudge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskInputTest {
    @Test fun titleOnlyUsesDailyRecurrence() {
        assertEquals(TaskInput("首のストレッチ", 1), validateTaskInput(" 首のストレッチ "))
    }

    @Test fun blankTitleIsRejected() {
        for (title in listOf("", " \t\n ")) {
            assertThrows(IllegalArgumentException::class.java) { validateTaskInput(title) }
        }
    }

    @Test fun titleLimitIsMeasuredAfterTrimming() {
        val title = "あ".repeat(100)
        assertEquals(TaskInput(title, 1), validateTaskInput(" $title "))
        assertThrows(IllegalArgumentException::class.java) { validateTaskInput("あ".repeat(101)) }
    }

    @Test fun recurrenceAcceptsDailyAnd365DayBoundaries() {
        assertEquals(TaskInput("首", 1), validateTaskInput("首", 1))
        assertEquals(TaskInput("首", 365), validateTaskInput("首", 365))
    }

    @Test fun recurrenceRejectsValuesOutsideBoundaries() {
        for (days in listOf(0, -1, 366, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { validateTaskInput("首", days) }
        }
    }

    @Test fun settingsAcceptValidHoursAndSupportedCadences() {
        assertEquals(ReminderSettings(), validateSettings(ReminderSettings()))
        for (minutes in listOf(30, 60, 120)) {
            val settings = ReminderSettings(0, 24, minutes)
            assertEquals(settings, validateSettings(settings))
        }
        assertEquals(ReminderSettings(23, 24), validateSettings(ReminderSettings(23, 24)))
    }

    @Test fun settingsRejectHoursOutsideRanges() {
        for (settings in listOf(ReminderSettings(-1, 21), ReminderSettings(24, 24), ReminderSettings(0, 0), ReminderSettings(9, 25))) {
            assertThrows(IllegalArgumentException::class.java) { validateSettings(settings) }
        }
    }

    @Test fun settingsRejectEmptyOrReversedWindow() {
        assertThrows(IllegalArgumentException::class.java) { validateSettings(ReminderSettings(9, 9)) }
        assertThrows(IllegalArgumentException::class.java) { validateSettings(ReminderSettings(21, 9)) }
    }

    @Test fun settingsRejectUnsupportedCadence() {
        for (minutes in listOf(-1, 0, 15, 31, 90, 121, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { validateSettings(ReminderSettings(intervalMinutes = minutes)) }
        }
    }
}
