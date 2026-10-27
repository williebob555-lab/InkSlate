package com.inksheets.desktop

import com.inksheets.core.CompanionLink
import com.inksheets.core.MeshFrames
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.MeshRadio
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

/** All radios and dispatch are fake: no real Bluetooth, Wi-Fi, or audio. */
class StandaloneBluetoothTest {
    private class Radio : MeshRadio {
        var allowed = true
        var heard: ((ByteArray) -> Unit)? = null
        var frames = emptyList<ByteArray>()
        override fun ready() = allowed
        override fun ready(ask: Boolean) = allowed
        override fun start(onFrame: (ByteArray) -> Unit): Boolean { heard = onFrame; return allowed }
        override fun broadcast(frames: List<ByteArray>) { this.frames = frames }
        override fun stop() { heard = null }
        fun receive(frame: MeshFrames.Frame) { heard?.invoke(MeshFrames.encode(frame)) }
    }
    private class Platform(val root: File, val radio: Radio) : SheetsPlatform {
        val prefs = hashMapOf<String,String?>("sheets_library" to root.path, "sheets_companion_transport" to "BLUETOOTH")
        override val deviceId = "standalone-test"
        override val deviceName = "Test stand"
        override val audioOut: com.inksheets.ui.AudioOut? = null
        override val microphone: com.inksheets.ui.Microphone? = null
        override val startFolder = root
        override val localFolder = File(root, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override fun onMain(block: () -> Unit) = block()
        override fun meshRadio() = radio
    }
    @Test fun bluetoothLeaderJoinCodeNeedsNoIpAndListeningDoesNotClaimFollowers() {
        val radio = Radio()
        val state = SheetsState(Platform(Files.createTempDirectory("bt-lead").toFile(), radio))
        try {
            assertTrue(state.companion.lead())
            val code = CompanionLink.parseJoin(state.companion.joinLink!!)!!
            assertTrue(code.hosts.isEmpty())
            assertNotNull(code.bluetoothSession)
            assertEquals(0, state.companion.followers)
        } finally { state.companion.stopLeading() }
    }
    @Test fun bluetoothFollowWaitsForActualLeaderBeaconAndIgnoresWrongSession() {
        val radio = Radio()
        val state = SheetsState(Platform(Files.createTempDirectory("bt-follow").toFile(), radio))
        var joined = false
        try {
            state.companion.followLeader(CompanionLink.Leader("Offline stand", emptyList(), bluetoothSession = 1234)) { joined = it }
            assertFalse(state.companion.connected)
            assertFalse(joined)
            radio.receive(MeshFrames.Beacon(999, 1, "Other"))
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(1234, 1, "Offline"))
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(1234, 2, "Offline"))
            assertTrue(state.companion.connected)
            assertTrue(joined)
        } finally { state.companion.stopFollowing() }
    }
    @Test fun deniedBluetoothPermissionNeverClaimsConnected() {
        val radio = Radio().apply { allowed = false }
        val state = SheetsState(Platform(Files.createTempDirectory("bt-denied").toFile(), radio))
        try {
            state.companion.followLeader(CompanionLink.Leader("Stand", emptyList(), bluetoothSession = 12)) {}
            assertFalse(state.companion.connected)
            assertNull(radio.heard)
            assertTrue(state.companion.meshStatus!!.contains("permission"))
        } finally { state.companion.stopFollowing() }
    }
    @Test fun staleAndRepeatedBeaconCannotKeepBluetoothConnected() {
        val radio = Radio()
        val state = SheetsState(Platform(Files.createTempDirectory("bt-stale").toFile(), radio))
        var now = 1000L
        state.companion.connectionClock = { now }
        try {
            state.companion.followLeader(CompanionLink.Leader("Stand", emptyList(), bluetoothSession = 12)) {}
            radio.receive(MeshFrames.Beacon(12, 1, "Stand"))
            radio.receive(MeshFrames.Beacon(12, 2, "Stand"))
            assertTrue(state.companion.connected)
            now += 4100
            radio.receive(MeshFrames.Beacon(12, 1, "Stand"))
            state.companion.tickBluetooth()
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 3, "Stand"))
            assertTrue(state.companion.connected)
        } finally { state.companion.stopFollowing() }
    }
    @Test fun quietRetryRecoversAfterPermissionGrantWithoutWifi() {
        val radio = Radio().apply { allowed = false }
        val state = SheetsState(Platform(Files.createTempDirectory("bt-retry").toFile(), radio))
        try {
            state.companion.followLeader(CompanionLink.Leader("Stand", emptyList(), bluetoothSession = 12)) {}
            assertNull(radio.heard)
            radio.allowed = true
            state.companion.tickBluetooth()
            assertNotNull(radio.heard)
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 1, "Stand"))
            radio.receive(MeshFrames.Beacon(12, 3, "Stand"))
            assertTrue(state.companion.connected)
        } finally { state.companion.stopFollowing() }
    }
    @Test fun restartedLeaderNeedsAdvancingBaselineAndOldRadioCannotReviveStoppedSession() {
        val radio = Radio()
        val state = SheetsState(Platform(Files.createTempDirectory("bt-restart").toFile(), radio))
        var now = 1000L
        state.companion.connectionClock = { now }
        val leader = CompanionLink.Leader("Stand", emptyList(), bluetoothSession = 12)
        try {
            state.companion.followLeader(leader) {}
            radio.receive(MeshFrames.Beacon(12, 100, "Stand"))
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 101, "Stand"))
            assertTrue(state.companion.connected)
            now += 8000
            state.companion.tickBluetooth()
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 1, "Stand"))
            radio.receive(MeshFrames.Beacon(12, 1, "Stand"))
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 2, "Stand"))
            assertTrue(state.companion.connected)
            val oldCallback = radio.heard!!
            state.companion.stopFollowing()
            state.companion.followLeader(leader) {}
            oldCallback(MeshFrames.encode(MeshFrames.Beacon(12, 102, "Stand")))
            oldCallback(MeshFrames.encode(MeshFrames.Beacon(12, 103, "Stand")))
            assertFalse(state.companion.connected)
            radio.receive(MeshFrames.Beacon(12, 3, "Stand"))
            radio.receive(MeshFrames.Beacon(12, 4, "Stand"))
            assertTrue(state.companion.connected)
        } finally { state.companion.stopFollowing() }
    }
    @Test fun leaderRosterUsesDistinctInboundIdentitiesAndShowsStalePeersReconnecting() {
        val radio = Radio()
        val state = SheetsState(Platform(Files.createTempDirectory("bt-roster").toFile(), radio))
        var now = 1000L
        state.companion.connectionClock = { now }
        try {
            assertTrue(state.companion.lead())
            state.companion.tickBluetooth()
            val session = CompanionLink.parseJoin(state.companion.joinLink!!)!!.bluetoothSession!!
            radio.receive(MeshFrames.Presence(session, 1, 1, 1, name = "Stand"))
            radio.receive(MeshFrames.Presence(session, 2, 1, 1, name = "Stand"))
            assertEquals(2, state.companion.followers)
            assertEquals(2, state.companion.connectedDevices.size)
            assertEquals(setOf("participant:1", "participant:2"), state.companion.connectedDevices.map { it.id }.toSet())
            now += 4100
            state.companion.tickBluetooth()
            assertEquals(0, state.companion.followers)
            assertTrue(state.companion.connectedDevices.all { it.status == com.inksheets.core.ConnectionStatus.RECONNECTING })
            now += 3000
            state.companion.tickBluetooth()
            assertTrue(state.companion.connectedDevices.isEmpty())
        } finally { state.companion.stopLeading() }
    }
}