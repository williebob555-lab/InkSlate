package com.inkslate

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import com.inkslate.core.DesignConfig
import com.inkslate.data.AppUpdates
import com.inkslate.data.DesignUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercises the real cache and Android preferences without contacting GitHub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DesignUpdatesTest {
    private val context: Context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName(): String = "com.inkslate"
    }
    private val cache: File get() = File(context.filesDir, "design-overrides.json")
    private val bundled = DesignConfig(revision = 1, summary = "Bundled")
    private var remote = encode(DesignConfig(revision = 2, summary = "New design"))

    private fun encode(design: DesignConfig) = DesignConfig.json.encodeToString(design)
    private fun updates(baseline: DesignConfig = bundled) =
        DesignUpdates(context, bundledDesign = baseline, fetchText = { remote })

    @Before fun prepare() {
        AtomicFile(cache).delete()
        AppUpdates.setTestChannel(context, true)
        DesignConfig.install(DesignConfig())
    }

    @Test fun `only a newer revision is offered`() = runBlocking {
        val updates = updates()
        for (revision in listOf(0, 1)) {
            remote = encode(DesignConfig(revision = revision))
            updates.check()
            assertNull(updates.pending)
        }
        remote = encode(DesignConfig(revision = 2))
        updates.check()
        assertEquals(2, updates.pending?.revision)
        assertEquals(1, updates.active.revision)
        assertFalse(cache.exists())
    }

    @Test fun `Later suppresses automatic offers but manual checking can offer again`() = runBlocking {
        val updates = updates()
        updates.check()
        updates.defer()
        updates.check()
        assertNull(updates.pending)
        updates.check(manual = true)
        assertEquals(2, updates.pending?.revision)
    }

    @Test fun `malformed remote data keeps installed config and cache`() = runBlocking {
        val updates = updates().apply { prepareReload = {} }
        updates.check()
        updates.applyPending()
        val previous = cache.readText()
        remote = """{"revision":3,"chrome":{"tabHeightDp":-1}}"""
        updates.check()
        assertEquals(2, updates.active.revision)
        assertEquals(2, DesignConfig.current.revision)
        assertEquals(previous, cache.readText())
        assertNull(updates.pending)
        assertNotNull(updates.error)
        assertFalse(updates.checking)
    }

    @Test fun `failed document flush prevents installation`() = runBlocking {
        val updates = updates().apply { prepareReload = { error("Working copy failed") } }
        updates.check()
        updates.applyPending()
        assertEquals(1, updates.active.revision)
        assertEquals(1, DesignConfig.current.revision)
        assertFalse(cache.exists())
        assertEquals(2, updates.pending?.revision)
        assertEquals("Working copy failed", updates.error)
        assertFalse(updates.applying)
    }

    @Test fun `a missing workspace callback also prevents installation`() = runBlocking {
        val updates = updates()
        updates.check()
        updates.applyPending()
        assertEquals(1, updates.active.revision)
        assertFalse(cache.exists())
        assertNotNull(updates.error)
    }

    @Test fun `successful apply flushes then persists and survives reopening`() = runBlocking {
        var flushes = 0
        val updates = updates().apply {
            prepareReload = {
                assertEquals(1, active.revision)
                assertFalse(cache.exists())
                flushes++
            }
        }
        updates.check()
        updates.applyPending()
        assertEquals(1, flushes)
        assertEquals(2, updates.active.revision)
        assertEquals(2, DesignConfig.parse(cache.readText()).revision)
        assertNull(updates.pending)
        assertNull(updates.error)
        assertEquals(2, updates().active.revision)
    }

    @Test fun `a newer bundled design supersedes the cached test design`() = runBlocking {
        val updates = updates().apply { prepareReload = {} }
        updates.check()
        updates.applyPending()
        val newBuild = updates(DesignConfig(revision = 3, summary = "New build"))
        assertEquals(3, newBuild.active.revision)
        assertEquals(3, DesignConfig.current.revision)
    }

    @Test fun `reset restores bundled design and defers the previous remote revision`() = runBlocking {
        var flushes = 0
        val updates = updates().apply { prepareReload = { flushes++ } }
        updates.check()
        updates.applyPending()
        updates.reset()
        assertEquals(2, flushes)
        assertEquals(1, updates.active.revision)
        assertEquals(1, DesignConfig.current.revision)
        assertFalse(cache.exists())
        updates.check()
        assertNull(updates.pending)
        updates.check(manual = true)
        assertEquals(2, updates.pending?.revision)
    }

    @Test fun `corrupt cache falls back to this builds design`() {
        cache.writeText("partial data")
        assertEquals(1, updates().active.revision)
    }

    @Test fun `AtomicFile legacy backup is recovered when the base is absent`() {
        // This is a real legacy crash point. Avoid an existing destination: Robolectric runs
        // File.renameTo on its host, and Windows cannot replace files as Android's Linux can.
        File(cache.path + ".bak").writeText(encode(DesignConfig(revision = 2)))
        assertEquals(2, updates().active.revision)
        assertEquals(2, DesignConfig.parse(cache.readText()).revision)
        assertFalse(File(cache.path + ".bak").exists())
    }

    @Test fun `AtomicFile discards an interrupted new write and retains the committed design`() {
        cache.writeText(encode(DesignConfig(revision = 2)))
        File(cache.path + ".new").writeText("interrupted")
        assertEquals(2, updates().active.revision)
        assertEquals(2, DesignConfig.parse(cache.readText()).revision)
        assertFalse(File(cache.path + ".new").exists())
    }

    @Test fun `cancellation propagates and clears busy state`() = runBlocking {
        val updates = DesignUpdates(context, bundledDesign = bundled,
            fetchText = { throw CancellationException("Leaving app") })
        var cancelled = false
        try { updates.check() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(updates.checking)
        assertNull(updates.error)
        assertEquals(1, updates.active.revision)
    }
}
