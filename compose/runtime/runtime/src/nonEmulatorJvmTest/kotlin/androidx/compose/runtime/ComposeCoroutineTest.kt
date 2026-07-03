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

package androidx.compose.runtime

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

@OptIn(InternalComposeApi::class)
class ComposeCoroutineTest {

    @Test
    fun launchStartsOnCallingThread() = runBlocking {
        val threadId = Thread.currentThread().id
        var startedThreadId = -1L

        val job =
            launchComposeCoroutine(coroutineContext) { startedThreadId = Thread.currentThread().id }

        job.join()
        assertEquals(threadId, startedThreadId)
    }

    @Test
    fun launchReturnsComposeHandle() = runBlocking {
        val handle = launchComposeCoroutine(coroutineContext) {}

        handle.join()

        assertFalse(handle is Job)
    }

    @Test
    fun cancellationRunsFinallyBlock() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var ranFinally = false
        val job =
            launchComposeCoroutine(coroutineContext) {
                try {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE)
                } finally {
                    ranFinally = true
                }
            }

        started.await()
        job.cancel()
        job.join()

        assertTrue(ranFinally)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun resumesFromOtherThreadHandOffToOriginalDispatcher() {
        val dispatcher = newSingleThreadContext("ComposeCoroutineTest")
        try {
            runBlocking(dispatcher) {
                val ownerThreadId = Thread.currentThread().id
                var callbackThreadId = -1L
                var resumedThreadId = -1L

                val job =
                    launchComposeCoroutine(coroutineContext) {
                        callbackThreadId = suspendCoroutine { continuation ->
                            Thread { continuation.resume(Thread.currentThread().id) }.start()
                        }
                        resumedThreadId = Thread.currentThread().id
                    }

                job.join()
                assertTrue(callbackThreadId != ownerThreadId)
                assertEquals(ownerThreadId, resumedThreadId)
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun parentCancellationCancelsChildJob() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val parent = Job(coroutineContext.job)
        var ranFinally = false
        val job =
            launchComposeCoroutine(coroutineContext + parent) {
                try {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE)
                } finally {
                    ranFinally = true
                }
            }

        started.await()
        parent.cancel()
        job.join()

        assertTrue(ranFinally)
    }

    @Test
    fun cancelledParentDoesNotStartBlock() = runBlocking {
        val parent = Job(coroutineContext.job)
        parent.cancel()
        var started = false

        val job = launchComposeCoroutine(coroutineContext + parent) { started = true }

        job.join()
        assertFalse(started)
        assertTrue(job.isCompleted)
    }
}
