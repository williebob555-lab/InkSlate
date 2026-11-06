package com.inksheets.ui

/** Auto-turns until the reader deliberately changes page; manual browsing then stays in control. */
internal class PlaybackPageFollow(startPage: Int) {
    private var expectedPage = startPage
    private var automaticTurnDeadline = 0L
    var suspended: Boolean = false
        private set

    fun observe(page: Int, nowMs: Long) {
        if (!suspended && page != expectedPage && nowMs > automaticTurnDeadline) suspended = true
    }

    /** True when playback should issue an automatic turn to [page]. */
    fun turnTo(page: Int, shownPage: Int, nowMs: Long): Boolean {
        observe(shownPage, nowMs)
        if (suspended || page == shownPage) {
            if (!suspended) expectedPage = shownPage
            return false
        }
        // A viewer page turn is asynchronous. The 100 ms playback watcher can see the old page
        // several more times before it settles; issuing the same jump each time queues competing
        // animations/loads and can make playback appear to skip around. One request stays pending
        // until the viewer reaches it or its settling deadline expires.
        if (page == expectedPage && nowMs <= automaticTurnDeadline) return false
        expectedPage = page
        automaticTurnDeadline = nowMs + 1_200L
        return true
    }
}
