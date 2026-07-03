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

@file:OptIn(InternalComposeApi::class)

package androidx.compose.runtime

import androidx.compose.runtime.internal.PlatformOptimizedCancellationException
import androidx.compose.runtime.internal.currentThreadId
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A Compose-owned coroutine launcher optimized for work that stays on one thread.
 *
 * Continuations resume directly while they stay on the launching thread. If a continuation is
 * resumed from another thread, the rest of that coroutine is handed off to the original
 * [ContinuationInterceptor] or to `kotlinx.coroutines` dispatch.
 */
@InternalComposeApi
public class ComposeCoroutineScope
internal constructor(parentContext: CoroutineContext, private val cancelMessage: String) :
    CoroutineScope, RememberObserver {
    private val job = Job(parentContext[Job])

    override val coroutineContext: CoroutineContext = parentContext + job

    public fun launch(block: suspend CoroutineScope.() -> Unit): ComposeCoroutineHandle =
        launchComposeCoroutine(coroutineContext, block)

    override fun onRemembered() {
        // Nothing to do.
    }

    override fun onForgotten() {
        job.cancel(cancelMessage)
    }

    override fun onAbandoned() {
        job.cancel(cancelMessage)
    }
}

/**
 * A Compose-owned handle for work launched by [launchComposeCoroutine].
 *
 * This is intentionally smaller than [Job]. It supports the operations used by Compose runtime call
 * sites without allocating a `kotlinx.coroutines` child job for every launched operation.
 */
@InternalComposeApi
public interface ComposeCoroutineHandle {
    public val isActive: Boolean

    public val isCompleted: Boolean

    public fun cancel()

    public suspend fun join()
}

@InternalComposeApi
public interface ComposeCancellableContinuation<T> {
    public val context: CoroutineContext

    public fun resume(value: T)

    public fun resumeWithException(exception: Throwable)

    public fun resumeWith(result: Result<T>)

    public fun invokeOnCancellation(onCancellation: (CancellationException) -> Unit)
}

/**
 * Return a [ComposeCoroutineScope] bound to this point in the composition.
 *
 * This is a prototype counterpart to [rememberCoroutineScope] for Compose-owned call sites that can
 * use the single-thread fast path and only need a [ComposeCoroutineHandle] for cancellation.
 */
@Composable
@InternalComposeApi
public fun rememberComposeCoroutineScope(): ComposeCoroutineScope {
    val applyContext = currentComposer.applyCoroutineContext
    return remember {
        ComposeCoroutineScope(applyContext, "rememberComposeCoroutineScope left the composition")
    }
}

/**
 * Launch [block] without creating a `kotlinx.coroutines` coroutine when execution stays on the
 * current thread.
 *
 * The returned [ComposeCoroutineHandle] is cancelled when [context]'s [Job] is cancelled. If any
 * continuation resumes from a different thread, subsequent resumes are dispatched through
 * [context]'s original [ContinuationInterceptor] when it has one, or through `kotlinx.coroutines`
 * otherwise.
 */
@InternalComposeApi
public fun launchComposeCoroutine(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): ComposeCoroutineHandle {
    val parentJob = context[Job]
    val fallbackContext = context
    val originalInterceptor = fallbackContext[ContinuationInterceptor]
    val handle =
        InteropComposeCoroutineHandle(
            parentJob = parentJob,
            fallbackContext = fallbackContext,
            fallbackInterceptor = originalInterceptor,
        )
    val fastContext =
        fallbackContext.minusKey(ContinuationInterceptor) + handle
    handle.coroutineContext = fastContext
    if (!handle.isActive) {
        handle.complete()
        return handle
    }

    block.startCoroutine(handle, handle)
    return handle
}

/**
 * Launch [block] on the current thread without replacing [context]'s [ContinuationInterceptor].
 *
 * This assumes Compose-owned children and suspension points. It exists so we can benchmark the
 * lower-overhead primitive side by side with [launchComposeCoroutine], which keeps the broader
 * interop behavior.
 */
@InternalComposeApi
public fun launchSingleThreadComposeCoroutine(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): ComposeCoroutineHandle {
    val fallbackContext = context
    val originalInterceptor = fallbackContext[ContinuationInterceptor]
    val handle =
        SingleThreadComposeCoroutineHandle(
            fallbackContext = fallbackContext,
            fallbackInterceptor = originalInterceptor,
        )
    handle.coroutineContext = handle

    val result =
        try {
            block.startCoroutineUninterceptedOrReturn(handle, handle)
        } catch (exception: Throwable) {
            completeComposeCoroutine(handle, handle, Result.failure(exception))
            return handle
        }
    if (result !== COROUTINE_SUSPENDED) {
        completeComposeCoroutine(handle, handle, Result.success(Unit))
    }
    return handle
}

