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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test fixture wiring a host, an event loop, a scheduler and a frame clock on the current thread.
 */
@OptIn(InternalComposeApi::class)
internal class FastTestHost(kotlinxContext: CoroutineContext = EmptyCoroutineContext) {
    val eventLoop = FastEventLoop()
    val scheduler = FastVirtualTimeScheduler()
    val unhandled = mutableListOf<Throwable>()
    var framesRequested = 0

    val host =
        FastCoroutineHost(
            handoff = eventLoop,
            kotlinxContext = kotlinxContext,
            scheduler = scheduler,
            onUnhandledException = { unhandled.add(it) },
        )

    val clock = FastFrameClock(host) { framesRequested++ }

    val scope = host.createScope()

    /** Launches a restricted coroutine — the prototype's only flavour. */
    fun launch(block: suspend FastRestrictedScope.() -> Unit): FastJob = scope.launch(clock, block)

    fun frame(timeNanos: Long) = clock.sendFrame(timeNanos)
}

@OptIn(InternalComposeApi::class)
class FastCoroutinesTest {

    @Test
    fun launchRunsUndispatchedUntilFirstSuspension() {
        val fixture = FastTestHost()
        var reachedBody = false
        var reachedAfterFrame = false
        fixture.launch {
            reachedBody = true
            withFrameNanos { reachedAfterFrame = true }
        }
        // No dispatch: the body already ran up to its first suspension.
        assertTrue(reachedBody)
        assertFalse(reachedAfterFrame)
        assertEquals(1, fixture.framesRequested)

        fixture.frame(1_000L)
        assertTrue(reachedAfterFrame)
    }

    @Test
    fun launchWithoutSuspensionCompletesImmediately() {
        val fixture = FastTestHost()
        var ran = false
        val job = fixture.launch { ran = true }
        assertTrue(ran)
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        // Nothing ever suspended, so no continuation was created.
        assertEquals(0, fixture.host.stats.fastSuspensions)
    }

    @Test
    fun suspensionResumedInsideBlockDoesNotSuspend() {
        val fixture = FastTestHost()
        var result = 0
        fixture.launch {
            result = suspendCancellable<Int> { continuation -> continuation.resume(42) }
        }
        assertEquals(42, result)
        assertEquals(1, fixture.host.stats.syncCompletions)
        assertEquals(0, fixture.host.stats.fastSuspensions)
    }

    @Test
    fun frameClockUsesFastPathAndNeverTouchesFallback() {
        val fixture = FastTestHost()
        val frames = mutableListOf<Long>()
        fixture.launch { repeat(3) { withFrameNanos { frames.add(it) } } }

        fixture.frame(1L)
        fixture.frame(2L)
        fixture.frame(3L)

        assertEquals(listOf(1L, 2L, 3L), frames)
        assertEquals(3, fixture.host.stats.fastSuspensions)
        assertEquals(0, fixture.host.stats.fallbackSuspensions)
        assertFalse(fixture.clock.isShared)
    }

    @Test
    fun allFrameCallbacksRunBeforeAnyBodyResumes() {
        val fixture = FastTestHost()
        val order = mutableListOf<String>()
        fixture.launch {
            withFrameNanos { order.add("onFrame1") }
            order.add("body1")
        }
        fixture.launch {
            withFrameNanos { order.add("onFrame2") }
            order.add("body2")
        }

        fixture.frame(1L)

        assertEquals(listOf("onFrame1", "onFrame2", "body1", "body2"), order)
    }

    @Test
    fun reAwaitFromOnFrameLandsInNextFrame() {
        val fixture = FastTestHost()
        val frames = mutableListOf<Long>()
        fixture.launch {
            while (true) {
                withFrameNanos { frames.add(it) }
            }
        }

        fixture.frame(1L)
        assertEquals(listOf(1L), frames)
        fixture.frame(2L)
        assertEquals(listOf(1L, 2L), frames)
    }

    @Test
    fun frameRequestedOnlyForFirstAwaiter() {
        val fixture = FastTestHost()
        fixture.launch { withFrameNanos {} }
        fixture.launch { withFrameNanos {} }
        assertEquals(1, fixture.framesRequested)

        fixture.frame(1L)
        fixture.launch { withFrameNanos {} }
        assertEquals(2, fixture.framesRequested)
    }

