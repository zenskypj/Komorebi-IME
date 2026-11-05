package me.zssu.ime.ime

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SerialInputQueueTest {
    private class ManualWorker : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() = tasks.removeFirst().run()
    }
    private class Fixture {
        val worker = ManualWorker()
        val ui = ArrayDeque<Runnable>()
        val failures = mutableListOf<Throwable>()
        val queue = SerialInputQueue(worker, { ui.addLast(it) }, { failures.add(it) })
        fun finishNative() { worker.runNext(); ui.removeFirst().run() }
    }

    @Test fun charactersDeletionConversionAndCommitRemainOrdered() {
        val f = Fixture()
        var nativeText = ""
        val rendered = mutableListOf<String>()
        for (command in listOf("a", "b", "delete", "c", "convert", "commit")) {
            f.queue.submit {
                val result = f.queue.native {
                    nativeText = when (command) {
                        "delete" -> nativeText.dropLast(1)
                        "convert" -> nativeText.uppercase()
                        "commit" -> "[$nativeText]"
                        else -> nativeText + command
                    }
                    nativeText
                }
                rendered.add(result)
            }
        }
        assertTrue(rendered.isEmpty())
        repeat(6) { f.finishNative() }
        assertEquals(listOf("a", "ab", "a", "ac", "AC", "[AC]"), rendered)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun switchingEditorsDiscardsOldResultAndPendingKeysBeforeResetAndNewInput() {
        val f = Fixture()
        var nativeText = ""
        val applied = mutableListOf<String>()
        f.queue.submit { val result = f.queue.native { nativeText += "old"; nativeText }; applied.add(result) }
        f.worker.runNext() // Native has finished; its old UI callback is still queued.
        f.queue.submit { f.queue.native { nativeText += "discarded" } }
        f.queue.invalidate()
        f.queue.submit { val result = f.queue.native { nativeText = "new"; nativeText }; applied.add(result) }
        f.ui.removeFirst().run()
        f.finishNative()
        assertEquals(listOf("new"), applied)
        assertEquals("new", nativeText)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun invalidationBeforeWorkerStartsSkipsTheOldNativeOperation() {
        val f = Fixture()
        var called = false
        f.queue.submit { f.queue.native { called = true } }
        f.queue.invalidate()
        f.finishNative()
        assertFalse(called)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun externalClearingDetectedInsideCurrentEventKeepsItsNewKey() {
        val f = Fixture()
        var applied = ""
        f.queue.submit {
            f.queue.invalidate(rebaseCurrent = true)
            applied = f.queue.native { "new key" }
        }
        f.finishNative()
        assertEquals("new key", applied)
    }

    @Test fun rebasingExternalClearCannotReviveAnAlreadyOutstandingResult() {
        val f = Fixture()
        var applied = false
        f.queue.submit { f.queue.native { Unit }; applied = true }
        f.queue.invalidate(rebaseCurrent = true)
        f.finishNative()
        assertFalse(applied)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun staleBackgroundLearningIsNotWrittenIntoANewEditorEpoch() {
        val f = Fixture()
        var learned = false
        f.queue.background { learned = true }
        f.queue.invalidate()
        f.worker.runNext()
        assertFalse(learned)
    }

    @Test fun releaseCancelsQueuedAutoRepeatsButKeepsOrdinaryKeys() {
        val f = Fixture()
        var deleted = 0
        var typed = 0
        f.queue.submit { f.queue.native { Unit } }
        repeat(100) { f.queue.submitRepeat { f.queue.native { deleted++ } } }
        f.queue.submit { typed++ }
        f.queue.cancelRepeats()
        f.finishNative()
        assertEquals(0, deleted)
        assertEquals(1, typed)
        assertTrue(f.worker.tasks.isEmpty())
    }

    @Test fun autoRepeatsHaveAtMostOneOutstandingNativeOperation() {
        val f = Fixture()
        var deleted = 0
        repeat(100) { f.queue.submitRepeat { f.queue.native { deleted++ } } }
        assertEquals(1, f.worker.tasks.size)
        f.queue.cancelRepeats()
        f.finishNative() // The already accepted operation still completes exactly once.
        assertEquals(1, deleted)
        assertTrue(f.worker.tasks.isEmpty())
    }

    @Test fun oldRepeatCompletionCannotClearANewHoldsBackpressure() {
        val f = Fixture()
        var moved = 0
        f.queue.submitRepeat { f.queue.native { moved++ } }
        f.queue.cancelRepeats()
        f.queue.submitRepeat { f.queue.native { moved++ } }
        f.finishNative()
        f.queue.submitRepeat { f.queue.native { moved++ } }
        f.finishNative()
        assertEquals(2, moved)
        assertTrue(f.worker.tasks.isEmpty())
    }

    @Test fun nativeFailureDoesNotDeadlockTheQueue() {
        val f = Fixture()
        var next = false
        f.queue.submit { f.queue.native<Unit> { error("native failed") } }
        f.queue.submit { next = true }
        f.finishNative()
        assertTrue(next)
        assertEquals("native failed", f.failures.single().message)
    }

    @Test fun manyQueuedUiActionsDoNotRecurseThroughTheStack() {
        val f = Fixture()
        var count = 0
        f.queue.submit { f.queue.native { Unit } }
        repeat(10000) { f.queue.submit { count++ } }
        f.finishNative()
        assertEquals(10000, count)
    }

    @Test fun destructionDropsResultsAndRunsCleanupOnWorker() {
        val f = Fixture()
        var applied = false
        var cleaned = false
        f.queue.submit { f.queue.native { Unit }; applied = true }
        f.queue.close { cleaned = true }
        f.worker.runNext()
        assertFalse(cleaned)
        f.worker.runNext()
        f.ui.removeFirst().run()
        assertTrue(cleaned)
        assertFalse(applied)
        assertTrue(f.worker.isShutdown)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun blockedNativeWorkDoesNotBlockUiOrResumeOnWorker() {
        val ui = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uiThread = Thread.currentThread()
        var resumed: Thread? = null
        val failures = mutableListOf<Throwable>()
        val queue = SerialInputQueue(worker, { ui.add(it) }, { failures.add(it) })
        try {
            queue.submit {
                queue.native {
                    assertNotEquals(uiThread, Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                resumed = Thread.currentThread()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(resumed) // Main thread is free while the native worker is blocked.
            release.countDown()
            requireNotNull(ui.poll(5, TimeUnit.SECONDS)).run()
            assertEquals(uiThread, resumed)
            assertTrue(failures.isEmpty())
        } finally { release.countDown(); queue.close {}; worker.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
