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
import kotlin.coroutines.AbstractCoroutineContextElement
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
    val handle = ComposeCoroutineHandleImpl(parentJob)
    val fallbackContext = context
    val originalInterceptor = fallbackContext[ContinuationInterceptor]
    val state =
        ComposeCoroutineState(
            handle = handle,
            parentJob = parentJob,
            ownerThreadId = currentThreadId(),
            fallbackContext = fallbackContext,
            fallbackInterceptor = originalInterceptor,
        )
    val fastContext =
        fallbackContext.minusKey(ContinuationInterceptor) + ComposeCoroutineInterceptor(state)
    if (!handle.isActive) {
        handle.complete()
        return handle
    }

    val scope =
        object : CoroutineScope {
            override val coroutineContext: CoroutineContext = fastContext
        }
    val completion =
        object : Continuation<Unit> {
            override val context: CoroutineContext = fastContext

            override fun resumeWith(result: Result<Unit>) {
                completeComposeCoroutine(handle, fastContext, result)
            }
        }

    block.startCoroutine(scope, completion)
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
    val parentJob = context[Job]
    val handle = ComposeCoroutineHandleImpl(parentJob)
    val fallbackContext = context
    val originalInterceptor = fallbackContext[ContinuationInterceptor]
    val state =
        ComposeCoroutineState(
            handle = handle,
            parentJob = parentJob,
            ownerThreadId = currentThreadId(),
            fallbackContext = fallbackContext,
            fallbackInterceptor = originalInterceptor,
        )
    val fastContext = fallbackContext + ComposeCoroutineElement(state)
    if (!handle.isActive) {
        handle.complete()
        return handle
    }

    val scope =
        object : CoroutineScope {
            override val coroutineContext: CoroutineContext = fastContext
        }
    val completion =
        object : Continuation<Unit> {
            override val context: CoroutineContext = fastContext

            override fun resumeWith(result: Result<Unit>) {
                completeComposeCoroutine(handle, fastContext, result)
            }
        }

    val result = block.startCoroutineUninterceptedOrReturn(scope, completion)
    if (result !== COROUTINE_SUSPENDED) {
        completeComposeCoroutine(handle, fastContext, Result.success(Unit))
    }
    return handle
}

@InternalComposeApi
public val CoroutineContext.composeCoroutineHandle: ComposeCoroutineHandle?
    get() =
        this[ComposeCoroutineElement]?.handle
            ?: (this[ContinuationInterceptor] as? ComposeCoroutineInterceptor)?.handle

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
        val composeElement = continuation.context[ComposeCoroutineElement]
        if (composeElement != null) {
            val state = composeElement.state
            val safeContinuation = SingleThreadComposeSafeContinuation(state, continuation)
            val cancellableContinuation =
                SingleThreadComposeCancellableContinuationImpl(safeContinuation)
            state.handle.register(cancellableContinuation)
            block(cancellableContinuation)
            safeContinuation.getOrThrow()
        } else {
            val intercepted = continuation.intercepted()
            val safeContinuation = ComposeSafeContinuation(intercepted)
            block(ComposeCancellableContinuationImpl(safeContinuation, intercepted))
            safeContinuation.getOrThrow()
        }
    }

private class ComposeCoroutineState(
    val handle: ComposeCoroutineHandleImpl,
    val parentJob: Job?,
    val ownerThreadId: Long,
    val fallbackContext: CoroutineContext,
    val fallbackInterceptor: ContinuationInterceptor?,
) {
    private var handedOff = false

    fun shouldResumeDirectly(): Boolean = !handedOff && currentThreadId() == ownerThreadId

    fun markHandedOff() {
        handedOff = true
    }
}

private class ComposeCoroutineElement(val state: ComposeCoroutineState) :
    AbstractCoroutineContextElement(ComposeCoroutineElement) {
    companion object Key : CoroutineContext.Key<ComposeCoroutineElement>

    @OptIn(InternalComposeApi::class)
    val handle: ComposeCoroutineHandle
        get() = state.handle
}

