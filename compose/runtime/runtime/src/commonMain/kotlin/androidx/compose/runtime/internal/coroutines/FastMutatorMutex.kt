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
import androidx.compose.runtime.internal.PlatformOptimizedCancellationException

/** Mirrors `MutatePriority` in animation-core. */
internal enum class FastMutatePriority {
    Default,
    UserInput,
    PreventUserInput,
}

/**
 * Thrown at a caller whose mutation is *refused* because a higher-priority one holds the lock.
 *
 * This is the one place the mutex throws, and it is a genuine failure of the operation the caller
 * asked for: it could not mutate, and there is no way to stop a running body other than by
 * throwing. Being *interrupted* — the common case, where a new mutation takes over — costs no
 * exception at all; the interrupted coroutine is simply retired.
 */
internal class FastMutationRefusedException :
    PlatformOptimizedCancellationException(
        "Mutation refused: a higher-priority mutation holds the lock"
    )

/**
 * Single-threaded counterpart of animation-core's `MutatorMutex`: mutual exclusion for UI state
 * mutation over time, where a new mutator interrupts the current one instead of queueing behind it.
 *
 * This is the primitive `Animatable` routes every `animateTo` and `snapTo` through, so it sits on
 * the path of every `animate*AsState`, every scrollbar fade and every fling. Differences from the
 * original, all of which come from the owner-thread invariant or from [FastCancellationMode]:
 * - The current-mutator slot is a plain field instead of an `AtomicReference` with a CAS loop.
 * - An uncontended `mutate` does not suspend at all: the lock is taken synchronously and the block
 *   runs on the caller's stack. `snapTo` on an idle `Animatable` therefore costs no continuation,
 *   no dispatch and no `coroutineScope`.
 * - The lock is released through [FastJob.addCleanup], not a `finally`. That is what makes a
 *   mutating coroutine safe to launch with [FastCancellationMode.ScopedCleanup]: the release
 *   happens when the job completes, whether or not its body is ever resumed to unwind.
 * - Ownership is handed directly to the next waiter on release, so a takeover does not race.
 */
internal class FastMutatorMutex {

    private class Mutator(val priority: FastMutatePriority, val job: FastJob) {
        fun canInterrupt(other: Mutator) = priority >= other.priority
    }

    private var currentMutator: Mutator? = null
    private var isLocked = false
    private var lockWaiters: MutableObjectList<FastCancellableContinuation<Unit>>? = null

    /** `true` while a mutation holds the lock. */
    val isMutating: Boolean
        get() = isLocked

    /**
     * Runs [block] with exclusive access, cancelling any current mutation whose priority is lower
     * or equal, and throwing [FastMutationInterruptedException] if the current mutation's priority
     * is higher. Reached through [FastRestrictedScope.mutate].
     */
    internal suspend fun <R> mutateIn(
        job: FastJob,
        priority: FastMutatePriority,
        block: suspend () -> R,
    ): R {
        val mutator = Mutator(priority, job)
        val existing = currentMutator
        if (existing != null && !mutator.canInterrupt(existing)) {
            throw FastMutationRefusedException()
        }
        currentMutator = mutator
        // Unlike MutatorMutex, a job does not interrupt itself: re-entering mutate from the same
        // coroutine would otherwise cancel the very body that is asking for the lock.
        if (existing != null && existing.job !== mutator.job) {
            // Retires the interrupted mutation: no exception is created and its body is never
            // resumed.
            existing.job.cancel()
        }

        acquireLock(job)

        // Registered, not `finally`: the release must also happen for a coroutine that is retired
        // without unwinding.
        val cleanup: () -> Unit = { release(mutator) }
        job.addCleanup(cleanup)
        val result = block()
        job.removeCleanup(cleanup)
        cleanup()
        return result
    }

    private suspend fun acquireLock(job: FastJob) {
        if (!isLocked) {
            // Uncontended: no suspension, no continuation, no dispatch.
            isLocked = true
            return
        }
        // Contended only while an interrupted mutator is still unwinding. Ownership is transferred
        // to
        // us by release(), so there is nothing to set once we are resumed.
        fastSuspendCancellableIn<Unit>(job.host, job) { continuation ->
            val waiters =
                lockWaiters
                    ?: mutableObjectListOf<FastCancellableContinuation<Unit>>().also {
                        lockWaiters = it
                    }
            waiters.add(continuation)
            continuation.invokeOnCancellation { lockWaiters?.remove(continuation) }
        }
    }

    private fun release(mutator: Mutator) {
        if (currentMutator === mutator) currentMutator = null
        val waiters = lockWaiters
        if (waiters != null && waiters.isNotEmpty()) {
            // Hand ownership straight to the next waiter: isLocked stays true. Release only ever
            // runs
            // on the owner thread — from a mutation returning, or from the job's cleanups — so the
            // resume does not need to re-check the thread.
            waiters.removeAt(0).resumeOnOwnerThread(Unit)
        } else {
            isLocked = false
        }
    }
}
