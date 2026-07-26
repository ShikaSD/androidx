/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.runtime.internal.coroutines

import androidx.collection.MutableObjectList
import androidx.collection.mutableObjectListOf
import androidx.compose.runtime.checkPrecondition
import androidx.compose.runtime.internal.currentThreadId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A task to run on a [FastCoroutineHost]'s owner thread.
 *
 * This is deliberately not `kotlinx.coroutines.Runnable`: the fast coroutine implementation only
 * references kotlinx.coroutines types in `KotlinxCoroutinesBridge`.
 */
internal fun interface FastTask {
    fun execute()
}

/**
 * The only mechanism the fast coroutine implementation has for getting back onto its owner thread
 * once work has escaped it, e.g. because a [FastJob] was cancelled from another thread or because a
 * bridged kotlinx.coroutines coroutine completed on a different dispatcher.
 *
 * Implementations must be safe to call from any thread. On Android this would be backed by the
 * `AndroidUiDispatcher` (handler + choreographer); see `KotlinxHandoffDispatcher` for a generic
 * implementation on top of a `CoroutineDispatcher`, and [FastEventLoop] for tests and non-UI hosts.
 */
internal interface FastHandoffDispatcher {
    fun schedule(task: FastTask)
}

/**
 * Owner-thread-confined resume trampoline.
 *
 * Resuming a fast continuation runs the coroutine body on the current stack (that is the whole
 * point: no dispatch, no atomics, no wrapper allocation). Two situations still require queueing:
 * 1. Reentrancy: a resume triggered while a batch of resumes is already being delivered must not
 *    run nested inside it, otherwise the ordering guarantee that all `withFrameNanos` callbacks
 *    observe the same frame before any body continues would be lost, and stacks could nest
 *    arbitrarily deep.
 * 2. Cross-thread hand-off, which arrives via [FastCoroutineHost.runOnOwnerThread].
 *
 * All state here is owner-thread confined, so no synchronization is used.
 */
internal class FastTrampoline(private val onUnhandledException: (Throwable) -> Unit) {
    internal var isDispatching = false
    internal var pending: MutableObjectList<FastTask>? = null
    internal var cursor = 0

    /** Runs [task] now, or queues it if a dispatch is already in progress on this thread. */
    fun execute(task: FastTask) {
        if (isDispatching) {
            enqueue(task)
            return
        }
        isDispatching = true
        try {
            runCatchingUnhandled(task)
            drain()
        } finally {
            isDispatching = false
        }
    }

    /**
     * Runs [block], deferring any resume that happens inside it until [block] returns. Used by
     * [FastFrameClock.sendFrame] so that every awaiter's `onFrame` callback runs before any
     * awaiting coroutine body resumes, matching the ordering that a `BroadcastFrameClock` plus a
     * trampolining UI dispatcher produces today.
     */
    inline fun <R> withBatchedResumes(block: () -> R): R {
        if (isDispatching) return block()
        isDispatching = true
        try {
            val result = block()
            drain()
            return result
        } finally {
            isDispatching = false
        }
    }

    fun enqueue(task: FastTask) {
        val queue = pending ?: mutableObjectListOf<FastTask>().also { pending = it }
        queue.add(task)
    }

    /** Drains queued resumes in FIFO order, including any queued while draining. */
    fun drain() {
        val queue = pending ?: return
        while (cursor < queue.size) {
            val task = queue[cursor]
            // Release the reference before running: the task may append to the queue.
            queue[cursor] = NoOpTask
            cursor++
            runCatchingUnhandled(task)
        }
        queue.clear()
        cursor = 0
    }

    fun runCatchingUnhandled(task: FastTask) {
        try {
            task.execute()
        } catch (e: Throwable) {
            // A resume must never fail the dispatch loop it happens to run on. Coroutine bodies
            // report their own failures through FastJob; anything reaching here is a bug in a
            // cancellation handler or in host code.
            onUnhandledException(e)
        }
    }

