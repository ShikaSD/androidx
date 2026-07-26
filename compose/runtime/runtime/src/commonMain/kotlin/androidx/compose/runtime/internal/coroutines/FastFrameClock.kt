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
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * A [MonotonicFrameClock] with a single-threaded fast path and a kotlinx.coroutines fallback.
 *
 * This is the primitive the prototype is really about: `withFrameNanos` is the highest-frequency
 * suspension in Compose. Every running animation, every `animateTo`, every fling and every
 * `InfiniteTransition` awaits it once per frame, so at 120Hz with a dozen animations it is the
 * suspension whose per-call cost is most visible.
 *
 * The two paths:
 * - **Fast path**, [awaitFrameIn], used by fast coroutines through their [FastRestrictedScope]: the
 *   awaiter is a single object that is both the continuation and the queue entry, and [sendFrame]
 *   resumes it inline. No `CancellableContinuationImpl`, no handler node, no atomic awaiter count,
 *   no dispatcher hop between the frame callback and the animation body, and no context lookup.
 *   Fast coroutines only ever run on the owner thread, so this list needs no synchronization.
 * - **Interop path**, the [withFrameNanos] override, which only kotlinx.coroutines callers can
 *   reach: delegated to a [BroadcastFrameClock], i.e. exactly today's behaviour, including
 *   cancellation through the caller's own `Job`. Both queues are served by the same [sendFrame], so
 *   the two kinds of awaiter see the same frame time and can coexist indefinitely.
 *
 * Note that a fast coroutine is never handed to the `BroadcastFrameClock`: it has no `Job`, so its
 * awaiter would no longer be cancellable by [FastJob]. Multithreading is absorbed where it can be
 * done safely — foreign awaiters go to the thread-safe fallback queue, and foreign resumes and
 * cancellations are forwarded to the owner thread by [FastCoroutineHost.runOnOwnerThread].
 *
 * Ordering matches the current implementation: all awaiters' `onFrame` callbacks run before any
 * awaiting coroutine body resumes, because the resumes are batched through [FastTrampoline].
 */
