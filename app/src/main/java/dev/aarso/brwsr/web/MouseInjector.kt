package dev.aarso.brwsr.web

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Speaks to the WebView as a real mouse would: hover moves, button presses (primary and
 * secondary — Chromium turns secondary into a `contextmenu`), and wheel scrolls at the cursor
 * (so the element under the cursor scrolls, not just the page). Coordinates are WebView px.
 */
class MouseInjector(private val webView: () -> WebView?) {
    private var downTime = 0L
    private var buttons = 0

    fun hover(x: Float, y: Float) {
        val wv = webView() ?: return
        val action = if (buttons != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
        val e = event(action, x, y)
        if (buttons != 0) wv.dispatchTouchEvent(e) else wv.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    fun press(x: Float, y: Float, secondary: Boolean) {
        val wv = webView() ?: return
        if (buttons != 0) release(x, y)
        downTime = SystemClock.uptimeMillis()
        buttons = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        val e = event(MotionEvent.ACTION_DOWN, x, y)
        wv.dispatchTouchEvent(e)
        e.recycle()
    }

    fun release(x: Float, y: Float) {
        val wv = webView() ?: return
        if (buttons == 0) return
        val e = event(MotionEvent.ACTION_UP, x, y, buttonState = 0)
        wv.dispatchTouchEvent(e)
        e.recycle()
        buttons = 0
        hover(x, y)
    }

    fun click(x: Float, y: Float, secondary: Boolean) {
        press(x, y, secondary)
        release(x, y)
    }

    /** [notchesY] > 0 scrolls content up (like a wheel turned away from you). */
    fun scroll(x: Float, y: Float, notchesX: Float, notchesY: Float) {
        val wv = webView() ?: return
        val e = event(MotionEvent.ACTION_SCROLL, x, y, vScroll = notchesY, hScroll = notchesX)
        wv.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private fun event(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = buttons,
        vScroll: Float = 0f,
        hScroll: Float = 0f,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = if (buttonState != 0) 1f else 0f; size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            if (downTime == 0L) now else downTime, now, action, 1, props, coords,
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }
}
