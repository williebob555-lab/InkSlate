package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.ControlBinding
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControlListener
import com.inksheets.core.ControlRef
import com.inksheets.core.Controllers
import com.inksheets.core.RemoteButton
import com.inksheets.core.RemoteLink
import com.inksheets.core.SpotControl
import com.inksheets.core.podgo.PodGoEvents

/**
 * Controllers plugged into this device - a foot controller, a POD Go over USB - and what their
 * switches, pedals and faders do here: any of the remote's actions, done just as a remote's press
 * is. Learning a control is pressing or moving it while the app listens.
 */
class ControllerHub(private val state: SheetsState) {
    /** The devices there now, by name. */
    val devices = mutableStateListOf<String>()
    /** What came in last, newest first: for seeing that a device talks, and what it sends. */
    val recent = mutableStateListOf<ControlEvent>()
    val bindings = mutableStateListOf<ControlBinding>().apply {
        val saved = Controllers.decode(state.platform.pref(K_BINDINGS))
        // A preset chosen did it at every press before "one way only" was there for it: kept so, once.
        if (state.platform.pref(K_ONE_WAY) == null) {
            addAll(saved.map { if (it.control.kind == ControlEvent.PROGRAM && !it.continuous && !it.whenOff) it.copy(everyMessage = true) else it })
            state.platform.setPref(K_BINDINGS, Controllers.encode(toList()))
            state.platform.setPref(K_ONE_WAY, "1")
        } else addAll(saved)
    }

    /** Listening for a control to give [learning]'s action to; what moved so far. */
    var learning by mutableStateOf<RemoteButton?>(null)
        private set
    private val heard = ArrayList<ControlEvent>()
    private val heardAt = ArrayList<Long>()
    /** The control heard while learning, and how it behaves, once it is clear. */
    var learned by mutableStateOf<ControlBinding?>(null)
        private set

    /** Which control the player put on each spot of a controller's picture ([PodGoPicture]), by spot. */
    val spots = mutableStateMapOf<String, SpotControl>().apply {
        putAll(Controllers.decodeSpots(state.platform.pref(K_SPOTS)).mapValues { (_, s) -> if (s.control.device == PodGoEvents.DEVICE) s.copy(momentary = false) else s })
    }
    /** When each control was last heard, and its value: for the picture to light it, and show a pedal's travel. */
    val lastHeard = mutableStateMapOf<ControlRef, Pair<Long, Int>>()
    /** When anything was last heard. */
    var lastAt by mutableStateOf(0L)
        private set

    /** Listening for the control to put on this spot; what came since, most likely first. */
    var placing by mutableStateOf<String?>(null)
        private set
    val placingHeard = mutableStateListOf<ControlRef>()
    private var listener: ControlListener? = null

    var on by mutableStateOf(state.platform.pref(K_ON) != "false")
        private set
    private var started = false
    private val taps = ArrayList<Long>()
    /** Each switch on or off as last heard: for one done one way only. */
    private val switched = HashMap<ControlRef, Boolean>()

    val available: Boolean get() = state.platform.controllers != null

    fun start() {
        if (started || !on) return
        val input = state.platform.controllers ?: return
        started = true
        // What a POD Go says over USB, written into the library (Training/PodGo) - it syncs to the
        // other devices, so working out its footswitches needs nothing copied by hand.
        com.inksheets.core.podgo.PodGoEvents.record = { line ->
            runCatching {
                val root = state.root ?: return@runCatching
                val dir = java.io.File(root, "${com.inksheets.core.LibraryScan.TRAINING}/PodGo").apply { mkdirs() }
                val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                synchronized(this) { java.io.File(dir, "podgo-${state.platform.deviceId}.txt").appendText("$at  $line\n") }
            }
        }
        runCatching {
            input.start(
                onEvent = { e -> state.platform.onMain { heard(e) } },
                onDevices = { names -> state.platform.onMain { devices.clear(); devices.addAll(names) } }
            )
        }.onFailure { started = false; state.platform.log("Controllers: could not start - ${it.message}") }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { state.platform.controllers?.stop() }
        devices.clear()
    }

