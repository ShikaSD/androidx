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

import androidx.compose.runtime.internal.AtomicInt
import androidx.compose.runtime.internal.currentThreadId
import androidx.compose.runtime.platform.makeSynchronizedObject
import androidx.compose.runtime.platform.synchronized

/**
 * Per-object thread affinity with one-way promotion.
 *
 * An object guarded by a [ThreadAffinity] starts out confined to the thread that created it, and is
 * free to use plain fields with no synchronization. The first time it is touched from another
 * thread it is *promoted*: [isShared] flips permanently, and from then on the object's shared state
 * is accessed under [lock] via [withAccess]. For [FastFrameClock] the shared state is the lazily
 * created `BroadcastFrameClock` that serves foreign and kotlinx.coroutines awaiters, so promotion
 * is what makes a clock created on the UI thread safe to await from a background coroutine.
 *
 * Promotion is one-way and per-object on purpose. A single background thread poking one frame clock
 * must not deoptimize every animation in the process, and an object that has been shared once is
 * overwhelmingly likely to be shared again, so flapping back is not worth the race.
 *
 * The fast-path cost is one relaxed thread-id read plus one volatile read of the promotion flag; no
 * CAS, no allocation.
 */
internal class ThreadAffinity(private val onPromoted: ((ThreadAffinity) -> Unit)? = null) {
    val ownerThreadId: Long = currentThreadId()

    private val sharedFlag = AtomicInt(0)

    @Suppress("NOTHING_TO_INLINE") internal val lock = makeSynchronizedObject()

    /** True once this object has been observed from a thread other than its owner. */
    inline val isShared: Boolean
        get() = sharedFlag.get() != 0

    inline val isOwnerThread: Boolean
        get() = currentThreadId() == ownerThreadId

    /** True when the caller may operate on owner-thread-confined state without synchronization. */
    inline val isFastPath: Boolean
        get() = isOwnerThread && !isShared

    /**
     * Marks this object shared. Idempotent, and safe to call from any thread. Returns `true` if
     * this call performed the promotion.
     */
    fun promote(): Boolean {
        if (sharedFlag.get() != 0) return false
        if (!sharedFlag.compareAndSet(0, 1)) return false
        onPromoted?.invoke(this)
        return true
    }

    /**
     * Records that the current thread touched this object, promoting it if the current thread is
     * not the owner. Returns `true` if the caller may take the fast path.
     */
    fun observeAccess(): Boolean {
        if (isOwnerThread) return !isShared
        promote()
        return false
    }

    /**
     * Runs [block] with mutual exclusion appropriate to this object's current mode: unsynchronized
     * on the fast path, under [lock] once shared. Note that promotion may be observed *during*
     * [block] by another thread, so state protected this way must also be safe to publish; callers
     * that cannot tolerate that should keep their state owner-thread confined and use
     * [FastCoroutineHost.runOnOwnerThread] instead.
     */
    inline fun <R> withAccess(block: () -> R): R =
        if (observeAccess()) block() else synchronized(lock) { block() }
}
