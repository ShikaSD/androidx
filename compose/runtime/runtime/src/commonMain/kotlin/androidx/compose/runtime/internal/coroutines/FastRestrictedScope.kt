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
import kotlin.coroutines.RestrictsSuspension

/**
 * A coroutine scope whose body is *statically* confined to the fast runtime.
 *
 * [RestrictsSuspension] means a suspending lambda with this receiver may only call suspend
 * functions declared as members or extensions of this scope. Inside such a body,
 * `kotlinx.coroutines.delay`, `withContext`, `Mutex.withLock`, `Flow.collect` and the top-level
 * `androidx.compose.runtime.withFrameNanos` do not compile. Two consequences:
 * 1. **No hand-off, by construction.** The runtime does not have to detect whether a suspension
 *    came from a fast coroutine or a kotlinx one, because in here it cannot be a kotlinx one. The
 *    fallback paths still exist for callers that are genuinely mixed (a `LaunchedEffect` awaiting
 *    the same clock); they are simply unreachable from a restricted body.
 * 2. **Nothing is discovered at runtime.** The scope carries the host, job and clock as fields, so
 *    `withFrameNanos` reaches them directly. The context-based path has to look up the
 *    `MonotonicFrameClock` and then the `FastCoroutineElement` on *every* suspension, which in a
 *    `while (…) withFrameNanos { … }` loop means twice per frame per animation, forever.
 *
 * Crucially, the code inside is unchanged: an animation still reads
 *
 * ```
 * while (isRunning) {
 *     withFrameNanos { frameTimeNanos -> … }
 * }
 * ```
 *
 * The only difference is that the enclosing function takes this receiver, the way `AnimationScope`
 * is already threaded through animation-core. Helper functions become extensions on the scope.
 *
 * The restriction is also the limitation: a body that genuinely needs a kotlinx primitive cannot
 * run here at all. That is deliberate — this is the prototype's only coroutine flavour, so every
 * fast suspension is one of this scope's members and no code path has to decide at runtime which
 * world it is in.
 */
@RestrictsSuspension
@InternalComposeApi
public class FastRestrictedScope
internal constructor(
    internal val host: FastCoroutineHost,
    internal val job: FastJob,
    internal val clock: FastFrameClock,
) {
    /**
     * Suspends until the next frame, then invokes [onFrame] with its time during frame dispatch —
     * the same contract as `MonotonicFrameClock.withFrameNanos`, resolved against this scope.
     */
    public suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        clock.awaitFrameIn(job, onFrame)

    /** Millisecond form, mirroring `withFrameMillis`. */
    public suspend fun <R> withFrameMillis(onFrame: (frameTimeMillis: Long) -> R): R =
        clock.awaitFrameIn(job) { onFrame(it / 1_000_000L) }

    /**
     * Suspends for [millis] on the host's scheduler. Cancellation cancels the scheduled wake-up.
     */
    public suspend fun delay(millis: Long) {
        if (millis <= 0L) return
        val scheduler =
            host.scheduler
                ?: throw IllegalStateException(
                    "This FastCoroutineHost has no FastScheduler, so delay is unavailable."
                )
        delayIn(host, job, scheduler, millis)
    }

    /** Suspends until this coroutine is cancelled. */
    public suspend fun awaitCancellation(): Nothing {
        fastSuspendCancellableIn<Nothing>(host, job) {
            // Only cancellation completes this suspension.
        }
        throw IllegalStateException("awaitCancellation resumed")
    }

    /**
     * Registers [action] to run once when this coroutine completes, for any reason — including
     * cancellation, which never resumes the body. This is the replacement for `finally`: a
     * `finally` in a cancelled body does not run, because nothing is thrown into it.
     */
    public fun onCleanup(action: () -> Unit) {
        job.addCleanup(action)
    }

    /**
     * Suspends until [block]'s continuation is resumed — the fast counterpart of
     * `suspendCancellableCoroutine`, for parking on an external callback (a listener, an emitter,
     * an input event queue).
     *
     * A resource held across the suspension should be released from the continuation's
     * `invokeOnCancellation`, which runs in both cancellation modes.
     */
    public suspend fun <T> suspendCancellable(block: (FastContinuation<T>) -> Unit): T =
        fastSuspendCancellableIn(host, job) { continuation -> block(continuation) }

    /**
     * Starts a child coroutine in its own restricted scope. Cancelling this coroutine cancels the
     * child; the child completing does not affect this one.
     *
     * Not a suspend function, so it is callable from a restricted body.
     */
    public fun launch(block: suspend FastRestrictedScope.() -> Unit): FastCancellableJob =
        FastCoroutineScope(host, job).launch(clock, block)

    /** Suspends until [other] completes. Does not rethrow its failure. */
    public suspend fun join(other: FastCancellableJob) {
        if (other !is FastJob) throw IllegalArgumentException("not a fast job: $other")
        other.joinIn(host, job)
    }

    /**
     * Runs [block] holding [mutator], cancelling any current mutation of lower or equal [priority]
     * — the `MutatorMutex.mutate` that `Animatable` wraps every animation in.
     *
     * The one place the restriction shows through in call style: a restricted body may only call
     * suspend functions whose receiver *is* the scope, so this takes the mutator as a parameter and
     * reads `mutate(mutatorMutex) { … }` rather than `mutatorMutex.mutate { … }`. [block] gets this
     * scope as its receiver, so the mutation can keep awaiting frames.
     */
    public suspend fun <R> mutate(
        mutator: FastMutator,
        priority: Int = 0,
        block: suspend FastRestrictedScope.() -> R,
    ): R =
        mutator.mutex.mutateIn(
            job,
            when (priority) {
                1 -> FastMutatePriority.UserInput
                2 -> FastMutatePriority.PreventUserInput
                else -> FastMutatePriority.Default
            },
        ) {
            block(this@FastRestrictedScope)
        }
}