private class ComposeCoroutineInterceptor(val state: ComposeCoroutineState) :
    AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    @OptIn(InternalComposeApi::class)
    val handle: ComposeCoroutineHandle
        get() = state.handle

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        ComposeContinuation(state, continuation)
}

private interface ComposeCancellationTarget {
    fun cancel(cause: CancellationException)
}

private class ComposeContinuation<T>(
    private val state: ComposeCoroutineState,
    private val continuation: Continuation<T>,
) : Continuation<T>, ComposeCancellationTarget {
    private var cancellationResumed = false
    private var cancellationCause: CancellationException? = null
    private var cancellationCallback: ((CancellationException) -> Unit)? = null

    override val context: CoroutineContext
        get() = continuation.context

    init {
        state.handle.register(this)
        val job = continuation.context[Job]
        if (job != null && job !== state.parentJob) {
            state.handle.registerKotlinxJob(job)
        }
    }

    override fun resumeWith(result: Result<T>) {
        resume(result)
    }

    private fun resume(result: Result<T>) {
        val resumeResult = if (result.isSuccess) result.withCancellation(state.handle) else result

        if (state.shouldResumeDirectly()) {
            continuation.resumeWith(resumeResult)
        } else {
            state.markHandedOff()
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
        when (val interceptor = state.fallbackInterceptor) {
            is CoroutineDispatcher -> {
                interceptor.dispatch(state.fallbackContext) {
                    continuation.resumeWith(result.withCancellation(state.handle))
                }
            }
            null -> {
                CoroutineScope(state.fallbackContext).launch {
                    continuation.resumeWith(result.withCancellation(state.handle))
                }
            }
            else -> {
                interceptor
                    .interceptContinuation(continuation)
                    .resumeWith(result.withCancellation(state.handle))
            }
        }
    }
}

private class ComposeSafeContinuation<T>(private val delegate: Continuation<T>) :
    Continuation<T> {
    private var state: Any? = Undecided

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

    private object Undecided
    private object Suspended
    private object Resumed
    private class Completed<T>(val result: Result<T>)
}

private class SingleThreadComposeSafeContinuation<T>(
    private val state: ComposeCoroutineState,
    private val delegate: Continuation<T>,
) : Continuation<T> {
    private var stateValue: Any? = Undecided

    override val context: CoroutineContext
        get() = delegate.context

    override fun resumeWith(result: Result<T>) {
        val resumeResult = if (result.isSuccess) result.withCancellation(state.handle) else result
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
        if (state.shouldResumeDirectly()) {
            delegate.resumeWith(result)
        } else {
            state.markHandedOff()
            resumeWithFallback(result)
        }
    }

    private fun resumeWithFallback(result: Result<T>) {
        when (val interceptor = state.fallbackInterceptor) {
            is CoroutineDispatcher -> {
                interceptor.dispatch(state.fallbackContext) {
                    delegate.resumeWith(result.withCancellation(state.handle))
                }
            }
            null -> {
                CoroutineScope(state.fallbackContext).launch {
                    delegate.resumeWith(result.withCancellation(state.handle))
                }
            }
            else -> {
                interceptor
                    .interceptContinuation(delegate)
                    .resumeWith(result.withCancellation(state.handle))
            }
        }
    }

    private object Undecided
    private object Suspended
    private object Resumed
    private class Completed<T>(val result: Result<T>)
}

private class ComposeCancellableContinuationImpl<T>(
    private val continuation: ComposeSafeContinuation<T>,
    intercepted: Continuation<T>,
) : ComposeCancellableContinuation<T> {
    private val composeContinuation = intercepted as? ComposeContinuation<T>

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
        composeContinuation?.invokeOnCancellation(onCancellation)
    }
}

