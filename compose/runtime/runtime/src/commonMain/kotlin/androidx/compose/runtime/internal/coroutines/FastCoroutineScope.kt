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
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * Scope for launching fast coroutines, the counterpart of `CoroutineScope`.
 *
 * Coroutines launched here run in a [FastRestrictedScope]: their bodies are statically confined to
 * the fast runtime, and they carry no `CoroutineContext` at all. Entering the fast world from
 * kotlinx.coroutines goes through `withFastCoroutine` or `asFastCoroutineScope`; there is no path
 * in the other direction, because a restricted body cannot call a kotlinx suspend function.
 */
internal class FastCoroutineScope(internal val host: FastCoroutineHost, internal val job: FastJob) {

    /**
     * Starts [block] with a [FastRestrictedScope] receiver: statically confined to the fast
     * runtime, and with the host, job and clock carried as fields instead of looked up per
     * suspension.
     */
    @OptIn(InternalComposeApi::class)
    fun launch(clock: FastFrameClock, block: suspend FastRestrictedScope.() -> Unit): FastJob {
        host.checkOwnerThread()
        val childJob = FastJob(host, job)
        val restrictedScope = FastRestrictedScope(host, childJob, clock)
        // Kotlin requires a restricted-suspension coroutine to run with EmptyCoroutineContext,
        // which
        // is precisely the point: nothing inside reads the context, because the scope carries the
        // host, job and clock. It also means a restricted launch builds no context at all.
        val completion = FastRootContinuation(childJob, EmptyCoroutineContext)
        childJob.bodyStarted()
        val result =
            try {
                block.startCoroutineUninterceptedOrReturn<FastRestrictedScope, Unit>(
                    restrictedScope,
                    completion,
                )
            } catch (e: Throwable) {
                childJob.completeWith(Result.failure(e))
                return childJob
            }
        if (result !== COROUTINE_SUSPENDED) {
            childJob.completeWith(Result.success(Unit))
        }
        return childJob
    }

    /**
     * Like [launch], but reports the body's result to [onComplete]. Used to hand a fast coroutine's
     * result back to a kotlinx.coroutines caller; see `withFastCoroutine`.
     */
    @OptIn(InternalComposeApi::class)
    internal fun <R> launchWithResult(
        clock: FastFrameClock,
        onComplete: (Result<R>) -> Unit,
        block: suspend FastRestrictedScope.() -> R,
    ): FastJob {
        host.checkOwnerThread()
        val childJob = FastJob(host, job, reportsFailureToParent = false)
        val restrictedScope = FastRestrictedScope(host, childJob, clock)
        val completion =
            FastScopeBodyContinuation(childJob, EmptyCoroutineContext) { result ->
                @Suppress("UNCHECKED_CAST") onComplete(result as Result<R>)
            }
        childJob.bodyStarted()
        val result =
            try {
                @Suppress("UNCHECKED_CAST")
                (block as suspend FastRestrictedScope.() -> Any?)
                    .startCoroutineUninterceptedOrReturn<FastRestrictedScope, Any?>(
                        restrictedScope,
                        completion,
                    )
            } catch (e: Throwable) {
                childJob.completeWith(Result.failure(e))
                onComplete(Result.failure(e))
                return childJob
            }
        if (result !== COROUTINE_SUSPENDED) {
            @Suppress("UNCHECKED_CAST") onComplete(Result.success(result as R))
            childJob.completeWith(Result.success(Unit))
        }
        return childJob
    }

    /** Cancels every coroutine launched from this scope. */
    fun cancel() {
        job.cancel()
    }
}

/** Root continuation of a fast coroutine body; reports the body's outcome to its [FastJob]. */
private class FastRootContinuation(
    private val job: FastJob,
    override val context: CoroutineContext,
) : Continuation<Unit> {
    override fun resumeWith(result: Result<Unit>) {
        job.completeWith(result)
    }
}

private class FastScopeBodyContinuation(
    private val job: FastJob,
    override val context: CoroutineContext,
    private val onResult: (Result<Any?>) -> Unit,
) : Continuation<Any?> {
    override fun resumeWith(result: Result<Any?>) {
        onResult(result)
        job.completeWith(result)
    }
}