    fun turn(value: Boolean) {
        on = value
        state.platform.setPref(K_ON, value.toString())
        if (value) start() else stop()
    }

    /** Listen for the control to do [action]: the next one pressed or moved. */
    fun learn(action: RemoteButton) { learning = action; heard.clear(); heardAt.clear(); learned = null }

    fun cancelLearning() { learning = null; heard.clear(); heardAt.clear(); learned = null }

    /** Keep what was learned. */
    fun keepLearned() {
        learned?.let { b -> bindings.removeAll { it.control == b.control && it.action == b.action }; bindings += b; save() }
        cancelLearning()
    }

    fun remove(b: ControlBinding) { bindings.remove(b); save() }

    private fun save() = state.platform.setPref(K_BINDINGS, Controllers.encode(bindings.toList()))
    private fun saveSpots() = state.platform.setPref(K_SPOTS, Controllers.encodeSpots(spots.toMap()))

    /** Listen for the control to put on [spot]: the next pressed or moved. What was already talking is offered last. */
    fun place(spot: String) {
        val now = System.currentTimeMillis()
        listener = ControlListener(chatter = lastHeard.filterValues { now - it.first < 3000 }.keys.toSet())
        placingHeard.clear()
        placing = spot
    }

    fun cancelPlacing() { placing = null; listener = null; placingHeard.clear() }

    /** How many times [c] was heard while listening. */
    fun placingCount(c: ControlRef): Int = listener?.count(c) ?: 0

    /**
     * Put [control] on [spot]. Learned again, the spot keeps its actions: they move to the new
     * control (unless another spot still has the old one).
     */
    fun keepPlaced(spot: String, control: ControlRef) {
        // (A POD Go's switch says lit or dark, one message a press - two quick presses are not a press and a let-go.)
        val placed = SpotControl(control, momentary = control.device != PodGoEvents.DEVICE && listener?.momentary(control) == true)
        val old = spots[spot]
        spots[spot] = placed
        if (old != null && old.control != control && spots.none { (id, s) -> id != spot && s.control == old.control }) {
            val moved = bindings.filter { it.control == old.control }
            bindings.removeAll(moved)
            for (b in moved) bindings.removeAll { it.control == control && it.action == b.action }
            // (A switch keeps which way it does it: every press, or one way of two.)
            bindings += moved.map { m -> Controllers.bindingFor(placed, m.action).let { b -> if (b.continuous || m.continuous) b else b.copy(everyMessage = m.everyMessage, whenOff = m.whenOff) } }
            save()
        }
        saveSpots()
        cancelPlacing()
    }

    /** Take [spot] off the picture, and its control's actions with it (unless another spot has the same control). */
    fun clearSpot(spot: String) {
        val old = spots.remove(spot) ?: return
        if (spots.values.none { it.control == old.control }) { bindings.removeAll { it.control == old.control }; save() }
        saveSpots()
    }

    /** What [spot]'s control does. */
    fun bindingsOf(spot: String): List<ControlBinding> = spots[spot]?.let { s -> bindings.filter { it.control == s.control } }.orEmpty()

    /**
     * When a switch does [b]: at [every] press, or - a toggle, lit at one press and dark at the
     * next - only as it lights, or only as it goes dark ([off]): pressed twice, done once.
     */
    fun setWhen(b: ControlBinding, every: Boolean, off: Boolean) {
        val i = bindings.indexOf(b)
        if (i < 0) return
        bindings[i] = b.copy(everyMessage = every, whenOff = !every && off)
        save()
    }

    /** Give [spot]'s control [action] too. */
    fun bindSpot(spot: String, action: RemoteButton) {
        val s = spots[spot] ?: return
        val b = Controllers.bindingFor(s, action)
        bindings.removeAll { it.control == b.control && it.action == b.action }
        bindings += b
        save()
    }