    @Test
    fun cancellationRetiresBodyWithoutThrowingIntoIt() {
        val fixture = FastTestHost()
        var inFinally = false
        var caught: Throwable? = null
        var cleanedUp = false
        val job =
            fixture.launch {
                onCleanup { cleanedUp = true }
                try {
                    withFrameNanos {}
                } catch (e: Throwable) {
                    caught = e
                } finally {
                    inFinally = true
                }
            }

        job.cancel()

        // The defining property of this runtime: cancelling creates no exception and throws nothing
        // into the body, so neither the catch nor the finally runs. Cleanup comes from onCleanup.
        assertFalse(inFinally)
        assertNull(caught)
        assertTrue(cleanedUp)
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertTrue(fixture.unhandled.isEmpty())
    }

    @Test
    fun cancelledAwaiterIsNotResumedByLaterFrames() {
        val fixture = FastTestHost()
        var frameCount = 0
        val job = fixture.launch { repeat(10) { withFrameNanos { frameCount++ } } }

        fixture.frame(1L)
        assertEquals(1, frameCount)

        job.cancel()
        fixture.frame(2L)
        fixture.frame(3L)

        assertEquals(1, frameCount)
    }

    @Test
    fun cancellingScopeCancelsAllChildren() {
        val fixture = FastTestHost()
        val cancelled = mutableListOf<Int>()
        repeat(3) { index ->
            fixture.launch {
                onCleanup { cancelled.add(index) }
                while (true) withFrameNanos {}
            }
        }

        fixture.frame(1L)
        fixture.scope.cancel()

        assertEquals(listOf(0, 1, 2), cancelled.sorted())
        assertFalse(fixture.clock.hasAwaiters)
    }

    @Test
    fun cancellationBeforeFirstFrameRetiresTheBody() {
        val fixture = FastTestHost()
        var cleanedUp = false
        var pastSuspension = false
        val job =
            fixture.launch {
                onCleanup { cleanedUp = true }
                withFrameNanos {}
                pastSuspension = true
            }
        job.cancel()
        assertTrue(cleanedUp)
        assertFalse(pastSuspension)
        assertTrue(job.isCancelled)
    }

    @Test
    fun launchOnCancelledScopeDoesNotRunPastFirstSuspension() {
        val fixture = FastTestHost()
        fixture.scope.cancel()
        var pastSuspension = false
        var cleanedUp = false
        val job =
            fixture.launch {
                onCleanup { cleanedUp = true }
                withFrameNanos {}
                pastSuspension = true
            }
        // The body ran up to its first suspension, which is refused because the scope is already
        // cancelled: it parks there for good rather than being thrown into.
        assertFalse(pastSuspension)
        assertTrue(cleanedUp)
        assertTrue(job.isCancelled)
    }

    @Test
    fun bodyFailurePropagatesToUnhandledHandler() {
        val fixture = FastTestHost()
        val failure = RuntimeException("boom")
        val job = fixture.launch { throw failure }

        assertTrue(job.isCompleted)
        assertEquals(listOf<Throwable>(failure), fixture.unhandled)
    }

    @Test
    fun failureAfterSuspensionCancelsSiblings() {
        val fixture = FastTestHost()
        var siblingUnwound = false
        fixture.launch {
            onCleanup { siblingUnwound = true }
            while (true) withFrameNanos {}
        }
        fixture.launch {
            withFrameNanos {}
            throw RuntimeException("boom")
        }

        fixture.frame(1L)

        assertTrue(siblingUnwound)
        assertEquals(1, fixture.unhandled.size)
        assertEquals("boom", fixture.unhandled.single().message)
    }

    @Test
    fun jobJoinResumesOnCompletion() {
        val fixture = FastTestHost()
        var joined = false
        val worker = fixture.launch { withFrameNanos {} }
        fixture.launch {
            join(worker)
            joined = true
        }
        assertFalse(joined)

        fixture.frame(1L)

        assertTrue(joined)
    }

    @Test
    fun cancellingAParentAlsoRetiresItsChildren() {
        val fixture = FastTestHost()
        var childCleanedUp = false
        val parent =
            fixture.launch {
                launch {
                    onCleanup { childCleanedUp = true }
                    while (true) withFrameNanos {}
                }
                withFrameNanos {}
            }

        fixture.frame(1L)
        parent.cancel()

        assertTrue(childCleanedUp)
        assertTrue(parent.isCompleted)
        assertFalse(fixture.clock.hasAwaiters)
    }

