package dev.aarso.brwsr.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.aarso.brwsr.core.PinchIn
import kotlin.math.hypot

/**
 * Hosts the page's WebView and watches (never steals) its touches for a two-finger pinch-in.
 * When the page is already at its smallest zoom, closing the fingers opens the tab overview —
 * the WebView is cancelled so it doesn't also react, and the rest of that gesture is swallowed.
 * Zoomed in, the pinch belongs to the page, as usual.
 */
class PinchFrame(context: Context) : FrameLayout(context) {
    var onPinchIn: (() -> Unit)? = null
    var canPinch: () -> Boolean = { false }

    private val pinch = PinchIn()
    private var swallowing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pinch.cancel(); swallowing = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = swallowing
                swallowing = false
                pinch.cancel()
                if (was) return true
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                if (ev.pointerCount == 2) pinch.begin(span(ev), armed = canPinch()) else pinch.cancel()
            MotionEvent.ACTION_POINTER_UP -> pinch.cancel()
        }
        if (swallowing) return true
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.pointerCount == 2 && pinch.update(span(ev))) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            swallowing = true
            onPinchIn?.invoke()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun span(ev: MotionEvent) = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
}