    internal fun heard(e: ControlEvent) {
        // A POD Go heard over USB says over its MIDI port again what matters: once is enough.
        if (com.inksheets.core.podgo.PodGoEvents.sameUnit(e.device) && com.inksheets.core.podgo.PodGoEvents.DEVICE in devices) return
        recent.add(0, e)
        while (recent.size > 12) recent.removeAt(recent.size - 1)
        val now = System.currentTimeMillis()
        lastHeard[e.control] = now to e.value
        lastAt = now
        listener?.let { l ->
            // Placing a control on the picture: nothing fires till it is put there.
            l.hear(e, now)
            val c = l.candidates
            if (c != placingHeard.toList()) { placingHeard.clear(); placingHeard.addAll(c) }
            return
        }
        learning?.let { action -> learnFrom(action, e); return }
        val on = Controllers.on(e, switched[e.control]).also { switched[e.control] = it }
        for (b in Controllers.firing(bindings, e, on)) fire(b, e)
    }

    private fun learnFrom(action: RemoteButton, e: ControlEvent) {
        val now = System.currentTimeMillis()
        if (heard.firstOrNull()?.control != e.control) { heard.clear(); heardAt.clear() }
        heard += e; heardAt += now
        val sweep = Controllers.isSweep(action) && e.kind == ControlEvent.CC
        // A switch's press: a momentary one sends on (127) and, let go, off (0) moments later; one
        // that sends a single message a press (on one press, off the next - a POD Go footswitch set
        // to toggle) is taken at every message. Until a quick "off" follows, it is taken as that.
        val letGo = (1 until heard.size).any { i -> heard[i - 1].value >= 64 && heard[i].value < 64 && heardAt[i] - heardAt[i - 1] < 3000 }
        learned = ControlBinding(
            control = e.control,
            action = action,
            continuous = sweep,
            everyMessage = !sweep && Controllers.everyPress(e.kind, momentary = letGo)
        )
    }

    private fun fire(b: ControlBinding, e: ControlEvent) {
        val a = b.action
        when (a.kind) {
            RemoteButton.MACRO -> {
                // A step at a time, with a moment between: a song has to open before its page turns.
                Thread({
                    for (step in a.steps) {
                        state.platform.onMain { act(step, null) }
                        Thread.sleep(if (step.kind == RemoteButton.SONG || step.kind == RemoteButton.SETLIST) 900 else 300)
                    }
                }, "controller-steps").apply { isDaemon = true; start() }
            }
            RemoteButton.TAP -> {
                taps += System.currentTimeMillis()
                while (taps.size > 12) taps.removeAt(0)
                com.inksheets.core.Metronome.tapTempo(taps)?.let { bpm -> act(a, kotlin.math.round(bpm).coerceIn(20.0, 300.0)) }
            }
            else -> act(a, if (b.continuous) Controllers.valueOf(b, e.value) else null)
        }
    }

    private fun act(a: RemoteButton, value: Double?) {
        // (A list to pick from has nothing for a foot - unless chosen already: a bookmark, an instrument.)
        if (a.kind in PICKERS && a.id == null) return
        state.remote.performHere(RemoteLink.Command(
            action = if (a.kind == RemoteButton.ACTION) a.id.orEmpty() else a.kind,
            id = a.id, text = a.text, value = value ?: a.value, color = a.color, urgent = a.urgent
        ))
    }

    companion object {
        /** Kinds that open a list on a remote to pick from: nothing for a foot to do. */
        val PICKERS = setOf(RemoteButton.SONGS, RemoteButton.SET, RemoteButton.PARTS, RemoteButton.PROFILES,
            RemoteButton.BOOKMARKS, RemoteButton.MESSAGE_TYPE, RemoteButton.TOUCHPAD)
        private const val K_BINDINGS = "sheets_controller_bindings"
        private const val K_ON = "sheets_controllers_on"
        private const val K_SPOTS = "sheets_controller_spots"
        private const val K_ONE_WAY = "sheets_controller_one_way"

        /** What a control is called in a list: "CC 71 (ch 1) on POD Go". */
        fun name(c: ControlRef): String = c.short() + if (c.device.isNotEmpty()) " on ${c.device}" else ""
    }
}