private class SingleThreadComposeCancellableContinuationImpl<T>(
    private val continuation: SingleThreadComposeSafeContinuation<T>
) : ComposeCancellableContinuation<T>, ComposeCancellationTarget {
    private var cancellationResumed = false
    private var cancellationCause: CancellationException? = null
    private var cancellationCallback: ((CancellationException) -> Unit)? = null

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
            continuation.resumeWith(Result.failure(cause))
        }
    }
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
    return try {
        handle.ensureActive()
        this
    } catch (e: Throwable) {
        Result.failure(e)
    }
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

private sealed interface ComposeCoroutineHandleState

private class ActiveComposeCoroutine(
    val cancellationCause: CancellationException?,
    val joiners: List<CancellableContinuation<Unit>>,
) : ComposeCoroutineHandleState

private class CompletedComposeCoroutine(val cause: Throwable?) : ComposeCoroutineHandleState

@OptIn(InternalComposeApi::class)
private class ComposeCoroutineHandleImpl(parentJob: Job?) : ComposeCoroutineHandle {
    private var state: ComposeCoroutineHandleState = ActiveComposeCoroutine(null, emptyList())
    private var currentContinuation: ComposeCancellationTarget? = null
    private var currentKotlinxJob: Job? = null
    private val parentCompletionHandle: DisposableHandle?

    override val isActive: Boolean
        get() {
            val current = state
            return current is ActiveComposeCoroutine && current.cancellationCause == null
        }

    override val isCompleted: Boolean
        get() = state is CompletedComposeCoroutine

    init {
        parentCompletionHandle =
            if (parentJob != null && parentJob.isActive) {
                parentJob.invokeOnCompletion { cause ->
                    if (cause != null) {
                        cancel(parentCancellationException(cause))
                    }
                }
            } else {
                null
            }

        if (parentJob != null && !parentJob.isActive) {
            cancel(CancelException)
        }
    }

    override fun cancel() {
        cancel(CancelException)
    }

    fun cancel(cause: CancellationException) {
        when (val current = state) {
            is CompletedComposeCoroutine -> return
            is ActiveComposeCoroutine -> {
                if (current.cancellationCause != null) return
                state = ActiveComposeCoroutine(cause, current.joiners)
                val job = currentKotlinxJob
                if (job != null && job.isActive) {
                    job.cancel(cause)
                } else {
                    currentContinuation?.cancel(cause)
                }
            }
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

    fun ensureActive() {
        val current = state
        if (current is ActiveComposeCoroutine && current.cancellationCause != null) {
            throw current.cancellationCause
        }
        if (current is CompletedComposeCoroutine && current.cause is CancellationException) {
            throw current.cause
        }
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
        val current = state
        val cause =
            if (current is ActiveComposeCoroutine) {
                current.cancellationCause
            } else {
                null
            }
        complete(cause)
    }

    fun completeExceptionally(cause: Throwable) {
        complete(cause)
    }

    private fun complete(cause: Throwable?) {
        val joiners =
            when (val current = state) {
                is CompletedComposeCoroutine -> return
                is ActiveComposeCoroutine -> current.joiners
            }
        state = CompletedComposeCoroutine(cause)
        parentCompletionHandle?.dispose()
        currentContinuation = null
        currentKotlinxJob = null
        joiners.forEach { it.resume(Unit) }
    }

    private fun addJoiner(continuation: CancellableContinuation<Unit>): Boolean {
        when (val current = state) {
            is CompletedComposeCoroutine -> return false
            is ActiveComposeCoroutine -> {
                state =
                    ActiveComposeCoroutine(
                        current.cancellationCause,
                        current.joiners + continuation,
                    )
                return true
            }
        }
    }

    private fun removeJoiner(continuation: CancellableContinuation<Unit>) {
        when (val current = state) {
            is CompletedComposeCoroutine -> return
            is ActiveComposeCoroutine -> {
                state =
                    ActiveComposeCoroutine(
                        current.cancellationCause,
                        current.joiners - continuation,
                    )
            }
        }
    }
}

private fun parentCancellationException(cause: Throwable): CancellationException =
    cause as? CancellationException ?: CancelException

private object CancelException : PlatformOptimizedCancellationException()
