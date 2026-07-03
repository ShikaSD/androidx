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
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.launchComposeCoroutine
import androidx.compose.runtime.suspendComposeCancellableCoroutine
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@LargeTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class, InternalComposeApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ComposeCoroutineBenchmark {
    @get:Rule val benchmarkRule = BenchmarkRule()

    private var field: (() -> Unit)? = null

    @Test
    fun coroutine() = runBlockingTestWithFrameClock {
        benchmarkRule.measureRepeatedOnMainThread {
            val job =
                launch {
                    suspendCancellableCoroutine { cont ->
                        field = { cont.resume(Unit) }
                        cont.invokeOnCancellation { field = null }
                    }
                }
            job.cancel()
        }
    }

    @Test
    fun coroutine_slop() = runBlockingTestWithFrameClock {
        benchmarkRule.measureRepeatedOnMainThread {
            val job =
                launch {
                    suspendComposeCancellableCoroutine { cont ->
                        field = { cont.resume(Unit) }
                        cont.invokeOnCancellation { field = null }
                    }
                }
            job.cancel()
        }
    }

    @Test
    fun composeCoroutine() = runBlockingTestWithFrameClock {
        benchmarkRule.measureRepeatedOnMainThread {
            val job =
                launchComposeCoroutine(coroutineContext) {
                    suspendComposeCancellableCoroutine { cont ->
                        field = { cont.resume(Unit) }
                        cont.invokeOnCancellation { field = null }
                    }
                }
            job.cancel()
        }
    }
}
