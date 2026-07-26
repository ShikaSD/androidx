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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restricted scope keeps animation-shaped code exactly as it reads today — `while (…)
 * withFrameNanos { … }` — while making a hand-off to kotlinx.coroutines impossible to write. These
 * tests cover the behaviour; the confinement itself is enforced at compile time (calling
 * `kotlinx.coroutines.delay` or the top-level `withFrameNanos` inside these bodies does not
 * compile).
 */
@OptIn(InternalComposeApi::class)
class FastRestrictedScopeTest {

    private class RestrictedFixture {
        val eventLoop = FastEventLoop()
        val scheduler = FastVirtualTimeScheduler()
        val unhandled = mutableListOf<Throwable>()
        val host =
            FastCoroutineHost(
                handoff = eventLoop,
                scheduler = scheduler,
                onUnhandledException = { unhandled.add(it) },
            )
        var framesRequested = 0
        val clock = FastFrameClock(host) { framesRequested++ }
        val scope = host.createScope()

        fun launch(block: suspend FastRestrictedScope.() -> Unit): FastJob =
            scope.launch(clock, block)

        fun frame(timeNanos: Long) = clock.sendFrame(timeNanos)
    }

    @Test
    fun frameLoopKeepsItsUsualShape() {
        val fixture = RestrictedFixture()
        val frames = mutableListOf<Long>()

        fixture.launch {
            var count = 0
            while (count < 3) {
                withFrameNanos { frameTimeNanos ->
                    frames.add(frameTimeNanos)
                    count++
                }
            }
        }

        fixture.frame(1L)
        fixture.frame(2L)
        fixture.frame(3L)

        assertEquals(listOf(1L, 2L, 3L), frames)
        assertEquals(3, fixture.host.stats.fastSuspensions)
        // Nothing was ever served by the kotlinx-backed queue, and nothing could have been.
        assertEquals(0, fixture.host.stats.fallbackSuspensions)
        assertFalse(fixture.clock.hasAwaiters)
    }

    @Test
    fun withFrameMillisConvertsTheFrameTime() {
        val fixture = RestrictedFixture()
        var millis = -1L
        fixture.launch { withFrameMillis { millis = it } }
        fixture.frame(5_000_000L)
        assertEquals(5L, millis)
    }

    @Test
    fun cancellationRetiresTheBodyAndRunsCleanup() {
        val fixture = RestrictedFixture()
        var unwound = false
        var caught: Throwable? = null
        var cleanedUp = false

        val job =
            fixture.launch {
                onCleanup { cleanedUp = true }
                try {
                    while (true) withFrameNanos {}
                } catch (e: Throwable) {
                    caught = e
                } finally {
                    unwound = true
                }
            }
        fixture.frame(1L)

        job.cancel()

        assertFalse(unwound)
        assertNull(caught)
        assertTrue(cleanedUp)
        assertTrue(job.isCancelled)
        assertFalse(fixture.clock.hasAwaiters)
    }

    @Test
    fun cancellationRunsRegisteredCleanupWithoutUnwinding() {
        val fixture = RestrictedFixture()
        var released = false
        var unwound = false

        val job =
            fixture.launch {
                onCleanup { released = true }
                try {
                    while (true) withFrameNanos {}
                } finally {
                    unwound = true
                }
            }
        fixture.frame(1L)

        job.cancel()

        assertTrue(released)
        assertFalse(unwound)
        assertFalse(fixture.clock.hasAwaiters)
    }

    @Test
    fun delayUsesTheHostScheduler() {
        val fixture = RestrictedFixture()
        var resumed = false

        fixture.launch {
            delay(500L)
            resumed = true
        }
        assertEquals(1, fixture.scheduler.pendingCount)

        fixture.scheduler.advanceTimeBy(500L)

        assertTrue(resumed)
    }

    @Test
    fun zeroDelayDoesNotSuspend() {
        val fixture = RestrictedFixture()
        var completed = false
        fixture.launch {
            delay(0L)
            completed = true
        }
        assertTrue(completed)
        assertEquals(0, fixture.host.stats.fastSuspensions)
    }

    @Test
    fun awaitCancellationParksUntilCancelled() {
        val fixture = RestrictedFixture()
        var released = false
        val job =
            fixture.launch {
                onCleanup { released = true }
                awaitCancellation()
            }
        assertTrue(job.isActive)

        job.cancel()

        assertTrue(released)
        assertTrue(job.isCancelled)
    }

    @Test
    fun mutateInterruptsTheRunningMutationAndKeepsAnimating() {
        val fixture = RestrictedFixture()
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

        fixture.launch {
            mutate(mutator) {
                events.add("second-start")
                withFrameNanos { events.add("second-frame") }
            }
        }
        fixture.frame(2L)

        assertEquals(listOf("first-start", "first-retired", "second-start", "second-frame"), events)
        assertFalse(mutator.isMutating)
    }

    @Test
    fun retargetPatternRunsEntirelyOnTheFastPath() {
        val fixture = RestrictedFixture()
        val mutator = FastMutator(FastMutatorMutex())
        val samples = mutableListOf<Long>()
        var job: FastJob? = null

        // The AnimateAsState.restartAnimation shape: cancel the in-flight animation, relaunch at
        // the
        // new target, once per frame.
        repeat(4) { iteration ->
            job?.cancel()
            job =
                fixture.launch {
                    mutate(mutator) { while (true) withFrameNanos { samples.add(it) } }
                }
            fixture.frame(iteration + 1L)
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), samples)
        assertEquals(0, fixture.host.stats.fallbackSuspensions)
        assertEquals(0, fixture.host.stats.kotlinxBridgeCrossings)
        assertTrue(fixture.unhandled.isEmpty())
    }
}