@InternalComposeApi
public val CoroutineContext.composeCoroutineHandle: ComposeCoroutineHandle?
    get() =
        this[SingleThreadComposeCoroutineHandle]
            ?: (this[ContinuationInterceptor] as? InteropComposeCoroutineHandle)

@InternalComposeApi
public suspend fun <T> suspendComposeCancellableCoroutine(
    block: (ComposeCancellableContinuation<T>) -> Unit
): T {
    if (coroutineContext.composeCoroutineHandle == null) {
        return suspendCancellableCoroutine { continuation ->
            block(KotlinxComposeCancellableContinuation(continuation))
        }
    }
    return suspendComposeCancellableCoroutineFast(block)
}

private suspend fun <T> suspendComposeCancellableCoroutineFast(
    block: (ComposeCancellableContinuation<T>) -> Unit
): T =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        val singleThreadHandle = continuation.context[SingleThreadComposeCoroutineHandle]
        if (singleThreadHandle != null) {
            val safeContinuation =
                SingleThreadComposeSafeContinuation(singleThreadHandle, continuation)
            singleThreadHandle.register(safeContinuation)
            block(safeContinuation)
            safeContinuation.getOrThrow()
        } else {
            val intercepted = continuation.intercepted()
            val safeContinuation = ComposeSafeContinuation(intercepted)
            block(safeContinuation)
            safeContinuation.getOrThrow()
        }
    }

private interface ComposeCancellationTarget {
    fun cancel(cause: CancellationException)
}

private class ComposeContinuation<T>(
    private val handle: ComposeCoroutineHandleImpl,
    private val continuation: Continuation<T>,
) : Continuation<T>, ComposeCancellationTarget {
    private var cancellationResumed = false
    private var cancellationCause: CancellationException? = null
    private var cancellationCallback: ((CancellationException) -> Unit)? = null

    override val context: CoroutineContext
        get() = continuation.context

    init {
        handle.register(this)
        val job = continuation.context[Job]
        if (job != null && job !== handle.parentJob) {
            handle.registerKotlinxJob(job)
        }
    }

    override fun resumeWith(result: Result<T>) {
        resume(result)
    }

    private fun resume(result: Result<T>) {
        val resumeResult = if (result.isSuccess) result.withCancellation(handle) else result

        if (handle.shouldResumeDirectly()) {
            continuation.resumeWith(resumeResult)
        } else {
            handle.markHandedOff()
            resumeWithFallback(resumeResult)
        }
    }

    override fun cancel(cause: CancellationException) {
        if (!cancellationResumed) {
            cancellationResumed = true
            cancellationCause = cause
            val callback = cancellationCallback
            cancellationCallback = null
            callback?.invoke(cause)
            resume(Result.failure(cause))
        }
    }

    fun invokeOnCancellation(onCancellation: (CancellationException) -> Unit) {
        val currentCause = cancellationCause
        if (currentCause != null) {
            onCancellation(currentCause)
            return
        }
        cancellationCallback = onCancellation
    }

    private fun resumeWithFallback(result: Result<T>) {
        when (val interceptor = handle.fallbackInterceptor) {
            is CoroutineDispatcher -> {
                interceptor.dispatch(handle.fallbackContext) {
                    continuation.resumeWith(result.withCancellation(handle))
                }
            }
            null -> {
                CoroutineScope(handle.fallbackContext).launch {
                    continuation.resumeWith(result.withCancellation(handle))
                }
            }
            else -> {
                interceptor
                    .interceptContinuation(continuation)
                    .resumeWith(result.withCancellation(handle))
            }
        }
    }
}

private class ComposeSafeContinuation<T>(private val delegate: Continuation<T>) :
    Continuation<T>, ComposeCancellableContinuation<T> {
    private var state: Any? = Undecided
    private val composeContinuation = delegate as? ComposeContinuation<T>

    override val context: CoroutineContext
        get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        when (state) {
            Undecided -> state = Completed(result)
            Suspended -> {
                state = Resumed
                delegate.resumeWith(result)
            }
            else -> throw IllegalStateException("Already resumed")
        }
    }

    fun getOrThrow(): Any? {
        val current = state
        if (current === Undecided) {
            state = Suspended
            return COROUTINE_SUSPENDED
        }
        if (current === Suspended) return COROUTINE_SUSPENDED
        @Suppress("UNCHECKED_CAST")
        return (current as Completed<T>).result.getOrThrow()
    }

    override fun resume(value: T) {
        resumeWith(Result.success(value))
    }

    override fun resumeWithException(exception: Throwable) {
        resumeWith(Result.failure(exception))
    }

    override fun invokeOnCancellation(onCancellation: (CancellationException) -> Unit) {
        composeContinuation?.invokeOnCancellation(onCancellation)
    }

    private object Undecided
    private object Suspended
    private object Resumed
    private class Completed<T>(val result: Result<T>)
}

