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
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.internal.AtomicInt

/**
 * Something that can be cancelled by the [FastJob] it is suspended on — in practice a
 * [FastCancellableContinuation]. Continuations implement this directly rather than being wrapped in
 * a handler node, so an awaiting coroutine costs exactly one allocation.
 */
internal interface FastCancellable {
    /**
     * Retires this suspension: the awaiter is dequeued and the continuation is marked so that
     * nothing can ever resume it. No exception is created and none is thrown into the coroutine —
     * the body simply stops where it is parked.
     *
     * Returns `false` if there was nothing to retire because the suspension had already been
     * resumed, in which case the body is still going to run and must not be treated as finished.
     */
    fun retire(): Boolean
}

/** Handle for removing a registered [FastCancellable] or completion handler from a [FastJob]. */
internal fun interface FastDisposableHandle {
    fun dispose()
}

/**
 * Cancellation and structured-concurrency node for fast coroutines.
 *
 * Everything except [state] is owner-thread confined: children, awaiters, joiners and causes are
 * plain fields, mutated only on [FastCoroutineHost]'s owner thread. [cancel] is safe to call from
 * any thread and forwards itself to the owner thread, which keeps the single-writer invariant
 * without any per-operation atomics. [state] is atomic purely so that foreign threads can cheaply
 * read [isActive]/[isCompleted] without a hand-off.
 *
 * Deliberately *not* a `kotlinx.coroutines.Job`: interop is explicit, see
 * `KotlinxCoroutinesBridge`.
 */
