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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.internal.coroutines.FastCancellableJob
import androidx.compose.runtime.internal.coroutines.FastCoroutinesPrototype
import androidx.compose.runtime.internal.coroutines.FastRestrictedScope
import androidx.compose.runtime.internal.coroutines.FastMutator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks of two coroutine patterns taken from the codebase, run on kotlinx.coroutines and on the
 * fast prototype.
 *
 * The patterns:
 * 1. **Retarget**, from `AnimateAsState.restartAnimation`: while an animation is in flight, the target
 *    changes, so the running job is cancelled and a new one launched undispatched. During a gesture
 *    this happens on every frame. What makes it interesting is depth — the cancelled coroutine is parked
 *    eight suspend frames down (`animateTo` → `runAnimation` → `mutate` → `coroutineScope` → `withLock`
 *    → animation loop → frame await → `withFrameNanos`) and the cancellation exception is thrown and
 *    caught at each one.
 * 2. **Fade retrigger**, from `NonInteractiveScrollbarNode.draw`: on every scroll frame the pending
 *    fade-out is cancelled and relaunched as `snapTo(1f)`, `delay(fadeDelay)`, `animateTo(0f)`. Here the
 *    cancelled coroutine is parked in a *delay*, and each relaunch performs an uncontended mutation.
 *
 * Both sides are mimics rather than the real `Animatable`: identical easing math, identical snapshot
 * state writes, and the same suspend nesting, so the difference measured is the coroutine runtime and
 * not the animation implementation. The one deliberate asymmetry is that the fast side has no
 * `coroutineScope` frame inside `mutate` — `MutatorMutex` needs it to obtain its `Job`, while
 * `FastMutatorMutex` reads the job from the context element. That is a real property of the design, not
 * a measurement artifact.
 *
 * Delays never fire in these benchmarks: like the real scrollbar, each iteration cancels the previous
 * fade long before its delay elapses, so nothing sleeps.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class, InternalComposeApi::class)
class FastCoroutinesPatternBenchmark {

    @get:Rule val benchmarkRule = BenchmarkRule()

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val AnimationDurationMillis = 300f
        const val FadeDelayMillis = 500L
        const val AnimationCount = 8

        /** Shared easing math, so both sides compute exactly the same values. */
        fun sample(start: Float, target: Float, elapsedMillis: Float, durationMillis: Float): Float {
            val fraction = (elapsedMillis / durationMillis).coerceIn(0f, 1f)
            return start + (target - start) * fraction
        }