private class SingleThreadComposeSafeContinuation<T>(
    private val handle: ComposeCoroutineHandleImpl,
    private val delegate: Continuation<T>,
) : Continuation<T>, ComposeCancellableContinuation<T>, ComposeCancellationTarget {
    private var stateValue: Any? = Undecided
    private var cancellationResumed = false
    private var cancellationCause: CancellationException? = null
    private var cancellationCallback: ((CancellationException) -> Unit)? = null

    override val context: CoroutineContext
        get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        val resumeResult = if (result.isSuccess) result.withCancellation(handle) else result
        when (stateValue) {
            Undecided -> stateValue = Completed(resumeResult)
            Suspended -> {
                stateValue = Resumed
                resumeDelegate(resumeResult)
            }
            else -> throw IllegalStateException("Already resumed")
        }
    }

    fun getOrThrow(): Any? {
        val current = stateValue
        if (current === Undecided) {
            stateValue = Suspended
            return COROUTINE_SUSPENDED
        }
        if (current === Suspended) return COROUTINE_SUSPENDED
        @Suppress("UNCHECKED_CAST")
        return (current as Completed<T>).result.getOrThrow()
    }

    private fun resumeDelegate(result: Result<T>) {
        if (handle.shouldResumeDirectly()) {
            delegate.resumeWith(result)
        } else {
            handle.markHandedOff()
            resumeWithFallback(result)
        }
    }

    private fun resumeWithFallback(result: Result<T>) {
        when (val interceptor = handle.fallbackInterceptor) {
            is CoroutineDispatcher -> {
                interceptor.dispatch(handle.fallbackContext) {
                    delegate.resumeWith(result.withCancellation(handle))
                }
            }
            null -> {
                CoroutineScope(handle.fallbackContext).launch {
                    delegate.resumeWith(result.withCancellation(handle))
                }
            }
            else -> {
                interceptor
                    .interceptContinuation(delegate)
                    .resumeWith(result.withCancellation(handle))
            }
        }
    }

    override fun resume(value: T) {
        resumeWith(Result.success(value))
    }

    override fun resumeWithException(exception: Throwable) {
        resumeWith(Result.failure(exception))
    }

    override fun invokeOnCancellation(onCancellation: (CancellationException) -> Unit) {
        val currentCause = cancellationCause
        if (currentCause != null) {
            onCancellation(currentCause)
            return
        }
        cancellationCallback = onCancellation
    }

    override fun cancel(cause: CancellationException) {
        if (!cancellationResumed) {
            cancellationResumed = true
            cancellationCause = cause
            val callback = cancellationCallback
            cancellationCallback = null
            callback?.invoke(cause)
            resumeWith(Result.failure(cause))
        }
    }

    private object Undecided
    private object Suspended
    private object Resumed
    private class Completed<T>(val result: Result<T>)
}

private class KotlinxComposeCancellableContinuation<T>(
    private val continuation: CancellableContinuation<T>
) : ComposeCancellableContinuation<T> {
    override val context: CoroutineContext
        get() = continuation.context

    override fun resume(value: T) {
        continuation.resume(value)
    }

    override fun resumeWithException(exception: Throwable) {
        continuation.resumeWithException(exception)
    }

    override fun resumeWith(result: Result<T>) {
        continuation.resumeWith(result)
    }

    override fun invokeOnCancellation(onCancellation: (CancellationException) -> Unit) {
        continuation.invokeOnCancellation { cause ->
            onCancellation(parentCancellationException(cause ?: CancelException))
        }
    }
}

private inline fun CoroutineDispatcher.dispatch(
    context: CoroutineContext,
    crossinline block: () -> Unit,
) {
    dispatch(context, Runnable { block() })
}

private fun <T> Result<T>.withCancellation(handle: ComposeCoroutineHandleImpl): Result<T> {
    if (isFailure) return this
    val cause = handle.cancellationFailure() ?: return this
    return Result.failure(cause)
}

private fun completeComposeCoroutine(
    handle: ComposeCoroutineHandleImpl,
    context: CoroutineContext,
    result: Result<Unit>,
) {
    result.fold(
        onSuccess = { handle.complete() },
        onFailure = { exception ->
            if (exception is CancellationException) {
                handle.completeExceptionally(exception)
            } else {
                handle.completeExceptionally(exception)
                context[CoroutineExceptionHandler]?.handleException(context, exception)
                    ?: throw exception
            }
        },
    )
}

