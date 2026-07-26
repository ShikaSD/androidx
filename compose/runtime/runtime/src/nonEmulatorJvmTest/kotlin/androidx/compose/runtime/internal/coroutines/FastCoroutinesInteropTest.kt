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
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Interop and multithreading behaviour of the fast coroutine prototype.
 *
 * These live in a JVM-only source set because they need real threads.
 */
@OptIn(InternalComposeApi::class)
class FastCoroutinesInteropTest {

    @Test
    fun kotlinxCallerIsServedByTheFallbackClock() = runBlocking {
        val fixture = FastTestHost()
        var frameTime = -1L

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.clock.withFrameNanos { frameTime = it }
            }

        assertEquals(1, fixture.host.stats.fallbackSuspensions)
        assertEquals(0, fixture.host.stats.fastSuspensions)
        assertEquals(1, fixture.framesRequested)

        fixture.frame(7L)
        job.join()

        assertEquals(7L, frameTime)
    }

    @Test
    fun fastAndKotlinxAwaitersShareTheSameFrame() = runBlocking {
        val fixture = FastTestHost()
        var fastFrame = -1L
        var kotlinxFrame = -1L

        fixture.launch { withFrameNanos { fastFrame = it } }
        val kotlinxJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.clock.withFrameNanos { kotlinxFrame = it }
            }

        fixture.frame(11L)
        kotlinxJob.join()

        assertEquals(11L, fastFrame)
        assertEquals(11L, kotlinxFrame)
        assertEquals(1, fixture.host.stats.fastSuspensions)
        assertEquals(1, fixture.host.stats.fallbackSuspensions)
    }

    @Test
    fun kotlinxCallerCancellationRemovesItsAwaiter() = runBlocking {
        val fixture = FastTestHost()
        var resumed = false

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.clock.withFrameNanos { resumed = true }
            }
        job.cancel()
        job.join()

        fixture.frame(1L)
        assertFalse(resumed)
        assertFalse(fixture.clock.hasAwaiters)
    }

    @Test
    fun awaitFromForeignThreadPromotesClockAndStillReceivesFrames() {
        val fixture = FastTestHost()
        val awaiting = CountDownLatch(1)
        val received = CountDownLatch(1)
        var frameTime = -1L

        val worker = thread {
            runBlocking {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.clock.withFrameNanos {
                        frameTime = it
                        received.countDown()
                    }
                }
                awaiting.countDown()
            }
        }

        assertTrue(awaiting.await(5, TimeUnit.SECONDS))
        assertTrue(fixture.clock.isShared)
        assertEquals(1, fixture.host.stats.promotions)

        fixture.frame(23L)
        assertTrue(received.await(5, TimeUnit.SECONDS))
        worker.join()

        assertEquals(23L, frameTime)
    }

    @Test
    fun cancellationFromForeignThreadIsForwardedToOwnerThread() {
        val fixture = FastTestHost()
        var unwound = false
        val job =
            fixture.launch {
                onCleanup { unwound = true }
                while (true) withFrameNanos {}
            }
        fixture.frame(1L)

        thread { job.cancel() }.join()

        // The cancellation is queued for the owner thread, not applied on the caller's thread.
        assertFalse(unwound)
        assertEquals(1, fixture.host.stats.crossThreadHandoffs)

        fixture.eventLoop.pump()

        assertTrue(unwound)
        assertTrue(job.isCancelled)
    }

    @Test
    fun resumeFromForeignThreadIsForwardedToOwnerThread() {
        val fixture = FastTestHost()
        var resumedWith = 0
        var continuation: FastContinuation<Int>? = null
        fixture.launch { resumedWith = suspendCancellable { continuation = it } }
        val captured = requireNotNull(continuation)

        thread { captured.resume(99) }.join()

        assertEquals(0, resumedWith)
        fixture.eventLoop.pump()
        assertEquals(99, resumedWith)
    }

    @Test
    fun withFastCoroutineRunsFastWorkFromKotlinxAndPropagatesCancellation() = runBlocking {
        val fixture = FastTestHost()
        var frames = 0
        var unwound = false

        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                withFastCoroutine(fixture.host, fixture.clock) {
                    onCleanup { unwound = true }
                    while (true) withFrameNanos { frames++ }
                }
            }

        fixture.frame(1L)
        fixture.frame(2L)
        assertEquals(2, frames)
        // Frames were driven entirely by fast suspensions: two delivered plus the pending third.
        assertEquals(3, fixture.host.stats.fastSuspensions)
        assertEquals(0, fixture.host.stats.fallbackSuspensions)

        job.cancel()
        job.join()

        assertTrue(unwound)
        fixture.frame(3L)
        assertEquals(2, frames)
    }

    @Test
    fun withFastCoroutineReturnsResult() = runBlocking {
        val fixture = FastTestHost()
        var result = ""
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                result =
                    withFastCoroutine(fixture.host, fixture.clock) { withFrameNanos { "at $it" } }
            }
        fixture.frame(42L)
        job.join()
        assertEquals("at 42", result)
    }

    @Test
    fun asFastCoroutineScopeTiesLifetimeToKotlinxJob() = runBlocking {
        val fixture = FastTestHost()
        var unwound = false

        val ownerJob = Job()
        val ownerScope = CoroutineScope(coroutineContext + ownerJob)
        val fastScope = ownerScope.asFastCoroutineScope(fixture.host)
        fastScope.launch(fixture.clock) {
            onCleanup { unwound = true }
            while (true) withFrameNanos {}
        }

        fixture.frame(1L)
        assertEquals(emptyList(), fixture.unhandled)
        assertFalse(unwound)

        // Cancelling the kotlinx job cancels the whole fast subtree through a single registration.
        ownerJob.cancel()

        assertTrue(unwound)
        assertFalse(fixture.clock.hasAwaiters)
    }
}
