package dev.tasknudge

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Logs what the device showed when a test failed: the focused window, the IME state,
 * and the window hierarchy. CI keeps the job log even when artifacts are unavailable;
 * scripts/device-smoke.sh prints these lines after a failed run.
 * Order this rule inside the Compose rule so the tested Activity still exists.
 * A test that closes its own ActivityScenario calls [captureNow] before closing it,
 * because the rule sees the failure only after the test body has finished.
 */
class FailureDiagnostics : TestWatcher() {
    private var testName = ""
    private var captured = false

    override fun starting(description: Description) {
        testName = description.methodName
        captured = false
    }

    override fun failed(e: Throwable, description: Description) = captureNow()

    fun captureNow() {
        if (captured) return
        captured = true
        val name = testName
        runCatching {
            log(name, "window", shell("dumpsys window").lines().filter { line ->
                listOf("mCurrentFocus", "mFocusedApp", "mFocusedWindow", "mTopFullscreenOpaqueWindowState").any { it in line }
            })
            log(name, "ime", shell("dumpsys input_method").lines().filter { line ->
                listOf("mInputShown", "mCurFocusedWindow ", "mImeWindowVis", "isInputViewShown").any { it in line }
            })
            val hierarchy = ByteArrayOutputStream()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).dumpWindowHierarchy(hierarchy)
            log(name, "hierarchy", hierarchy.toString(Charsets.UTF_8.name()).chunked(LOG_CHUNK))
        }.onFailure { Log.e(TAG, "$name: diagnostics failed", it) }
    }

    private fun log(test: String, section: String, lines: List<String>) {
        lines.forEach { Log.e(TAG, "$test [$section] ${it.trim()}") }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        const val TAG = "TaskNudgeDiag"
        private const val LOG_CHUNK = 3000
    }
}