@OptIn(InternalComposeApi::class)
private abstract class ComposeCoroutineHandleImpl(
    val parentJob: Job?,
    val fallbackContext: CoroutineContext,
    val fallbackInterceptor: ContinuationInterceptor?,
    connectParent: Boolean,
) : ComposeCoroutineHandle, CoroutineScope, Continuation<Unit> {
    override lateinit var coroutineContext: CoroutineContext
    override val context: CoroutineContext
        get() = coroutineContext

    private val ownerThreadId = currentThreadId()
    private var handedOff = false
    private var completed = false
    private var cancellationCause: CancellationException? = null
    private var completedCause: Throwable? = null
    private var joiners: List<CancellableContinuation<Unit>> = emptyList()
    private var currentContinuation: ComposeCancellationTarget? = null
    private var currentKotlinxJob: Job? = null
    private val parentCompletionHandle: DisposableHandle?

    override val isActive: Boolean
        get() = !completed && cancellationCause == null

    override val isCompleted: Boolean
        get() = completed

    init {
        parentCompletionHandle =
            if (connectParent && parentJob != null && parentJob.isActive) {
                parentJob.invokeOnCompletion { cause ->
                    if (cause != null) {
                        cancel(parentCancellationException(cause))
                    }
                }
            } else {
                null
            }

        if (connectParent && parentJob != null && !parentJob.isActive) {
            cancel(CancelException)
        }
    }

    override fun resumeWith(result: Result<Unit>) {
        completeComposeCoroutine(this, coroutineContext, result)
    }

    override fun cancel() {
        cancel(CancelException)
    }

    fun cancel(cause: CancellationException) {
        if (isCompleted || cancellationCause != null) return
        cancellationCause = cause
        val job = currentKotlinxJob
        if (job != null && job.isActive) {
            job.cancel(cause)
        } else {
            currentContinuation?.cancel(cause)
        }
    }

    override suspend fun join() {
        if (isCompleted) return
        suspendCancellableCoroutine { continuation ->
            if (!addJoiner(continuation)) {
                continuation.resume(Unit)
            } else {
                continuation.invokeOnCancellation { removeJoiner(continuation) }
            }
        }
    }

    fun cancellationFailure(): Throwable? {
        val cause = cancellationCause
        if (cause != null) {
            return cause
        }
        val completed = completedCause
        if (completed is CancellationException) {
            return completed
        }
        return null
    }

    fun shouldResumeDirectly(): Boolean = !handedOff && currentThreadId() == ownerThreadId

    fun markHandedOff() {
        handedOff = true
    }

    fun register(continuation: ComposeCancellationTarget) {
        currentContinuation = continuation
    }

    fun registerKotlinxJob(job: Job) {
        currentKotlinxJob = job
        job.invokeOnCompletion {
            if (currentKotlinxJob === job) {
                currentKotlinxJob = null
            }
        }
    }

    fun complete() {
        complete(cancellationCause)
    }

    fun completeExceptionally(cause: Throwable) {
        complete(cause)
    }

    private fun complete(cause: Throwable?) {
        if (isCompleted) return
        val currentJoiners = joiners
        completed = true
        completedCause = cause
        parentCompletionHandle?.dispose()
        currentContinuation = null
        currentKotlinxJob = null
        currentJoiners.forEach { it.resume(Unit) }
    }

    private fun addJoiner(continuation: CancellableContinuation<Unit>): Boolean {
        if (isCompleted) return false
        joiners = joiners + continuation
        return true
    }

    private fun removeJoiner(continuation: CancellableContinuation<Unit>) {
        if (isCompleted) return
        joiners = joiners - continuation
    }
}

private class InteropComposeCoroutineHandle(
    parentJob: Job?,
    fallbackContext: CoroutineContext,
    fallbackInterceptor: ContinuationInterceptor?,
) :
    ComposeCoroutineHandleImpl(
        parentJob = parentJob,
        fallbackContext = fallbackContext,
        fallbackInterceptor = fallbackInterceptor,
        connectParent = true,
    ),
    ContinuationInterceptor {
    override val key: CoroutineContext.Key<*>
        get() = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        ComposeContinuation(this, continuation)
}

private class SingleThreadComposeCoroutineHandle(
    fallbackContext: CoroutineContext,
    fallbackInterceptor: ContinuationInterceptor?,
) :
    ComposeCoroutineHandleImpl(
        parentJob = null,
        fallbackContext = fallbackContext,
        fallbackInterceptor = fallbackInterceptor,
        connectParent = false,
    ),
    CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SingleThreadComposeCoroutineHandle>

    override val key: CoroutineContext.Key<*>
        get() = Key

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        @Suppress("UNCHECKED_CAST")
        return if (key === Key) this as E else fallbackContext[key]
    }

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(fallbackContext.fold(initial, operation), this)

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        if (key === Key) return fallbackContext

        val remainingContext = fallbackContext.minusKey(key)
        return if (remainingContext === fallbackContext) {
            this
        } else {
            remainingContext + this
        }
    }
}

private fun parentCancellationException(cause: Throwable): CancellationException =
    cause as? CancellationException ?: CancelException

private object CancelException : PlatformOptimizedCancellationException()