@OptIn(InternalComposeApi::class)
internal class FastJob(
    internal val host: FastCoroutineHost,
    private val parent: FastJob?,
    /**
     * When `false`, a non-cancellation failure of this job is not reported to the parent or to the
     * host's unhandled-exception handler, because whoever created the job delivers the failure
     * itself (e.g. `launchWithResult` hands it to a kotlinx.coroutines caller).
     */
    private val reportsFailureToParent: Boolean = true,
) : FastCancellableJob, FastTask {

    private val state = AtomicInt(ACTIVE)

    /** `true` once [cancel] has been requested but not yet applied on the owner thread. */
    private var cancelRequested = false

    /** `true` once this job has been cancelled. Owner thread only. */
    private var isCancelling = false

    /** Non-cancellation failure of this job or one of its children. Owner thread only. */
    private var failureCause: Throwable? = null

    /** `true` once a coroutine body has been started for this job and has not yet completed. */
    private var isBodyRunning = false

    /** `true` once a body was started at all; scope jobs without a body finish on cancellation. */
    private var hasBody = false

    // Single-slot + overflow list: a suspended coroutine body has exactly one awaiting suspension
    // point in the common case, so the list is usually never allocated.
    private var awaiter: FastCancellable? = null
    private var moreAwaiters: MutableObjectList<FastCancellable>? = null

    private var children: MutableObjectList<FastJob>? = null
    private var activeChildren = 0

    private var joiners: MutableObjectList<FastCancellableContinuation<Unit>>? = null
    private var completionHandlers: MutableObjectList<(Throwable?) -> Unit>? = null

    // Scoped cleanups, single-slot + overflow so the common one-resource case allocates no list.
    // The slot holds the first registration, so LIFO order is the list reversed, then the slot.
    private var cleanup: (() -> Unit)? = null
    private var moreCleanups: MutableObjectList<() -> Unit>? = null

    init {
        parent?.attachChild(this)
    }

    override val isActive: Boolean
        get() = state.get() == ACTIVE

    override val isCancelled: Boolean
        get() = state.get().let { it == CANCELLING || it == CANCELLED }

    override val isCompleted: Boolean
        get() = state.get() >= CANCELLED

    /**
     * The failure this job or one of its children ended with, or `null` if it completed normally or
     * was cancelled. Cancellation has no cause: it is not a failure and creates no exception.
     */
    val completionCause: Throwable?
        get() = failureCause

    /**
     * Cancels this job and all of its children. Safe to call from any thread; when called from a
     * foreign thread the cancellation is forwarded to the owner thread, so it takes effect
     * asynchronously. When called on the owner thread during a resume batch (e.g. from inside a
     * frame callback), it is deferred until that batch drains, and is otherwise synchronous.
     */
    override fun cancel() {
        if (state.get() >= CANCELLED) return
        // `this` is the hand-off task, so cancelling allocates nothing at all.
        cancelRequested = true
        host.runOnOwnerThread(this)
    }

    /** Hand-off callback for [cancel]; see [FastCoroutineHost.runOnOwnerThread]. */
    override fun execute() {
        cancelRequested = false
        cancelOnOwnerThread()
    }

    internal fun cancelOnOwnerThread() {
        if (state.get() != ACTIVE) return
        isCancelling = true
        state.set(CANCELLING)

        // Children first: they unwind into completeWith, decrementing activeChildren.
        val children = children
        if (children != null) {
            // Iterate over a snapshot: cancelling a child mutates this list via detachChild.
            var i = children.size - 1
            while (i >= 0) {
                if (i < children.size) children[i].cancelOnOwnerThread()
                i--
            }
        }

        // Then this job's own suspension points. Retiring one dequeues its awaiter and guarantees
        // nothing will resume it: the body stops where it is parked and its coroutine frames become
        // garbage. Nothing is thrown, so there is no unwinding to pay for.
        val awaiter = awaiter
        this.awaiter = null
        val more = moreAwaiters
        moreAwaiters = null
        var retired = awaiter?.retire() == true
        if (more != null) {
            for (i in 0 until more.size) {
                if (more[i].retire()) retired = true
            }
            more.clear()
        }
        // Only a body that was actually parked has been retired. A body currently on the stack —
        // one
        // cancelling itself, or holding an in-flight resume — keeps running until its next
        // suspension, which addAwaiter refuses, retiring it there instead.
        if (retired) isBodyRunning = false

        tryFinish()
    }

    /**
     * Registers a suspension point with this job, returning `false` if the job is already
     * cancelled.
     *
     * A refused registration is how a body that was *running* when its job was cancelled comes to a
     * stop: the suspension parks and is never resumed, so the body ends there without an exception.
     * The caller reports that by treating the suspension as retired.
     *
     * No handle is returned: continuations remove themselves with [removeAwaiter] when they resume,
     * which keeps a suspension at exactly one allocation (the continuation itself).
     */
    fun addAwaiter(cancellable: FastCancellable): Boolean {
        if (isCancelling || state.get() != ACTIVE) return false
        if (awaiter == null) {
            awaiter = cancellable
        } else {
            val list =
                moreAwaiters ?: mutableObjectListOf<FastCancellable>().also { moreAwaiters = it }
            list.add(cancellable)
        }
        return true
    }

    internal fun removeAwaiter(cancellable: FastCancellable) {
        if (awaiter === cancellable) {
            awaiter = null
            // Promote an overflow awaiter into the single slot to keep the common path
            // allocation-free.
            val more = moreAwaiters
            if (more != null && more.isNotEmpty()) {
                awaiter = more.removeAt(more.size - 1)
            }
            return
        }
        moreAwaiters?.remove(cancellable)
    }

    /**
     * Registers [handler] to run when this job completes, with the completion cause or `null` for
     * normal completion. Runs immediately if the job has already completed. Owner thread only.
     */
    fun invokeOnCompletion(handler: (Throwable?) -> Unit): FastDisposableHandle {
        if (isCompleted) {
            handler(completionCause)
            return DisposedHandle
        }
        val handlers =
            completionHandlers
                ?: mutableObjectListOf<(Throwable?) -> Unit>().also { completionHandlers = it }
        handlers.add(handler)
        return FastDisposableHandle { completionHandlers?.remove(handler) }
    }

    /**
     * Registers [action] to run exactly once when this job completes — normally, by cancellation,
     * or by failure — regardless of [FastCancellationMode]. Cleanups run LIFO on the owner thread.
     *
     * This is the mechanism that makes [FastCancellationMode.ScopedCleanup] usable: it holds the
     * release side of a resource on the job instead of in the body's `finally`, so it does not
     * depend on the body being resumed. Dispose the returned handle to unregister (e.g. when the
     * resource is released early).
     *
     * Runs [action] immediately if the job has already completed, so a registration that loses a
     * race with cancellation still releases its resource.
     */
    fun addCleanup(action: () -> Unit): FastDisposableHandle {
        if (isCompleted) {
            runCleanup(action)
            return DisposedHandle
        }
        if (cleanup == null) {
            cleanup = action
        } else {
            val list = moreCleanups ?: mutableObjectListOf<() -> Unit>().also { moreCleanups = it }
            list.add(action)
        }
        return FastDisposableHandle { removeCleanup(action) }
    }

    internal fun removeCleanup(action: () -> Unit) {
        if (cleanup === action) {
            cleanup = null
            val more = moreCleanups
            if (more != null && more.isNotEmpty()) {
                // Keep the slot occupied by the oldest remaining registration to preserve LIFO
                // order.
                cleanup = more.removeAt(0)
            }
            return
        }
        moreCleanups?.remove(action)
    }

    private fun runCleanups() {
        val more = moreCleanups
        moreCleanups = null
        if (more != null) {
            for (i in more.size - 1 downTo 0) runCleanup(more[i])
            more.clear()
        }
        val cleanup = cleanup
        this.cleanup = null
        if (cleanup != null) runCleanup(cleanup)
    }

    private fun runCleanup(action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            // One failing cleanup must not strand the others: report it and keep going. A cleanup
            // is
            // the last chance to release a subscription, so this is not the place to unwind.
            host.onUnhandledException(e)
        }
    }

    internal fun bodyStarted() {
        hasBody = true
        isBodyRunning = true
    }

    /**
     * Called by the coroutine body's root continuation when the body returns. Owner thread only.
     */
    internal fun completeWith(result: Result<Any?>) {
        isBodyRunning = false
        // A failure here is a genuine one — the body threw. Cancellation never resumes a body, so
        // it
        // can never arrive this way.
        val exception = result.exceptionOrNull()
        if (exception != null && failureCause == null) failureCause = exception
        tryFinish()
    }

    private fun attachChild(child: FastJob) {
        val children = children ?: mutableObjectListOf<FastJob>().also { this.children = it }
        children.add(child)
        activeChildren++
        // A child attached to an already-cancelled parent is cancelled right away.
        if (isCancelling) child.cancelOnOwnerThread()
    }

    private fun detachChild(child: FastJob) {
        children?.remove(child)
        activeChildren--
        tryFinish()
    }

    private fun tryFinish() {
        if (state.get() >= CANCELLED) return
        if (isBodyRunning) return
        if (activeChildren > 0) return
        if (state.get() == ACTIVE && !hasBody && !isCancelling && failureCause == null) {
            // An active scope job with nothing running stays active: more work may be launched
            // later.
            return
        }
        finish()
    }

    private fun finish() {
        val failure = failureCause
        state.set(if (failure != null || isCancelling) CANCELLED else COMPLETED)

        // Scoped cleanups first: they release what the body acquired, and completion handlers and
        // joiners may observe that release.
        runCleanups()

        val handlers = completionHandlers
        completionHandlers = null
        if (handlers != null) {
            for (i in 0 until handlers.size) handlers[i](completionCause)
        }

        val joiners = joiners
        this.joiners = null
        if (joiners != null) {
            for (i in 0 until joiners.size) joiners[i].resume(Unit)
            joiners.clear()
        }

        if (failure != null && reportsFailureToParent) {
            // Report the failure exactly once, at the root of the fast job tree.
            val parent = parent
            if (parent != null) {
                parent.childFailed(failure)
            } else {
                host.onUnhandledException(failure)
            }
        }
        parent?.detachChild(this)
    }

    private fun childFailed(cause: Throwable) {
        if (failureCause == null) failureCause = cause
        cancelOnOwnerThread()
    }

    /**
     * Suspends the coroutine identified by [callerJob] until this job completes. Does not rethrow
     * this job's failure. Reached through [FastRestrictedScope.join].
     */
    internal suspend fun joinIn(callerHost: FastCoroutineHost, callerJob: FastJob) {
        if (isCompleted) return
        fastSuspendCancellableIn<Unit>(callerHost, callerJob) { co ->
            if (isCompleted) {
                co.resume(Unit)
            } else {
                val joiners =
                    joiners
                        ?: mutableObjectListOf<FastCancellableContinuation<Unit>>().also {
                            joiners = it
                        }
                joiners.add(co)
                co.invokeOnCancellation { this.joiners?.remove(co) }
            }
        }
    }

    override fun toString(): String {
        val stateName =
            when (state.get()) {
                ACTIVE -> "Active"
                CANCELLING -> "Cancelling"
                CANCELLED -> "Cancelled"
                else -> "Completed"
            }
        return "FastJob($stateName, children=$activeChildren)"
    }

    internal companion object {
        const val ACTIVE = 0
        const val CANCELLING = 1
        const val CANCELLED = 2
        const val COMPLETED = 3

        val DisposedHandle = FastDisposableHandle {}
    }
}