internal class FastFrameClock(
    private val host: FastCoroutineHost,
    /** Invoked when the awaiter count goes from 0 to 1, to request a frame. */
    private val onNewAwaiters: (() -> Unit)? = null,
) : MonotonicFrameClock {

    private val affinity = ThreadAffinity(onPromoted = { host.stats.promotions++ })

    /** Fast-path awaiters. Owner-thread confined; rotated on dispatch like `AwaiterQueue` does. */
    private var awaiters = mutableObjectListOf<FrameEntry>()
    private var spareAwaiters = mutableObjectListOf<FrameEntry>()

    /**
     * Fallback clock, created only if a kotlinx.coroutines coroutine awaits this clock or this
     * clock is promoted to shared. A purely single-threaded app never allocates it.
     */
    private var fallbackClock: BroadcastFrameClock? = null

    private var failureCause: Throwable? = null

    /**
     * Number of entries in [awaiters] that are still live. Cancelled entries are left in the list —
     * removing one would be O(n) and could mutate the list mid-dispatch — so the count, not the
     * list size, is what says whether a frame is still needed.
     */
    private var liveAwaiters = 0

    val hasAwaiters: Boolean
        get() = liveAwaiters > 0 || currentFallbackClock()?.hasAwaiters == true

    /** True once this clock has been touched from a thread other than its owner. */
    val isShared: Boolean
        get() = affinity.isShared

    /** `true` if [candidate] is the host whose fast coroutines this clock can serve directly. */
    fun ownedBy(candidate: FastCoroutineHost): Boolean = candidate === host

    /**
     * The `MonotonicFrameClock` entry point, which only kotlinx.coroutines callers can reach: a
     * `LaunchedEffect`, a `rememberCoroutineScope().launch`, or any coroutine on another thread.
     * Fast coroutines await through [awaitFrameIn] on their [FastRestrictedScope] instead, so this
     * is purely the interop path and is served by a [BroadcastFrameClock] — today's behaviour,
     * including cancellation through the caller's own `Job`. Both queues are served by the same
     * [sendFrame], so the two kinds of awaiter see the same frame.
     */
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        if (!affinity.isOwnerThread) affinity.promote()
        host.stats.fallbackSuspensions++
        return fallbackClock().withFrameNanos(onFrame)
    }

    /**
     * Awaits one frame for a caller that already knows its [job] — see [FastRestrictedScope].
     * Identical behaviour to the context-based path, without the two context lookups per frame.
     */
    internal suspend fun <R> awaitFrameIn(job: FastJob, onFrame: (frameTimeNanos: Long) -> R): R =
        suspendCoroutineUninterceptedOrReturn { uCont ->
            val entry = FrameAwaiterContinuation(uCont, host, job, onFrame, this)
            entry.attachToJob()
            register(entry)
            entry.getResult()
        }

    /**
     * Enqueues [entry] for the next frame.
     *
     * There is no owner-thread assertion here: a fast coroutine body only ever runs on the owner
     * thread, and that is checked once when it is launched. Re-deriving it on every await was one
     * of the largest single costs the method trace showed on the steady-state frame path.
     */
    private fun register(entry: FrameEntry) {
        val cause = failureCause
        if (cause != null) {
            entry.failWith(cause)
            return
        }
        sweepDeadEntriesIfIdle()
        awaiters.add(entry)
        liveAwaiters++
        if (liveAwaiters == 1) requestFrame()
    }

    /** Called by an entry that dequeued itself because its suspension was cancelled. */
    internal fun onEntryCancelled() {
        liveAwaiters--
    }

    private fun sweepDeadEntriesIfIdle() {
        if (liveAwaiters == 0 && awaiters.isNotEmpty()) {
            // Every remaining entry was cancelled without a frame arriving to sweep it up.
            awaiters.clear()
        }
    }

    private fun requestFrame() {
        val callback = onNewAwaiters ?: return
        try {
            callback()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    /**
     * Returns the fallback clock if one has been created, reading the field under the affinity lock
     * once this clock is shared so that a clock created by a foreign thread is safely published.
     */
    private fun currentFallbackClock(): BroadcastFrameClock? =
        // One volatile read on the common path. Only a clock that has actually been touched from
        // another thread needs the lock — and the affinity probe that reads the current thread id.
        if (!affinity.isShared) fallbackClock else affinity.withAccess { fallbackClock }

    private fun fallbackClock(): BroadcastFrameClock =
        affinity.withAccess {
            fallbackClock
                ?: BroadcastFrameClock(onNewAwaiters).also { clock ->
                    fallbackClock = clock
                    failureCause?.let { cause ->
                        clock.cancel(
                            cause as? CancellationException
                                ?: CancellationException("clock cancelled")
                        )
                    }
                }
        }

    /**
     * Dispatches frame [timeNanos] to every awaiter, fast and fallback alike. Must be called on the
     * host's owner thread — this is the choreographer/frame-callback entry point.
     */
    fun sendFrame(timeNanos: Long) {
        host.checkOwnerThread()
        // Read the reference before dispatching so the affinity lock is never held across
        // callbacks.
        val fallbackClock = currentFallbackClock()
        host.trampoline.withBatchedResumes {
            // Rotate lists so that an awaiter re-awaiting from its onFrame callback (the animation
            // loop's steady state) lands in the next frame's list rather than this one.
            val toResume = awaiters
            awaiters = spareAwaiters
            spareAwaiters = toResume

            for (i in 0 until toResume.size) {
                val entry = toResume[i]
                when (entry.dispatchFrame(timeNanos)) {
                    FrameEntry.Completed -> liveAwaiters--
                    else -> {} // Already cancelled; its count was dropped at cancellation time.
                }
            }
            toResume.clear()

            // Fallback awaiters observe the same frame time. Their resumes go through their own
            // dispatcher, as they do today.
            fallbackClock?.sendFrame(timeNanos)
        }
    }

    /** Permanently fails this clock, resuming all current and future awaiters with [cause]. */
    fun fail(cause: Throwable) {
        host.runOnOwnerThread(
            FastTask {
                if (failureCause != null) return@FastTask
                failureCause = cause
                val fallbackClock = currentFallbackClock()
                val toFail = awaiters
                awaiters = spareAwaiters
                spareAwaiters = toFail
                for (i in 0 until toFail.size) {
                    if (toFail[i].failWith(cause)) liveAwaiters--
                }
                toFail.clear()
                // The fallback queue serves kotlinx.coroutines callers, whose contract *is*
                // exception-based, so a CancellationException is constructed here at the boundary.
                fallbackClock?.cancel(
                    cause as? CancellationException ?: CancellationException("clock cancelled")
                )
            }
        )
    }
}

/**
 * One entry in [FastFrameClock]'s awaiter list: either a single `withFrameNanos` call or a
 * [frameLoop][FastFrameClock.frameLoop] that spans many frames.
 *
 * Entries are separate objects from their continuations because `onFrame` must run during frame
 * dispatch while the coroutine's resume is trampolined until every callback has been delivered.
 */
private interface FrameEntry {
    /** Delivers [timeNanos]; returns [Completed] or [AlreadyCancelled]. */
    fun dispatchFrame(timeNanos: Long): Int

    /** Returns `true` if this entry was still live, i.e. if the live count should drop. */
    fun markCancelled(): Boolean

    /** Returns `true` if a live entry was failed. */
    fun failWith(cause: Throwable): Boolean

    companion object {
        const val AlreadyCancelled = 0
        const val Completed = 1
    }
}

/**
 * A single pending `withFrameNanos` call, which *is* its own continuation.
 *
 * Keeping the awaiter separate cost an extra object plus an `invokeOnCancellation` lambda on every
 * await — per animation, per frame. Folding them together leaves one object for the whole
 * suspension, and cancellation dequeues through [onCancellationRequested] instead of through a
 * handler.
 *
 * Resumes use the owner-thread-known variants: `dispatchFrame` is only ever called from
 * [FastFrameClock.sendFrame], which has already established the thread.
 */
private class FrameAwaiterContinuation<R>(
    delegate: Continuation<R>,
    host: FastCoroutineHost,
    job: FastJob?,
    onFrame: (Long) -> R,
    private val clock: FastFrameClock,
) : FastCancellableContinuation<R>(delegate, host, job), FrameEntry {

    private var onFrame: ((Long) -> R)? = onFrame

    override fun onCancellationRequested(cause: Throwable) {
        if (onFrame != null) {
            onFrame = null
            clock.onEntryCancelled()
        }
    }

    override fun markCancelled(): Boolean {
        val wasLive = onFrame != null
        onFrame = null
        return wasLive
    }

    override fun dispatchFrame(timeNanos: Long): Int {
        val onFrame = onFrame ?: return FrameEntry.AlreadyCancelled
        this.onFrame = null
        val result =
            try {
                onFrame(timeNanos)
            } catch (e: Throwable) {
                resumeWithExceptionOnOwnerThread(e)
                return FrameEntry.Completed
            }
        resumeOnOwnerThread(result)
        return FrameEntry.Completed
    }

    override fun failWith(cause: Throwable): Boolean {
        if (onFrame == null) return false
        onFrame = null
        resumeWithExceptionOnOwnerThread(cause)
        return true
    }
}

/**
 * A minimal owner-thread event loop, used as the [FastHandoffDispatcher] for hosts that are not
 * driven by a platform UI dispatcher (tests, benchmarks, non-UI hosts).
 *
 * Only the hand-off queue is synchronized; it is touched exclusively when work crosses threads.
 */
internal class FastEventLoop : FastHandoffDispatcher {
    private val affinity = ThreadAffinity()
    private var queue: MutableObjectList<FastTask> = mutableObjectListOf()
    private var spare: MutableObjectList<FastTask> = mutableObjectListOf()

    override fun schedule(task: FastTask) {
        affinity.withAccess { queue.add(task) }
    }

    /** Runs everything queued so far. Returns the number of tasks run. */
    fun pump(): Int {
        val toRun =
            affinity.withAccess {
                val toRun = queue
                queue = spare
                spare = toRun
                toRun
            }
        val count = toRun.size
        for (i in 0 until count) toRun[i].execute()
        toRun.clear()
        return count
    }

    val isEmpty: Boolean
        get() = affinity.withAccess { queue.isEmpty() }
}
