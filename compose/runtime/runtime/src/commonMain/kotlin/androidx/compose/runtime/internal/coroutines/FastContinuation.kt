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

import androidx.compose.runtime.InternalComposeApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Sentinel distinguishing "no value parked" from a parked `null`, so that a failure can be parked
 * in a separate field instead of a wrapper object.
 */
private val NoParkedValue = Any()

/**
 * Passed to `invokeOnCancellation` handlers when a suspension is retired.
 *
 * Cancellation is not a failure here and creates no exception, but handlers take a `Throwable`
 * because they are also invoked for genuine failures. This one instance stands in for "cancelled":
 * it is never thrown, never delivered to a coroutine body, and exists only so those handlers have
 * something to receive.
 */
internal val RetiredSignal: Throwable = FastRetired()

private class FastRetired : Throwable("coroutine retired")

/** `Result.success(Unit)`, boxed once, for the overwhelmingly common `Unit`-valued resume. */
private val UnitResult: Result<Unit> = Result.success(Unit)

/**
 * A cancellable continuation for single-threaded use, the fast-path counterpart of
 * `kotlinx.coroutines.CancellableContinuationImpl`.
 *
 * What it does differently:
 * - **No interception.** It resumes the *unintercepted* continuation directly, so a resume
 *   continues the coroutine body on the current stack instead of allocating a
 *   `DispatchedContinuation` and going through a dispatcher queue. Reentrancy and ordering are
 *   handled by [FastTrampoline].
 * - **No atomics.** State is a plain `Int`, sound because every mutation happens on the host's
 *   owner thread; resumes arriving from another thread are forwarded there by [FastCoroutineHost].
 * - **No handler nodes.** It is itself the [FastCancellable] registered on the job and the
 *   [FastTask] posted to the trampoline, so an await costs one allocation in total.
 * - **Synchronous completion is free.** If the suspend block resumes before returning (a frame that
 *   is already available, an uncontended lock), [getResult] returns the value and the coroutine
 *   never suspends: no state machine save, no dispatch.
 *
 * Cancellation deliberately does *not* match `suspendCancellableCoroutine`: there is no exception.
 * Cancelling [retire]s the suspension, the body is never resumed and stops where it is parked, and
 * its cleanup runs from registrations instead of from `finally`. An already-resumed continuation
 * ignores cancellation, and a resume of a retired continuation is dropped.
 */
