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

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.withFrameNanos
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A/B comparison of the same frame-driven animation workload on kotlinx.coroutines and on the fast
 * primitives.
 *
 * The workload deliberately mirrors what `animation-core` does in `AnimationState.animate`: a
 * coroutine that awaits `withFrameNanos`, samples an animation value from the frame time, writes it
 * somewhere, and immediately awaits the next frame — that loop being the steady state of every
 * running animation, `Transition` child and fling.
 *
 * This is a rough in-test harness, not JMH: it reports wall-clock time per frame-resume and
 * allocation counts are not measured. Numbers are for orienting a prototype; a real evaluation
 * belongs in `compose/benchmark-utils` with proper warmup and isolation. It is kept as a `@Test` so
 * it runs (and keeps compiling) in CI, with generous thresholds.
 */
@OptIn(InternalComposeApi::class)
class FastCoroutinesFrameLoopBenchmark {

    private companion object {
        const val ANIMATIONS = 16
        const val FRAMES = 2_000
        const val WARMUP_FRAMES = 3_000
    }

    /** Single-threaded dispatcher that mimics a UI dispatcher's trampolining behaviour. */
    private class TrampolineDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private var dispatching = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
            if (dispatching) return
            dispatching = true
            try {
                while (true) {
                    val next = queue.removeFirstOrNull() ?: break
                    next.run()
                }
            } finally {
                dispatching = false
            }
        }

        fun drain() {
            dispatch(EmptyCoroutineContext, Runnable {})
        }
    }

    private fun sampleAnimation(frameTimeNanos: Long, startTimeNanos: Long): Float {
        val elapsed = (frameTimeNanos - startTimeNanos) / 1_000_000f
        return elapsed / (elapsed + 100f)
    }

    private fun runKotlinxWorkload(frames: Int): Pair<Long, Int> {
        val dispatcher = TrampolineDispatcher()
        val clock = BroadcastFrameClock()
        val job = Job()
        val scope = CoroutineScope(dispatcher + clock + job)
        var samples = 0
        var sink = 0f

        repeat(ANIMATIONS) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var startTime = -1L
                while (true) {
                    withFrameNanos { frameTime ->
                        if (startTime < 0) startTime = frameTime
                        sink += sampleAnimation(frameTime, startTime)
                        samples++
                    }
                }
            }
        }
        dispatcher.drain()

        val start = System.nanoTime()
        repeat(frames) { frame ->
            clock.sendFrame(frame * 16_666_666L)
            dispatcher.drain()
        }
        val elapsed = System.nanoTime() - start

        scope.cancel()
        dispatcher.drain()
        assertTrue(sink >= 0f) // keep the computation alive
        return elapsed to samples
    }

    private fun runFastWorkload(frames: Int): Triple<Long, Int, FastCoroutineStats> {
        val host = FastCoroutineHost(FastEventLoop())
        val clock = FastFrameClock(host)
        val scope = host.createScope()
        var samples = 0
        var sink = 0f

        repeat(ANIMATIONS) {
            scope.launch(clock) {
                var startTime = -1L
                while (true) {
                    withFrameNanos { frameTime ->
                        if (startTime < 0) startTime = frameTime
                        sink += sampleAnimation(frameTime, startTime)
                        samples++
                    }
                }
            }
        }

        val start = System.nanoTime()
        repeat(frames) { frame -> clock.sendFrame(frame * 16_666_666L) }
        val elapsed = System.nanoTime() - start

        scope.cancel()
        assertTrue(sink >= 0f)
        return Triple(elapsed, samples, host.stats)
    }

    @Test
    fun fastFrameLoopMatchesKotlinxBehaviourAndReportsTiming() {
        // Warmup: let the JIT see both loops before timing anything.
        runKotlinxWorkload(WARMUP_FRAMES)
        runFastWorkload(WARMUP_FRAMES)

        val (kotlinxNanos, kotlinxSamples) = runKotlinxWorkload(FRAMES)
        val (fastNanos, fastSamples, stats) = runFastWorkload(FRAMES)

        // Same observable work in both cases: every animation sampled on every frame.
        assertEquals(ANIMATIONS * FRAMES, fastSamples)
        assertEquals(fastSamples, kotlinxSamples)

        // And the fast run genuinely never reached kotlinx.coroutines.
        assertEquals(0, stats.fallbackSuspensions)
        assertEquals(0, stats.kotlinxBridgeCrossings)
        assertEquals(0, stats.crossThreadHandoffs)
        // One suspension per delivered frame, plus the await each animation is parked on at the
        // end.
        assertEquals(ANIMATIONS * (FRAMES + 1), stats.fastSuspensions)

        val resumes = (ANIMATIONS * FRAMES).toDouble()
        println(
            buildString {
                appendLine("frame-loop benchmark: $ANIMATIONS animations x $FRAMES frames")
                appendLine(
                    "  kotlinx.coroutines: ${kotlinxNanos / 1_000_000} ms total, " +
                        "${"%.1f".format(kotlinxNanos / resumes)} ns/resume"
                )
                appendLine(
                    "  fast coroutines:    ${fastNanos / 1_000_000} ms total, " +
                        "${"%.1f".format(fastNanos / resumes)} ns/resume"
                )
                appendLine(
                    "  ratio: ${"%.2f".format(kotlinxNanos.toDouble() / fastNanos.toDouble())}x"
                )
                append("  $stats")
            }
        )
    }
}
