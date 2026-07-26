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
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmName

/**
 * Minimal public surface over the single-threaded coroutine prototype, so that benchmarks and
 * experiments outside the runtime module can drive it. The implementation itself stays `internal`.
 *
 * Everything runs in a [FastRestrictedScope]: that is the prototype's only coroutine flavour, so a
 * body is statically confined to the fast runtime and no suspension consults a `CoroutineContext`.
 *
 * Not an API proposal: if this prototype graduates, the shape should be decided separately.
 */

/** A suspension started by [FastRestrictedScope.suspendCancellable]. */
@InternalComposeApi
public interface FastContinuation<T> {
    /** Resumes the awaiting coroutine with [value]. Safe to call from any thread. */
    public fun resume(value: T)

    /** Resumes the awaiting coroutine by throwing [exception] from the suspension point. */
    public fun resumeWithException(exception: Throwable)

    /** Registers [handler] to run if this suspension is cancelled. */
    public fun invokeOnCancellation(handler: (Throwable) -> Unit)
}

/** Handle to a coroutine started by [FastCoroutinesPrototype.launch]. */
@InternalComposeApi
public interface FastCancellableJob {
    public val isActive: Boolean

    @get:Suppress("GetterSetterNames") public val isCancelled: Boolean

    @get:Suppress("GetterSetterNames") public val isCompleted: Boolean

    /** Cancels this coroutine and its children. Safe to call from any thread. */
    public fun cancel()
}

/**
 * Interruptible mutual exclusion, the fast counterpart of animation-core's `MutatorMutex`. Obtain
 * one from [FastCoroutinesPrototype.newMutator] and hold it with [FastRestrictedScope.mutate].
 */
@InternalComposeApi
public class FastMutator internal constructor(internal val mutex: FastMutatorMutex) {
    /** `true` while a mutation holds the lock. */
    @get:Suppress("GetterSetterNames")
    @get:JvmName("isMutating")
    public val isMutating: Boolean
        get() = mutex.isMutating
}

/**
 * A self-contained single-threaded coroutine world: a host bound to the calling thread, an event
 * loop for cross-thread hand-offs, a virtual-time scheduler for [FastRestrictedScope.delay], and a
 * [MonotonicFrameClock] whose awaiters are resumed by [sendFrame].
 *
 * Must be constructed and driven from one thread; see the internal `FastCoroutineHost`
 * documentation for what happens when other threads get involved.
 */
@InternalComposeApi
public class FastCoroutinesPrototype(
    kotlinxContext: CoroutineContext = EmptyCoroutineContext,
    private val onFrameRequested: (() -> Unit)? = null,
) {
    private val eventLoop = FastEventLoop()
    private val virtualTime = FastVirtualTimeScheduler()
    private val host = FastCoroutineHost(eventLoop, kotlinxContext, virtualTime)
    private val clock = FastFrameClock(host, onFrameRequested)
    private val scope = host.createScope()

    /**
     * The frame clock kotlinx.coroutines callers can await; fast coroutines use the scope's member.
     */
    public val frameClock: MonotonicFrameClock
        get() = clock

    /** `true` if any coroutine is currently awaiting a frame. */
    @get:Suppress("GetterSetterNames")
    @get:JvmName("hasFrameAwaiters")
    public val hasFrameAwaiters: Boolean
        get() = clock.hasAwaiters

    /**
     * Starts [block] undispatched in a restricted scope, running it on the calling stack until its
     * first suspension. The compiler rejects any call into kotlinx.coroutines from inside.
     */
    public fun launch(block: suspend FastRestrictedScope.() -> Unit): FastCancellableJob =
        scope.launch(clock, block)

    /** Dispatches a frame to all awaiters of [frameClock]. */
    public fun sendFrame(timeNanos: Long) {
        clock.sendFrame(timeNanos)
    }

    /**
     * Advances the virtual clock backing [FastRestrictedScope.delay], running everything due.
     *
     * Delays are virtual here so that benchmarks and tests never sleep; a production host installs
     * a timer-backed scheduler (`Handler.postDelayed`) instead.
     */
    public fun advanceTimeBy(millis: Long) {
        virtualTime.advanceTimeBy(millis)
    }

    /** Number of scheduled delays that have neither fired nor been cancelled. */
    public fun pendingDelayCount(): Int = virtualTime.pendingCount

    /** Creates a mutator with the interruption semantics of animation-core's `MutatorMutex`. */
    public fun newMutator(): FastMutator = FastMutator(FastMutatorMutex())

    /** Runs work that was handed off from other threads. Returns the number of tasks run. */
    public fun pump(): Int = eventLoop.pump()

    /** Cancels every coroutine in this world. */
    public fun cancel() {
        scope.cancel()
    }

    /** Diagnostic counters, including how often kotlinx.coroutines was reached. */
    public fun statsString(): String = host.stats.toString()
}
