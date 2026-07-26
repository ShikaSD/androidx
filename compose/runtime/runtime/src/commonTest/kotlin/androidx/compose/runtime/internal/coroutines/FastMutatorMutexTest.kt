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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(InternalComposeApi::class)
class FastMutatorMutexTest {

    @Test
    fun uncontendedMutateDoesNotSuspend() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        var result = 0

        fixture.launch { result = mutate(mutator) { 7 } }

        assertEquals(7, result)
        // The whole point of the uncontended path: no continuation, no dispatch, nothing parked.
        assertEquals(0, fixture.host.stats.fastSuspensions)
        assertEquals(0, fixture.host.stats.syncCompletions)
        assertFalse(mutator.isMutating)
    }

    @Test
    fun equalPriorityMutationInterruptsTheCurrentOne() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        val events = mutableListOf<String>()

        fixture.launch {
            onCleanup { events.add("first-retired") }
            mutate(mutator) {
                events.add("first-start")
                while (true) withFrameNanos {}
            }
        }
        fixture.frame(1L)

        fixture.launch { mutate(mutator) { events.add("second-start") } }

        // Interrupting throws nothing: the first mutation is retired and its cleanup releases the
        // lock, which is what lets the second one proceed.
        assertEquals(listOf("first-start", "first-retired", "second-start"), events)
        assertFalse(mutator.isMutating)
    }

    @Test
    fun lowerPriorityMutationIsRefusedAndLeavesTheCurrentOneRunning() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        var refused: Throwable? = null
        var secondRan = false
        var firstInterrupted = false

        fixture.launch {
            onCleanup { firstInterrupted = true }
            mutate(mutator, 1) { while (true) withFrameNanos {} }
        }
        fixture.frame(1L)

        fixture.launch {
            try {
                mutate(mutator, 0) { secondRan = true }
            } catch (e: CancellationException) {
                refused = e
            }
        }

        assertIs<FastMutationRefusedException>(refused)
        assertFalse(secondRan)
        assertFalse(firstInterrupted)
        assertTrue(mutator.isMutating)
    }

    @Test
    fun higherPriorityMutationInterruptsALowerOne() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        var interrupted = false
        var highPriorityRan = false

        fixture.launch {
            onCleanup { interrupted = true }
            mutate(mutator, 0) { while (true) withFrameNanos {} }
        }
        fixture.frame(1L)

        fixture.launch { mutate(mutator, 2) { highPriorityRan = true } }

        assertTrue(interrupted)
        assertTrue(highPriorityRan)
    }

    @Test
    fun takeoverWaitsForTheInterruptedMutatorToRelease() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        val events = mutableListOf<String>()

        fixture.launch {
            onCleanup { events.add("first-released") }
            mutate(mutator) { while (true) withFrameNanos {} }
        }
        fixture.frame(1L)

        // Cancelling from inside a frame dispatch defers retirement to the end of the batch, so the
        // takeover has to queue on the lock rather than assume it is free.
        fixture.launch {
            withFrameNanos {}
            mutate(mutator) { events.add("second-start") }
        }
        fixture.frame(2L)

        assertEquals(listOf("first-released", "second-start"), events)
        assertFalse(mutator.isMutating)
    }

    @Test
    fun cancellingTheMutatingJobReleasesTheLock() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        val job = fixture.launch { mutate(mutator) { while (true) withFrameNanos {} } }
        fixture.frame(1L)
        assertTrue(mutator.isMutating)

        job.cancel()

        assertFalse(mutator.isMutating)
        var laterRan = false
        fixture.launch { mutate(mutator) { laterRan = true } }
        assertTrue(laterRan)
    }

    @Test
    fun cancelledMutatorReleasesTheLockWithoutUnwinding() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        var unwound = false

        val job =
            fixture.launch {
                mutate(mutator) { while (true) withFrameNanos {} }
                unwound = true
            }
        fixture.frame(1L)
        assertTrue(mutator.isMutating)

        job.cancel()

        // The body was never resumed, yet the lock is free: the release is a registered cleanup,
        // not
        // a `finally`. This is what makes MutatorMutex-shaped code usable in ScopedCleanup mode.
        assertFalse(unwound)
        assertFalse(mutator.isMutating)
        assertTrue(fixture.unhandled.isEmpty())

        var laterRan = false
        fixture.launch { mutate(mutator) { laterRan = true } }
        assertTrue(laterRan)
    }

    @Test
    fun reentrantMutateFromTheSameJobDoesNotCancelItself() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        var innerRan = false
        var failure: Throwable? = null

        fixture.launch {
            try {
                mutate(mutator) {
                    // Not something Compose does, but self-cancellation here would deadlock rather
                    // than fail visibly, so the mutex refuses to interrupt its own job.
                    innerRan = mutator.isMutating
                }
            } catch (e: Throwable) {
                failure = e
            }
        }

        assertTrue(innerRan)
        assertEquals(null, failure)
        assertFalse(mutator.isMutating)
    }

    @Test
    fun mutationFailurePropagatesAndReleasesTheLock() {
        val fixture = FastTestHost()
        val mutator = FastMutator(FastMutatorMutex())
        val failure = RuntimeException("mutation failed")
        var caught: Throwable? = null

        fixture.launch {
            try {
                mutate<Unit>(mutator) { throw failure }
            } catch (e: RuntimeException) {
                caught = e
            }
        }

        assertEquals(failure, caught)
        assertFalse(mutator.isMutating)
    }
}
