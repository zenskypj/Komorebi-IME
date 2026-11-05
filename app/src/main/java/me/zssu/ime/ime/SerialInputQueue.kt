package me.zssu.ime.ime

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** UI continuations run on [postUi]; native operations run in FIFO order on one worker. */
internal class SerialInputQueue(
    private val worker: ExecutorService,
    private val postUi: (Runnable) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private class StaleInput : RuntimeException()
    @Volatile private var epoch = 0L
    private var closed = false
    private var running = false
    private var draining = false
    private var awaitingNative = false
    private var activeEpoch = 0L
    private var repeatGeneration = 0L
    private var repeatSequence = 0L
    private var pendingRepeat: Long? = null
    private val pending = ArrayDeque<Pair<Long, suspend () -> Unit>>()

    fun submit(action: suspend () -> Unit) {
        if (closed) return
        pending.addLast(epoch to action)
        drain()
    }

    /** Backpressure only for generated hold ticks; physical key presses always use submit. */
    fun submitRepeat(action: suspend () -> Unit) {
        if (closed || pendingRepeat != null) return
        val generation = repeatGeneration
        val ticket = ++repeatSequence
        pendingRepeat = ticket
        submit {
            try { if (generation == repeatGeneration) action() }
            finally { if (pendingRepeat == ticket) pendingRepeat = null }
        }
    }

    fun cancelRepeats() { repeatGeneration++; pendingRepeat = null }

    /** In-flight JNI is allowed to finish, but its result can never reach a new editor. */
    // Rebase only a currently executing UI continuation (e.g. detecting an empty editor before
    // sending its new key). Never rebase an outstanding JNI operation or its queued UI result.
    fun invalidate(rebaseCurrent: Boolean = false) {
        epoch++
        if (rebaseCurrent && running && !awaitingNative) activeEpoch = epoch
        pending.clear()
        cancelRepeats()
    }

    private fun drain() {
        if (closed || running || draining) return
        draining = true
        try {
            while (!closed && !running) {
                val next = pending.pollFirst() ?: break
                if (next.first != epoch) continue
                running = true
                activeEpoch = next.first
                next.second.startCoroutine(object : Continuation<Unit> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Unit>) {
                        running = false
                        result.exceptionOrNull()?.takeUnless { it is StaleInput }?.let(onFailure)
                        drain()
                    }
                })
            }
        } finally { draining = false }
    }

    fun background(operation: () -> Unit) {
        if (closed) return
        val ticket = epoch
        worker.execute {
            if (ticket == epoch) runCatching(operation).exceptionOrNull()?.let { error ->
                postUi(Runnable { if (!closed && ticket == epoch) onFailure(error) })
            }
        }
    }

    suspend fun <T> native(operation: () -> T): T = suspendCoroutine { continuation ->
        check(running) { "Native access must belong to an input event" }
        val ticket = activeEpoch
        awaitingNative = true
        worker.execute {
            val result = runCatching {
                if (ticket != epoch) throw StaleInput()
                operation()
            }
            postUi(Runnable {
                awaitingNative = false
                if (closed || ticket != epoch) continuation.resumeWithException(StaleInput())
                else continuation.resumeWith(result)
            })
        }
    }

    fun close(cleanup: () -> Unit) {
        if (closed) return
        closed = true
        invalidate()
        worker.execute(cleanup)
        worker.shutdown()
    }
}
