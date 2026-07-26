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
import kotlin.test.assertTrue

/** Fixture with a virtual-time scheduler, so delays are deterministic. */
@OptIn(InternalComposeApi::class)
private class DelayTestHost {
    val eventLoop = FastEventLoop()
    val scheduler = FastVirtualTimeScheduler()
    val unhandled = mutableListOf<Throwable>()
    val host =
        FastCoroutineHost(
            handoff = eventLoop,
            scheduler = scheduler,
            onUnhandledException = { unhandled.add(it) },
        )
    val clock = FastFrameClock(host)
    val scope = host.createScope()

    fun launch(block: suspend FastRestrictedScope.() -> Unit): FastJob = scope.launch(clock, block)
}

@OptIn(InternalComposeApi::class)
class FastDelayTest {

    @Test
    fun delayResumesWhenTheScheduledTimeArrives() {
        val fixture = DelayTestHost()
        var resumed = false

        fixture.launch {
            delay(500L)
            resumed = true
        }

        assertFalse(resumed)
        assertEquals(1, fixture.scheduler.pendingCount)

        fixture.scheduler.advanceTimeBy(499L)
        assertFalse(resumed)

        fixture.scheduler.advanceTimeBy(1L)
        assertTrue(resumed)
        assertEquals(0, fixture.scheduler.pendingCount)
    }

    @Test
    fun nonPositiveDelayDoesNotSuspend() {
        val fixture = DelayTestHost()
        var completed = false

        fixture.launch {
            delay(0L)
            completed = true
        }

        assertTrue(completed)
        assertEquals(0, fixture.host.stats.fastSuspensions)
        assertEquals(0, fixture.scheduler.pendingCount)
    }

    @Test
    fun cancellingADelayDisposesTheScheduledTask() {
        val fixture = DelayTestHost()
        var resumed = false
        var unwound = false

        val job =
            fixture.launch {
                onCleanup { unwound = true }
                delay(500L)
                resumed = true
            }
        assertEquals(1, fixture.scheduler.pendingCount)

        job.cancel()

        assertTrue(unwound)
        // Nothing is left pending, so a fade-out rescheduled every scroll frame does not pile up.
        assertEquals(0, fixture.scheduler.pendingCount)

        fixture.scheduler.advanceTimeBy(1_000L)
        assertFalse(resumed)
    }

    @Test
    fun scopedCleanupCancellationAlsoDisposesTheScheduledTask() {
        val fixture = DelayTestHost()
        var released = false

        val job =
            fixture.launch {
                onCleanup { released = true }
                delay(500L)
            }
        assertEquals(1, fixture.scheduler.pendingCount)

        job.cancel()

        // The suspension's own cancellation handler runs in both modes, so the timer is disposed
        // even
        // though the body was never resumed.
        assertEquals(0, fixture.scheduler.pendingCount)
        assertTrue(released)
        assertTrue(job.isCancelled)
    }

    @Test
    fun rescheduledDelayLeavesOnlyOnePendingTask() {
        val fixture = DelayTestHost()
        var fired = 0
        var job: FastJob? = null

        // The scrollbar fade shape: every scroll frame cancels the pending fade and starts a new
        // one.
        repeat(10) {
            job?.cancel()
            job =
                fixture.launch {
                    delay(500L)
                    fired++
                }
            assertEquals(1, fixture.scheduler.pendingCount)
        }

        fixture.scheduler.advanceTimeBy(500L)
        assertEquals(1, fired)
    }

    @Test
    fun delaysRunInDueTimeOrderRegardlessOfSchedulingOrder() {
        val fixture = DelayTestHost()
        val order = mutableListOf<String>()

        fixture.launch {
            delay(300L)
            order.add("late")
        }
        fixture.launch {
            delay(100L)
            order.add("early")
        }
        fixture.launch {
            delay(200L)
            order.add("middle")
        }

        fixture.scheduler.advanceTimeBy(300L)

        assertEquals(listOf("early", "middle", "late"), order)
    }

    @Test
    fun delayWithoutASchedulerFailsWithAClearMessage() {
        val unhandled = mutableListOf<Throwable>()
        val host =
            FastCoroutineHost(
                handoff = FastEventLoop(),
                onUnhandledException = { unhandled.add(it) },
            )
        val clock = FastFrameClock(host)

        host.createScope().launch(clock) { delay(10L) }

        val failure = unhandled.firstOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message!!.contains("FastScheduler"))
    }
}
