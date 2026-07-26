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

package androidx.compose.runtime.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeatedOnMainThread
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.internal.coroutines.FastCoroutinesPrototype
import androidx.compose.runtime.withFrameNanos
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A/B benchmarks for the single-threaded coroutine prototype in
 * `androidx.compose.runtime.internal.coroutines` against kotlinx.coroutines, on the paths Compose
 * actually suspends on.
 *
 * Two workloads:
 * 1. **launch + cancellable suspension + cancel** — the cost of starting a coroutine that parks on a
 *    resumable callback and then tearing it down, which is what a gesture, a `LaunchedEffect` or a
 *    one-shot animation does on every interaction.
 * 2. **frame resume** — the steady state of a running animation: N coroutines awaiting
 *    `withFrameNanos`, resumed and immediately re-suspended once per frame.
 *
 * Fairness notes: the kotlinx frame workload uses `Dispatchers.Main.immediate` so that its resumes
 * happen inside the measured `sendFrame` call rather than in a later looper message. That is the best
 * case for kotlinx — a real `AndroidUiDispatcher` adds a trampolined dispatch per resume that this
 * measurement does not charge it for.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeApi::class)
class FastCoroutinesBenchmark {

    @get:Rule val benchmarkRule = BenchmarkRule()

    /** Holds the resume callback of the currently parked coroutine, as a real awaiter queue would. */
    private var field: (() -> Unit)? = null

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    // region launch + suspend + cancel

    @Test
    fun coroutine() = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                job.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fastCoroutine() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        onMainThread { prototype = FastCoroutinesPrototype() }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job =
                    prototype.launch {
                        suspendCancellable<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                job.cancel()
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    // endregion

    // region launch and cancel measured separately, to see where the time goes

    @Test
    fun coroutineLaunchOnly() = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                runWithMeasurementDisabled { job.cancel() }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fastCoroutineLaunchOnly() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        onMainThread { prototype = FastCoroutinesPrototype() }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job =
                    prototype.launch {
                        suspendCancellable<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                runWithMeasurementDisabled { job.cancel() }
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    @Test
    fun coroutineCancelOnly() = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job = runWithMeasurementDisabled {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                }
                job.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fastCoroutineCancelOnly() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        onMainThread { prototype = FastCoroutinesPrototype() }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job = runWithMeasurementDisabled {
                    prototype.launch {
                        suspendCancellable<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                }
                job.cancel()
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    /**
     * The fast counterpart of [coroutineCancelOnlySubscribed]: a coroutine holding a subscription is
     * cancelled. The release runs from its registered cleanup and the body is never resumed, so no
     * exception is created, thrown or caught anywhere on this path.
     */
    @Test
    fun fastCoroutineCancelOnlySubscribed() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        onMainThread { prototype = FastCoroutinesPrototype() }
        var subscriptions = 0
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job = runWithMeasurementDisabled {
                    prototype.launch {
                        subscriptions++
                        onCleanup { subscriptions-- }
                        suspendCancellable<Unit> { cont ->
                            field = { cont.resume(Unit) }
                            cont.invokeOnCancellation { field = null }
                        }
                    }
                }
                job.cancel()
            }
            // The subscription really was released on every iteration.
            assertEquals(0, subscriptions)
        } finally {
            onMainThread { prototype.cancel() }
        }
    }
    @Test
    fun coroutineCancelOnlySubscribed() = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        var subscriptions = 0
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                val job = runWithMeasurementDisabled {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        subscriptions++
                        try {
                            suspendCancellableCoroutine<Unit> { cont ->
                                field = { cont.resume(Unit) }
                                cont.invokeOnCancellation { field = null }
                            }
                        } finally {
                            subscriptions--
                        }
                    }
                }
                job.cancel()
            }
            assertEquals(0, subscriptions)
        } finally {
            scope.cancel()
        }
    }

    // endregion

    // region resume a parked coroutine (no cancellation)

    @Test
    fun coroutineResume() = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        field = { cont.resume(Unit) }
                        cont.invokeOnCancellation { field = null }
                    }
                }
                field!!.invoke()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fastCoroutineResume() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        onMainThread { prototype = FastCoroutinesPrototype() }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                prototype.launch {
                    suspendCancellable<Unit> { cont ->
                        field = { cont.resume(Unit) }
                        cont.invokeOnCancellation { field = null }
                    }
                }
                field!!.invoke()
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    // endregion

    // region frame dispatch, i.e. the animation steady state

    private fun measureKotlinxFrames(awaiters: Int) {
        val clock = BroadcastFrameClock()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + clock + job)
        var frameTime = 0L
        try {
            onMainThread {
                repeat(awaiters) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        while (true) {
                            withFrameNanos { /* sample an animation value */ }
                        }
                    }
                }
            }
            benchmarkRule.measureRepeatedOnMainThread {
                frameTime += 16_666_666L
                clock.sendFrame(frameTime)
            }
        } finally {
            job.cancel()
        }
    }

    private fun measureFastFrames(awaiters: Int) {
        lateinit var prototype: FastCoroutinesPrototype
        var frameTime = 0L
        onMainThread {
            prototype = FastCoroutinesPrototype()
            repeat(awaiters) {
                prototype.launch {
                    while (true) {
                        withFrameNanos { /* sample an animation value */ }
                    }
                }
            }
        }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                frameTime += 16_666_666L
                prototype.sendFrame(frameTime)
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    @Test fun withFrameNanos_1_awaiter() = measureKotlinxFrames(1)

    @Test fun withFrameNanos_1_awaiter_fast() = measureFastFrames(1)

    @Test fun withFrameNanos_16_awaiters() = measureKotlinxFrames(16)

    @Test fun withFrameNanos_16_awaiters_fast() = measureFastFrames(16)

    // endregion
}
