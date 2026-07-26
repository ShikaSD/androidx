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

import androidx.collection.MutableObjectList
import androidx.collection.mutableObjectListOf

/**
 * Platform hook for running a task on the owner thread after a delay — the fast counterpart of what
 * `Delay` does for a `CoroutineDispatcher`.
 *
 * In production this is `Handler.postDelayed` on the UI thread (or the equivalent timer on other
 * platforms). [FastVirtualTimeScheduler] is the deterministic implementation used by tests and
 * benchmarks, where sleeping for real would make measurements useless.
 *
 * Implementations only need to be callable from the owner thread; cross-thread scheduling goes
 * through [FastCoroutineHost.runOnOwnerThread] first.
 */
internal interface FastScheduler {
    /**
     * Schedules [task] to run on the owner thread after [delayMillis]. Disposing the returned
     * handle cancels it if it has not run yet.
     */
    fun schedule(delayMillis: Long, task: FastTask): FastDisposableHandle
}

/**
 * Suspends for [millis], resuming on the owner thread. Reached through [FastRestrictedScope.delay].
 *
 * Cancellation disposes the scheduled task, so a cancelled delay leaves nothing pending — the
 * common case for the pattern this exists to support: a fade-out rescheduled on every scroll frame
 * cancels the previous delay long before it fires (see `NonInteractiveScrollbarNode`).
 */
internal suspend fun delayIn(
    host: FastCoroutineHost,
    job: FastJob,
    scheduler: FastScheduler,
    millis: Long,
) {
    fastSuspendCancellableIn<Unit>(host, job) { continuation ->
        // The continuation is the task: resuming it is exactly what the timer should do.
        val handle = scheduler.schedule(millis, FastTask { continuation.resume(Unit) })
        continuation.invokeOnCancellation { handle.dispose() }
    }
}

/**
 * A [FastScheduler] driven by an explicit clock instead of wall time: nothing runs until
 * [advanceTimeBy] is called.
 *
 * This is deliberately in main rather than test sources so that both the runtime's tests and the
 * benchmark module (which cannot see test sources) can drive delays deterministically. Production
 * hosts install a timer-backed scheduler instead.
 */
internal class FastVirtualTimeScheduler : FastScheduler {

    private class Entry(val dueAtMillis: Long, val task: FastTask) {
        var isDisposed = false
    }

    private var entries: MutableObjectList<Entry> = mutableObjectListOf()
    private var spare: MutableObjectList<Entry> = mutableObjectListOf()

    /** Current virtual time in milliseconds. */
    var currentTimeMillis: Long = 0L
        private set

    /** Number of scheduled tasks that have neither run nor been disposed. */
    val pendingCount: Int
        get() {
            var count = 0
            for (i in 0 until entries.size) if (!entries[i].isDisposed) count++
            return count
        }

    override fun schedule(delayMillis: Long, task: FastTask): FastDisposableHandle {
        val entry = Entry(currentTimeMillis + delayMillis, task)
        entries.add(entry)
        return FastDisposableHandle { entry.isDisposed = true }
    }

    /**
     * Advances virtual time by [millis], running everything that comes due in due-time order. Tasks
     * scheduled by those tasks run too if they also come due within the same advance.
     */
    fun advanceTimeBy(millis: Long) {
        val target = currentTimeMillis + millis
        while (true) {
            val next = earliestDueEntry(target) ?: break
            currentTimeMillis = maxOf(currentTimeMillis, next.dueAtMillis)
            next.isDisposed = true
            next.task.execute()
        }
        currentTimeMillis = target
        compact()
    }

    private fun earliestDueEntry(throughMillis: Long): Entry? {
        var earliest: Entry? = null
        for (i in 0 until entries.size) {
            val entry = entries[i]
            if (entry.isDisposed || entry.dueAtMillis > throughMillis) continue
            if (earliest == null || entry.dueAtMillis < earliest.dueAtMillis) earliest = entry
        }
        return earliest
    }

    private fun compact() {
        val kept = spare
        for (i in 0 until entries.size) {
            val entry = entries[i]
            if (!entry.isDisposed) kept.add(entry)
        }
        entries.clear()
        spare = entries
        entries = kept
    }
}