    /** Stand-in for a hot emitter: hands out subscriptions that must be disposed. */
    private class TestEmitter {
        val liveSubscriptions = mutableListOf<(Int) -> Unit>()

        fun subscribe(onValue: (Int) -> Unit): FastDisposableHandle {
            liveSubscriptions.add(onValue)
            return FastDisposableHandle { liveSubscriptions.remove(onValue) }
        }

        fun emit(value: Int) {
            // Iterate a copy: a listener may unsubscribe while being notified.
            for (listener in liveSubscriptions.toList()) listener(value)
        }
    }

    @Test
    fun cancellationUnsubscribesFromHotEmitter() {
        val fixture = FastTestHost()
        val emitter = TestEmitter()
        val received = mutableListOf<Int>()

        val job =
            fixture.launch {
                val subscription = emitter.subscribe { received.add(it) }
                onCleanup { subscription.dispose() }
                awaitCancellation()
            }

        emitter.emit(1)
        assertEquals(listOf(1), received)
        assertEquals(1, emitter.liveSubscriptions.size)

        job.cancel()

        // The body was never resumed, but the subscription is gone: cleanup does not need the
        // unwind.
        assertEquals(0, emitter.liveSubscriptions.size)
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertTrue(fixture.unhandled.isEmpty())

        emitter.emit(2)
        assertEquals(listOf(1), received)
    }

    @Test
    fun suspensionLevelCleanupSurvivesCancellation() {
        val fixture = FastTestHost()
        val emitter = TestEmitter()
        var received = -1

        // The resource is held by the suspension itself and released from its cancellation handler,
        // which runs in both cancellation modes.
        val job =
            fixture.launch {
                received =
                    suspendCancellable<Int> { continuation ->
                        val subscription = emitter.subscribe { continuation.resume(it) }
                        continuation.invokeOnCancellation { subscription.dispose() }
                    }
            }

        assertEquals(1, emitter.liveSubscriptions.size)
        job.cancel()

        assertEquals(0, emitter.liveSubscriptions.size)
        assertEquals(-1, received)
    }

    @Test
    fun cleanupsRunInReverseOrderExactlyOnce() {
        val fixture = FastTestHost()
        val order = mutableListOf<String>()

        val job =
            fixture.launch {
                onCleanup { order.add("first") }
                onCleanup { order.add("second") }
                onCleanup { order.add("third") }
                awaitCancellation()
            }

        job.cancel()
        job.cancel()

        assertEquals(listOf("third", "second", "first"), order)
    }

    @Test
    fun cleanupAlsoRunsOnNormalCompletion() {
        val fixture = FastTestHost()
        val cleanups = mutableListOf<String>()

        fixture.launch {
            onCleanup { cleanups.add("completed-normally") }
            withFrameNanos {}
        }
        val cancelled =
            fixture.launch {
                onCleanup { cleanups.add("cancelled-while-unwinding") }
                awaitCancellation()
            }

        fixture.frame(1L)
        assertEquals(listOf("completed-normally"), cleanups)

        cancelled.cancel()
        assertEquals(listOf("completed-normally", "cancelled-while-unwinding"), cleanups)
    }

    @Test
    fun failingCleanupDoesNotStrandTheOthers() {
        val fixture = FastTestHost()
        val cleanups = mutableListOf<String>()
        val failure = RuntimeException("cleanup failed")

        val job =
            fixture.launch {
                onCleanup { cleanups.add("outer") }
                onCleanup { throw failure }
                onCleanup { cleanups.add("inner") }
                awaitCancellation()
            }

        job.cancel()

        assertEquals(listOf("inner", "outer"), cleanups)
        assertEquals(listOf<Throwable>(failure), fixture.unhandled)
    }

    // endregion

    @Test
    fun statsShowNoKotlinxUsageForPureFastWorkload() {
        val fixture = FastTestHost()
        fixture.launch { repeat(60) { withFrameNanos {} } }
        repeat(60) { fixture.frame(it.toLong()) }

        val stats = fixture.host.stats
        assertEquals(60, stats.fastSuspensions)
        assertEquals(0, stats.fallbackSuspensions)
        assertEquals(0, stats.kotlinxBridgeCrossings)
        assertEquals(0, stats.crossThreadHandoffs)
        assertEquals(0, stats.promotions)
    }
}
