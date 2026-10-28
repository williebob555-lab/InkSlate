package com.inksheets.desktop

import com.inksheets.ui.ReadingQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReadingQueueTest {
    @Test fun `foreground reading takes over at page boundary then background resumes without duplicate pages`() {
        val events = Collections.synchronizedList(ArrayList<String>())
        val firstPage = CountDownLatch(1)
        val finishPage = CountDownLatch(1)
        val page = AtomicInteger()
        ReadingQueue<Int>().use { queue ->
            val background = queue.submit("piece", ReadingQueue.CURRENT_PIECE) { control ->
                while (page.get() < 3) {
                    if (control.shouldYield()) return@submit page.get()
                    val index = page.incrementAndGet()
                    events += "background-$index"
                    if (index == 1) { firstPage.countDown(); assertTrue(finishPage.await(5, TimeUnit.SECONDS)) }
                }
                page.get()
            }
            assertTrue(firstPage.await(5, TimeUnit.SECONDS))
            val foreground = queue.submit("chosen", ReadingQueue.FOREGROUND) { events += "foreground"; 9 }
            finishPage.countDown()
            assertEquals(9, foreground.get(5, TimeUnit.SECONDS))
            assertEquals(3, background.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("background-1", "foreground", "background-2", "background-3"), events)
        }
    }

    @Test fun `writing or playback pause background while explicit request still runs and duplicate requests share work`() {
        val idle = AtomicBoolean(false)
        val calls = AtomicInteger()
        ReadingQueue<Int> { idle.get() }.use { queue ->
            val background = queue.submit("library", ReadingQueue.LIBRARY) { calls.incrementAndGet() }
            val duplicate = queue.submit("library", ReadingQueue.LIBRARY) { error("Duplicate work must not run") }
            assertSame(background, duplicate)
            assertEquals(4, queue.submit("explicit", ReadingQueue.FOREGROUND) { 4 }.get(5, TimeUnit.SECONDS))
            assertFalse(background.isDone)
            assertEquals(0, calls.get())
            idle.set(true); queue.wake()
            assertEquals(1, background.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `current piece outranks library and cancelled queued files never run`() {
        val idle = AtomicBoolean(false)
        val events = Collections.synchronizedList(ArrayList<String>())
        ReadingQueue<Int> { idle.get() }.use { queue ->
            val other = queue.submit("other", ReadingQueue.LIBRARY) { events += "library"; 1 }
            val removed = queue.submit("deleted", ReadingQueue.CURRENT_PIECE) { error("Cancelled library must not run") }
            val current = queue.submit("current", ReadingQueue.CURRENT_PIECE) { events += "current"; 2 }
            queue.cancelWhere { it == "deleted" }
            idle.set(true); queue.wake()
            assertEquals(2, current.get(5, TimeUnit.SECONDS))
            assertEquals(1, other.get(5, TimeUnit.SECONDS))
            assertTrue(removed.isCancelled)
            assertEquals(listOf("current", "library"), events)
        }
    }

    @Test fun `deferred idle passes do not spin or starve new foreground reads`() {
        val calls = AtomicInteger()
        ReadingQueue<Int>().use { queue ->
            val deep = queue.submit("deeper", ReadingQueue.DEEPER) { control ->
                if (calls.incrementAndGet() == 1) { control.defer(150); return@submit 0 }
                1
            }
            assertEquals(2, queue.submit("foreground", ReadingQueue.FOREGROUND) { 2 }.get(5, TimeUnit.SECONDS))
            assertEquals(1, deep.get(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        }
    }

    @Test fun `cancelling active background work finishes current page but never starts its next page`() {
        val page = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextPage = AtomicBoolean(false)
        ReadingQueue<Int>().use { queue ->
            val background = queue.submit("background", ReadingQueue.CURRENT_PIECE) { control ->
                page.countDown(); check(release.await(5, TimeUnit.SECONDS))
                if (control.shouldYield()) return@submit 1
                nextPage.set(true); 2
            }
            assertTrue(page.await(5, TimeUnit.SECONDS))
            queue.cancelWhere { it == "background" }
            val foreground = queue.submit("foreground", ReadingQueue.FOREGROUND) { 3 }
            release.countDown()
            assertEquals(3, foreground.get(5, TimeUnit.SECONDS))
            assertTrue(background.isCancelled)
            assertFalse(nextPage.get())
        }
    }
}