@OptIn(InternalComposeApi::class)
internal open class FastCancellableContinuation<T>(
    private val delegate: Continuation<T>,
    private val host: FastCoroutineHost,
    private val job: FastJob?,
) : FastCancellable, FastTask, FastContinuation<T> {

    private var state = UNDECIDED

    /** Parked success value, or [NoParkedValue] when nothing (or a failure) is parked. */
    private var parkedValue: Any? = NoParkedValue

    /**
     * Parked failure, kept in its own field so no wrapper object is allocated per failed resume.
     */
    private var parkedFailure: Throwable? = null

    private var onCancellation: ((Throwable) -> Unit)? = null
    private var registeredWithJob = false

    val isActive: Boolean
        get() = state == UNDECIDED || state == SUSPENDED

    /**
     * Registers with the enclosing job so that cancelling it retires this suspension.
     *
     * If the job is already cancelled the registration is refused, and this suspension is retired
     * immediately: [getResult] then parks the body for good rather than throwing into it.
     */
    internal fun attachToJob() {
        val job = job ?: return
        registeredWithJob = job.addAwaiter(this)
        if (!registeredWithJob) retire()
    }

    /**
     * Registers [handler] to run when this continuation is cancelled, used by awaiters to remove
     * themselves from their queue. Invoked immediately if cancellation already happened.
     */
    override fun invokeOnCancellation(handler: (Throwable) -> Unit) {
        when (state) {
            RETIRED -> handler(RetiredSignal)
            RESUMED -> parkedFailure?.let { handler(it) }
            else -> onCancellation = handler
        }
    }

    override fun resume(value: T) {
        if (host.isOnOwnerThread) {
            deliverOnOwnerThread(value, failure = null)
        } else {
            handOffToOwnerThread(value, failure = null)
        }
    }

    override fun resumeWithException(exception: Throwable) {
        if (host.isOnOwnerThread) {
            deliverOnOwnerThread(NoParkedValue, exception)
        } else {
            handOffToOwnerThread(NoParkedValue, exception)
        }
    }

    /**
     * Resume from a caller that is already known to be on the owner thread — frame dispatch, a lock
     * hand-off, a timer callback. Skips the `currentThreadId()` read that [resume] needs in order
     * to decide whether to hand off, which the method trace showed happening about four times per
     * animation per frame purely to re-derive a fact the frame dispatch had already established.
     *
     * Calling this from another thread would corrupt owner-thread-confined state, so it is internal
     * and every call site must be reachable only from the owner thread.
     */
    internal fun resumeOnOwnerThread(value: T) {
        deliverOnOwnerThread(value, failure = null)
    }

    /** [resumeOnOwnerThread] for a failure. */
    internal fun resumeWithExceptionOnOwnerThread(exception: Throwable) {
        deliverOnOwnerThread(NoParkedValue, exception)
    }

    fun resumeWith(result: Result<T>) {
        val exception = result.exceptionOrNull()
        if (exception != null) resumeWithException(exception)
        else @Suppress("UNCHECKED_CAST") resume(result.getOrNull() as T)
    }

    /**
     * Multithreading detected on a suspension: rather than making this continuation's state
     * thread-safe, hand the resume to the owner thread. The single-writer invariant holds, and a
     * foreign resume racing an owner-thread cancellation is serialized there.
     */
    private fun handOffToOwnerThread(value: Any?, failure: Throwable?) {
        host.runOnOwnerThread(FastTask { deliverOnOwnerThread(value, failure) })
    }

    private fun deliverOnOwnerThread(value: Any?, failure: Throwable?) {
        when (state) {
            UNDECIDED -> {
                // Resumed before the suspend block returned: getResult() will hand the value
                // straight back to the caller without ever suspending.
                parkedValue = value
                parkedFailure = failure
                state = RESUMED
            }
            SUSPENDED -> {
                state = RESUMED
                parkedValue = value
                parkedFailure = failure
                onCancellation = null
                if (registeredWithJob) {
                    registeredWithJob = false
                    job?.removeAwaiter(this)
                }
                // `this` is the task: no wrapper allocation for the trampolined resume.
                host.trampoline.execute(this)
            }
            else -> {
                // Already resumed or cancelled; drop, as kotlinx.coroutines does.
            }
        }
    }

    /** Trampoline callback: resumes the coroutine body. */
    override fun execute() {
        val failure = parkedFailure
        val value = parkedValue
        parkedFailure = null
        parkedValue = NoParkedValue
        if (failure != null) {
            // Only a genuine failure reaches here: a frame callback that threw, or a failed clock.
            delegate.resumeWith(Result.failure(failure))
        } else if (value === Unit) {
            // `withFrameNanos {}` and most other awaits resume with Unit; boxing one Result for it
            // keeps the steady-state frame loop from allocating per resume.
            @Suppress("UNCHECKED_CAST") delegate.resumeWith(UnitResult as Result<T>)
        } else {
            @Suppress("UNCHECKED_CAST") delegate.resumeWith(Result.success(value as T))
        }
    }

    /**
     * Called before the cancellation handler when this suspension is cancelled. Overridden by
     * continuations that are themselves queue entries, so that dequeuing costs no handler lambda.
     */
    protected open fun onCancellationRequested(cause: Throwable) {}

    override fun retire(): Boolean {
        // Already resumed: a delivery is in flight (or done), so the body is not ours to retire.
        if (state == RESUMED || state == RETIRED) return false
        registeredWithJob = false
        onCancellationRequested(RetiredSignal)
        val handler = onCancellation
        onCancellation = null
        // The handler is how a suspension releases what it holds — a frame-clock slot, a scheduled
        // timer, a queue entry. It runs even though the body never will.
        handler?.invoke(RetiredSignal)
        state = RETIRED
        parkedValue = NoParkedValue
        parkedFailure = null
        return true
    }

    /** Retires this suspension without cancelling the enclosing job. Safe from any thread. */
    fun cancel() {
        if (host.isOnOwnerThread) {
            if (registeredWithJob) {
                registeredWithJob = false
                job?.removeAwaiter(this)
            }
            retire()
        } else {
            host.runOnOwnerThread(FastTask { cancel() })
        }
    }

    /** Returns the result if it is already available, or [COROUTINE_SUSPENDED] to suspend. */
    internal fun getResult(): Any? {
        if (state == UNDECIDED) {
            state = SUSPENDED
            host.stats.fastSuspensions++
            return COROUTINE_SUSPENDED
        }
        if (state == RETIRED) {
            // Cancelled before this suspension could park. Returning COROUTINE_SUSPENDED unwinds
            // the
            // *native* stack back to the caller without an exception, and since nothing will ever
            // resume this continuation, the body ends here.
            return COROUTINE_SUSPENDED
        }
        host.stats.syncCompletions++
        val failure = parkedFailure
        val value = parkedValue
        parkedFailure = null
        parkedValue = NoParkedValue
        if (failure != null) throw failure
        return value
    }

    private companion object {
        const val UNDECIDED = 0
        const val SUSPENDED = 1
        const val RESUMED = 2

        /**
         * Cancelled. The coroutine body is parked here for good: nothing resumes it, so it ends
         * without an exception being created, thrown, or caught anywhere.
         */
        const val RETIRED = 3
    }
}

/**
 * The fast-path suspension for callers that already know their host and job — the restricted-scope
 * form, which skips the `CoroutineContext` lookup that [fastSuspendCancellableCoroutine] needs.
 *
 * In a `while (…) withFrameNanos { … }` loop that lookup happens on every frame of every animation,
 * so removing it is worth having a second entry point for.
 */
internal suspend inline fun <T> fastSuspendCancellableIn(
    host: FastCoroutineHost,
    job: FastJob,
    crossinline block: (FastCancellableContinuation<T>) -> Unit,
): T = suspendCoroutineUninterceptedOrReturn { uCont ->
    val continuation = FastCancellableContinuation<T>(uCont, host, job)
    continuation.attachToJob()
    block(continuation)
    continuation.getResult()
}