    private companion object {
        val NoOpTask = FastTask {}
    }
}

/**
 * Owner-thread-approximate counters used by tests and benchmarks to assert that the fast path is
 * actually taken and that kotlinx.coroutines is only reached where expected.
 *
 * Counters are plain fields updated on the owner thread; cross-thread counters may lose updates
 * under contention. They exist for observability of a prototype, not for correctness.
 */
internal class FastCoroutineStats {
    var fastSuspensions = 0
    var syncCompletions = 0
    var fallbackSuspensions = 0
    var crossThreadHandoffs = 0
    var promotions = 0
    var kotlinxBridgeCrossings = 0

    fun reset() {
        fastSuspensions = 0
        syncCompletions = 0
        fallbackSuspensions = 0
        crossThreadHandoffs = 0
        promotions = 0
        kotlinxBridgeCrossings = 0
    }

    override fun toString(): String =
        "FastCoroutineStats(fast=$fastSuspensions, sync=$syncCompletions, " +
            "fallback=$fallbackSuspensions, handoffs=$crossThreadHandoffs, " +
            "promotions=$promotions, bridge=$kotlinxBridgeCrossings)"
}

/**
 * A single-threaded coroutine world.
 *
 * A host owns the thread it was created on (typically the UI thread) and provides the invariant the
 * whole implementation is built on: **all coroutine state is mutated only on the owner thread.**
 * That is what makes plain fields instead of atomics, and direct resumes instead of dispatches,
 * sound. Work originating on another thread is not made thread-safe with locks in the hot path; it
 * is forwarded to the owner thread by [runOnOwnerThread], so there is exactly one writer.
 *
 * Multithreading is therefore not an error, just a slower path: resumes and cancellations from
 * other threads are forwarded here, and objects that are genuinely shared are promoted per-object
 * by [ThreadAffinity] to synchronize the state foreign threads can reach — for [FastFrameClock]
 * that is the `BroadcastFrameClock` serving kotlinx.coroutines awaiters.
 *
 * This is a prototype: it is internal, unsupported, and intentionally covers only the suspension
 * primitives on Compose's frame-driven hot paths.
 */
internal class FastCoroutineHost(
    private val handoff: FastHandoffDispatcher,
    /**
     * Context used when bridging out to kotlinx.coroutines; should carry the dispatcher that runs
     * on this host's owner thread (e.g. `AndroidUiDispatcher.Main`), used when handing results back
     * across the kotlinx.coroutines boundary.
     */
    val kotlinxContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * Timer used by [fastDelay]; `null` if this host does not support delays. Production hosts pass
     * a scheduler backed by the UI thread's timer, tests a [FastVirtualTimeScheduler].
     */
    val scheduler: FastScheduler? = null,
    val onUnhandledException: (Throwable) -> Unit = { throw it },
) {
    val ownerThreadId: Long = currentThreadId()

    val stats: FastCoroutineStats = FastCoroutineStats()

    internal val trampoline: FastTrampoline = FastTrampoline(onUnhandledException)

    inline val isOnOwnerThread: Boolean
        get() = currentThreadId() == ownerThreadId

    /**
     * Runs [task] on the owner thread: inline (through the trampoline) when already there,
     * otherwise by forwarding it to [handoff]. Safe to call from any thread.
     */
    fun runOnOwnerThread(task: FastTask) {
        if (isOnOwnerThread) {
            trampoline.execute(task)
        } else {
            stats.crossThreadHandoffs++
            handoff.schedule(task)
        }
    }

    /** Creates a root scope for this host. Must be called on the owner thread. */
    fun createScope(): FastCoroutineScope {
        checkOwnerThread()
        return FastCoroutineScope(this, FastJob(this, parent = null))
    }

    fun checkOwnerThread() {
        checkPrecondition(isOnOwnerThread) {
            "FastCoroutineHost may only be used from its owner thread (id=$ownerThreadId)"
        }
    }
}
