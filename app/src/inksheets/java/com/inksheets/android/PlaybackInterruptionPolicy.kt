package com.inksheets.android

/** User intent survives temporary focus loss, but never a pause, unplug, completion or replacement. */
internal class PlaybackInterruptionPolicy {
    var playRequested = false
        private set
    var waitingForFocus = false
        private set

    fun userPlay() { playRequested = true; waitingForFocus = false }
    fun userPause() { playRequested = false; waitingForFocus = false }
    fun focusLost(transient: Boolean, wasPlaying: Boolean) {
        if (transient) waitingForFocus = playRequested && (wasPlaying || waitingForFocus)
        else userPause()
    }
    fun focusGained(): Boolean {
        val resume = playRequested && waitingForFocus
        waitingForFocus = false
        return resume
    }
    fun headphonesDisconnected() = userPause()
    fun completed() = userPause()
    fun failed() = userPause()
}
