package dev.solvit.app

import android.util.Log

/** Diagnostic only: preserve the original fatal handler and capture the other offending thread. */
internal object SnapshotThreadFailureProbe {
    fun install(): AutoCloseable {
        val previous = checkNotNull(Thread.getDefaultUncaughtExceptionHandler())
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            try {
                if (error is IllegalArgumentException && error.message.orEmpty().contains("SnapshotStateObserver")) {
                    val id = Regex("previousThreadId=(\\d+)").find(error.message.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    val other = Thread.getAllStackTraces().entries.singleOrNull { it.key.id == id }
                    Log.e("SNAPSHOT_THREAD_DIAG", "fatalThread=${thread.id}:${thread.name}; previousThread=$id; alive=${other != null}")
                    other?.let { (owner, stack) ->
                        Log.e("SNAPSHOT_THREAD_DIAG", "previousThreadName=${owner.name}")
                        stack.take(24).forEach { Log.e("SNAPSHOT_THREAD_DIAG", it.toString()) }
                    }
                }
            } finally {
                // Never suppress, retry or convert an application crash into a passing test.
                previous.uncaughtException(thread, error)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        return AutoCloseable {
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
