package com.telnyx.webrtc.sdk.utility

import android.view.View

inline fun <T : View> T.showIf(predicate: T.() -> Boolean): T = this.apply {
    if (predicate(this)) {
        show()
    } else {
        hide()
    }
    return this
}

fun View.hide() {
    if (this.visibility != View.GONE) {
        this.visibility = View.GONE
    }
}

fun View.show() {
    if (this.visibility != View.VISIBLE) {
        this.visibility = View.VISIBLE
    }
}