        /**
         * Duration for the steady-state benchmarks. The animation must not finish while the
         * benchmark runs, or `sendFrame` ends up measuring an empty awaiter list.
         */
        const val NeverFinishesDurationMillis = 1e9f
    }

    // region kotlinx.coroutines mimic

    /** Mirrors animation-core's internal `MutatorMutex`. */
    private class MimicMutatorMutex {
        private class Mutator(val priority: Int, val job: Job) {
            fun canInterrupt(other: Mutator) = priority >= other.priority

            fun cancel() = job.cancel()
        }

        private var currentMutator: Mutator? = null
        private val mutex = Mutex()

        private fun tryMutateOrCancel(mutator: Mutator) {
            val old = currentMutator
            if (old == null || mutator.canInterrupt(old)) {
                currentMutator = mutator
                old?.cancel()
            } else {
                throw kotlinx.coroutines.CancellationException("higher priority mutation")
            }
        }

        suspend fun <R> mutate(priority: Int = 0, block: suspend () -> R): R = coroutineScope {
            val mutator = Mutator(priority, requireNotNull(coroutineContext[Job]))
            tryMutateOrCancel(mutator)
            mutex.withLock {
                try {
                    block()
                } finally {
                    if (currentMutator === mutator) currentMutator = null
                }
            }
        }
    }

    /** Mirrors `Animatable`: snapshot-backed value guarded by a mutator mutex. */
    private class MimicAnimatable(initial: Float) {
        var value by mutableFloatStateOf(initial)
            private set

        private val mutex = MimicMutatorMutex()

        suspend fun snapTo(target: Float) = mutex.mutate { value = target }

        suspend fun animateTo(target: Float, durationMillis: Float = AnimationDurationMillis) =
            runAnimation(target, durationMillis)

        private suspend fun runAnimation(target: Float, durationMillis: Float) =
            mutex.mutate {
                val start = value
                animationLoop(start, target, durationMillis)
            }

        private suspend fun animationLoop(start: Float, target: Float, durationMillis: Float) {
            var startTimeNanos = -1L
            while (true) {
                var finished = false
                awaitFrame { frameTimeNanos ->
                    if (startTimeNanos < 0L) startTimeNanos = frameTimeNanos
                    val elapsedMillis = (frameTimeNanos - startTimeNanos) / 1_000_000f
                    value = sample(start, target, elapsedMillis, durationMillis)
                    finished = elapsedMillis >= durationMillis
                }
                if (finished) break
            }
        }

        /** Stands in for `callWithFrameNanos`, keeping the suspend depth the same as the original. */
        private suspend fun awaitFrame(onFrame: (Long) -> Unit) {
            withFrameNanos(onFrame)
        }
    }

    // endregion

    // region restricted-scope mimic: same code shape, statically confined to the fast runtime

    /**
     * The same mimic again, but its suspending parts are extensions on [FastRestrictedScope]. The
     * animation body is character-for-character the loop the other two mimics use — `while (…)
     * withFrameNanos { … }` — except that `withFrameNanos` resolves to the scope's member, so no
     * suspension looks anything up in the `CoroutineContext`, and the compiler rejects any call into
     * kotlinx.coroutines from in here.
     */
    private class RestrictedMimicAnimatable(initial: Float, val mutator: FastMutator) {
        var value by mutableFloatStateOf(initial)
    }

    private suspend fun FastRestrictedScope.snapTo(anim: RestrictedMimicAnimatable, target: Float) =
        mutate(anim.mutator) { anim.value = target }

    private suspend fun FastRestrictedScope.animateTo(
        anim: RestrictedMimicAnimatable,
        target: Float,
        durationMillis: Float = AnimationDurationMillis,
    ) = runAnimation(anim, target, durationMillis)

    private suspend fun FastRestrictedScope.runAnimation(
        anim: RestrictedMimicAnimatable,
        target: Float,
        durationMillis: Float,
    ) = mutate(anim.mutator) { animationLoop(anim, anim.value, target, durationMillis) }

    private suspend fun FastRestrictedScope.animationLoop(
        anim: RestrictedMimicAnimatable,
        start: Float,
        target: Float,
        durationMillis: Float,
    ) {
        var startTimeNanos = -1L
        while (true) {
            var finished = false
            awaitFrame { frameTimeNanos ->
                if (startTimeNanos < 0L) startTimeNanos = frameTimeNanos
                val elapsedMillis = (frameTimeNanos - startTimeNanos) / 1_000_000f
                anim.value = sample(start, target, elapsedMillis, durationMillis)
                finished = elapsedMillis >= durationMillis
            }
            if (finished) break
        }
    }

    private suspend fun FastRestrictedScope.awaitFrame(onFrame: (Long) -> Unit) {
        withFrameNanos(onFrame)
    }

    // endregion

    // region pattern 1: retarget an in-flight animation (AnimateAsState.restartAnimation)

    @Test
    fun retarget() = runTest {
        val clock = BroadcastFrameClock()
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + clock + parent)
        var frameTime = 0L
        var target = 0f
        try {
            lateinit var animatable: MimicAnimatable
            var job: Job? = null
            onMainThread {
                animatable = MimicAnimatable(0f)
                job = scope.launch(start = CoroutineStart.UNDISPATCHED) { animatable.animateTo(1f) }
            }
            benchmarkRule.measureRepeatedOnMainThread {
                job?.cancel()
                target += 1f
                job =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.animateTo(target)
                    }
                runWithMeasurementDisabled {
                    // Advance the animation one frame, as a real retarget-per-frame loop would.
                    frameTime += 16_666_666L
                    clock.sendFrame(frameTime)
                }
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun retargetFast() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        lateinit var animatable: RestrictedMimicAnimatable
        var frameTime = 0L
        var target = 0f
        var job: FastCancellableJob? = null
        onMainThread {
            prototype = FastCoroutinesPrototype()
            animatable = RestrictedMimicAnimatable(0f, prototype.newMutator())
            job = prototype.launch { animateTo(animatable, 1f) }
        }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                job?.cancel()
                target += 1f
                job =
                    prototype.launch { animateTo(animatable, target) }
                runWithMeasurementDisabled {
                    frameTime += 16_666_666L
                    prototype.sendFrame(frameTime)
                }
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    // endregion

    // region pattern 1b: steady state, N animations advanced by one frame

    @Test
    fun animationFrames() = runTest {
        val clock = BroadcastFrameClock()
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + clock + parent)
        var frameTime = 0L
        try {
            onMainThread {
                repeat(AnimationCount) {
                    val animatable = MimicAnimatable(0f)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.animateTo(1f, NeverFinishesDurationMillis)
                    }
                }
            }
            benchmarkRule.measureRepeatedOnMainThread {
                frameTime += 16_666_666L
                clock.sendFrame(frameTime)
            }
        } finally {
            parent.cancel()
        }
    }
    @Test
    fun animationFramesFast() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        var frameTime = 0L
        onMainThread {
            prototype = FastCoroutinesPrototype()
            repeat(AnimationCount) {
                val animatable = RestrictedMimicAnimatable(0f, prototype.newMutator())
                prototype.launch {
                    animateTo(animatable, 1f, NeverFinishesDurationMillis)
                }
            }
        }
        try {
            org.junit.Assert.assertTrue(prototype.hasFrameAwaiters)
            benchmarkRule.measureRepeatedOnMainThread {
                frameTime += 16_666_666L
                prototype.sendFrame(frameTime)
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    // endregion

    // region pattern 2: scrollbar fade retriggered on every scroll frame

    @Test
    fun fadeRetrigger() = runTest {
        val clock = BroadcastFrameClock()
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + clock + parent)
        try {
            lateinit var alpha: MimicAnimatable
            var fadeJob: Job? = null
            onMainThread { alpha = MimicAnimatable(0f) }
            benchmarkRule.measureRepeatedOnMainThread {
                fadeJob?.cancel()
                fadeJob =
                    scope.launch {
                        if (alpha.value != 1f) alpha.snapTo(1f)
                        delay(FadeDelayMillis)
                        alpha.animateTo(0f)
                    }
            }
        } finally {
            parent.cancel()
        }
    }
    @Test
    fun fadeRetriggerFast() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        lateinit var alpha: RestrictedMimicAnimatable
        var fadeJob: FastCancellableJob? = null
        onMainThread {
            prototype = FastCoroutinesPrototype()
            alpha = RestrictedMimicAnimatable(0f, prototype.newMutator())
        }
        try {
            benchmarkRule.measureRepeatedOnMainThread {
                fadeJob?.cancel()
                fadeJob =
                    prototype.launch {
                        if (alpha.value != 1f) snapTo(alpha, 1f)
                        delay(FadeDelayMillis)
                        animateTo(alpha, 0f)
                    }
            }
            onMainThread { fadeJob?.cancel() }
            org.junit.Assert.assertEquals(0, prototype.pendingDelayCount())
        } finally {
            onMainThread { prototype.cancel() }
        }
    }

    private fun runWithMeasurementDisabledOnMain(block: () -> Unit) = onMainThread(block)

    // endregion

    // region uncontended snapTo, the cheapest and most frequent mutation

    @Test
    fun snapToUncontended() = runTest {
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + parent)
        try {
            lateinit var animatable: MimicAnimatable
            onMainThread { animatable = MimicAnimatable(0f) }
            var value = 0f
            benchmarkRule.measureRepeatedOnMainThread {
                value += 1f
                scope.launch(start = CoroutineStart.UNDISPATCHED) { animatable.snapTo(value) }
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun snapToUncontendedFast() = runTest {
        lateinit var prototype: FastCoroutinesPrototype
        lateinit var animatable: RestrictedMimicAnimatable
        onMainThread {
            prototype = FastCoroutinesPrototype()
            animatable = RestrictedMimicAnimatable(0f, prototype.newMutator())
        }
        try {
            var value = 0f
            benchmarkRule.measureRepeatedOnMainThread {
                value += 1f
                prototype.launch { snapTo(animatable, value) }
            }
        } finally {
            onMainThread { prototype.cancel() }
        }
    }
    // endregion
}
