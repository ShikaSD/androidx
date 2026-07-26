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
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Interop between fast coroutines and kotlinx.coroutines.
 *
 * Fast coroutines are intentionally *not* kotlinx.coroutines types: a [FastJob] is not a [Job] and
 * a fast coroutine's context carries no `ContinuationInterceptor`. Everything that crosses the
 * boundary therefore does so through one of the functions in this file, and a real [Job] — the
 * "mirror" — is created only at that moment. An app that never leaves the fast world never
 * allocates one.
 *
 * The boundary is one-way, because a [FastRestrictedScope] body cannot call a kotlinx suspend
 * function at all — the compiler rejects it. So there are only two crossings, both *into* the fast
 * world:
 * 1. **A kotlinx coroutine runs fast work** (a `LaunchedEffect` driving an animation):
 *    [withFastCoroutine].
 * 2. **A kotlinx scope owns a fast scope's lifetime**: [asFastCoroutineScope].
 *
 * Work that needs a kotlinx primitive stays on the kotlinx side of that boundary. Awaiting the
 * frame clock from there needs no bridge: [FastFrameClock.withFrameNanos] serves kotlinx callers
 * from a `BroadcastFrameClock`, so a mixed app keeps working with no changes.
 */

/**
 * [FastHandoffDispatcher] backed by a kotlinx [CoroutineDispatcher] that runs on the owner thread.
 */
internal class KotlinxHandoffDispatcher(private val dispatcher: CoroutineDispatcher) :
    FastHandoffDispatcher {
    override fun schedule(task: FastTask) {
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { task.execute() })
    }
}

/**
 * Runs [block] as a fast coroutine from inside a kotlinx.coroutines coroutine, suspending the
 * caller until it completes. This is the entry point a `LaunchedEffect` would use to drive an
 * animation on the fast primitives.
 *
 * Cancelling the calling coroutine cancels the fast coroutine — that costs a single
 * `invokeOnCancellation` registration for the whole fast subtree, instead of one per suspension.
 *
 * Must be called from the host's owner thread; the caller's dispatcher is what puts it there.
 */
@OptIn(InternalComposeApi::class)
internal suspend fun <R> withFastCoroutine(
    host: FastCoroutineHost,
    clock: FastFrameClock,
    block: suspend FastRestrictedScope.() -> R,
): R {
    host.checkOwnerThread()
    host.stats.kotlinxBridgeCrossings++
    val scope = host.createScope()
    return suspendCancellableCoroutine { continuation ->
        var settled = false
        val fastJob =
            scope.launchWithResult<R>(
                clock = clock,
                onComplete = { result ->
                    settled = true
                    val exception = result.exceptionOrNull()
                    if (exception != null) continuation.resumeWithException(exception)
                    else continuation.resume(result.getOrNull() as R)
                },
                block = block,
            )
        if (!settled) {
            // A cancelled fast coroutine is retired: its body never completes, so there is no
            // result
            // to hand back. The job completing is the signal, and the caller is cancelled the way
            // kotlinx.coroutines expects — with an exception, because that is its contract.
            fastJob.invokeOnCompletion { failure ->
                if (settled) return@invokeOnCompletion
                settled = true
                if (failure != null) continuation.resumeWithException(failure)
                else continuation.cancel()
            }
            continuation.invokeOnCancellation { fastJob.cancel() }
        }
    }
}

/**
 * Creates a [FastCoroutineScope] whose lifetime is tied to this kotlinx [CoroutineScope]: when this
 * scope's [Job] completes or is cancelled, the fast scope is cancelled too.
 *
 * One `invokeOnCompletion` registration covers every coroutine launched from the returned scope.
 */
internal fun CoroutineScope.asFastCoroutineScope(host: FastCoroutineHost): FastCoroutineScope {
    host.checkOwnerThread()
    val fastScope = host.createScope()
    coroutineContext.job.invokeOnCompletion { fastScope.cancel() }
    return fastScope
}
