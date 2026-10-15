package dev.tasknudge.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.ceil

/** Keeps the insertion point intact while a long task line is panned by touch. */
internal class BulkTaskEditText(context: Context) : EditText(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var selectionAtDownStart = 0
    private var selectionAtDownEnd = 0
    private var panning = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                selectionAtDownStart = selectionStart
                selectionAtDownEnd = selectionEnd
                panning = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!panning && abs(event.x - downX) > touchSlop &&
                    abs(event.x - downX) > abs(event.y - downY)
                ) {
                    panning = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    if (selectionAtDownStart >= 0 && selectionAtDownEnd >= 0) {
                        setSelection(selectionAtDownStart, selectionAtDownEnd)
                    }
                }
                if (panning) {
                    panBy(lastX - event.x)
                    lastX = event.x
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (panning) {
                    panning = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun panBy(distance: Float) {
        val textLayout = layout ?: return
        val lineWidth = (0 until textLayout.lineCount).maxOfOrNull { textLayout.getLineRight(it) } ?: return
        val viewportWidth = width - totalPaddingLeft - totalPaddingRight
        val maxScroll = (ceil(lineWidth).toInt() - viewportWidth).coerceAtLeast(0)
        scrollTo((scrollX + distance.toInt()).coerceIn(0, maxScroll), scrollY)
    }
}
