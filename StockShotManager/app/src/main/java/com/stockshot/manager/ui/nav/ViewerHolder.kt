package com.stockshot.manager.ui.nav

import com.stockshot.manager.ui.vm.ViewerSession

/**
 * Holds the ViewerSession between navigation transitions. Compose Navigation does not
 * carry large parcelable lists well, so the source screen sets this before navigating to
 * the full-screen image viewer.
 */
object ViewerHolder {
    @Volatile
    var session: ViewerSession = ViewerSession()
}
