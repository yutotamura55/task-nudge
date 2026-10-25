package dev.tasknudge

import android.app.Activity
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Takes focus off the text field and waits until the window has reserved no space for
 * a keyboard for [KEYBOARD_QUIET_MS]. Call this after typing and before tapping or
 * checking anything the keyboard area could move.
 *
 * The app pads its layout by the keyboard area (imePadding). The system updates that
 * area asynchronously, even with every keyboard disabled, and Compose idling does not
 * wait for it. A show request made before the hide can still arrive after the area
 * first disappears, so a single check is not enough.
 */
fun ComposeTestRule.closeKeyboard() {
    runOnIdle {
        val activity = resumedActivity()
        activity.currentFocus?.let { focused ->
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }
    waitForKeyboardArea { height -> height == 0 }
}

/**
 * Waits until the keyboard area, shown or not, has kept the same height for [KEYBOARD_QUIET_MS].
 * Use it when a test must keep a text field focused, before measuring positions for a
 * gesture.
 */
fun ComposeTestRule.awaitKeyboardAreaSettled() = waitForKeyboardArea { true }

private fun ComposeTestRule.waitForKeyboardArea(accept: (height: Int) -> Boolean) {
    var last: Int? = null
    var since = 0L
    waitUntil(KEYBOARD_TIMEOUT_MS) {
        // Read the time after the height: the read waits for the main thread.
        val height = keyboardAreaHeight()
        val now = SystemClock.uptimeMillis()
        if (height != last) { last = height; since = now }
        accept(height) && now - since >= KEYBOARD_QUIET_MS
    }
}

private fun keyboardAreaHeight(): Int {
    var height = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val insets = ViewCompat.getRootWindowInsets(resumedActivity().window.decorView)
        if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
            height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
    }
    return height
}

private fun resumedActivity(): Activity =
    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()

private const val KEYBOARD_TIMEOUT_MS = 5_000L
private const val KEYBOARD_QUIET_MS = 500L
