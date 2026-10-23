package com.cliq.plugin.util

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object IndicatorCoroutines {

    private const val CANCELLATION_POLL_MS = 100L

    @RequiresBackgroundThread
    fun <T> runCancellable(indicator: ProgressIndicator, block: suspend CoroutineScope.() -> T): T {
        indicator.checkCanceled()
        return try {
            runBlocking {
                val work = async(Dispatchers.Default) { block() }
                val watcher = launch {
                    while (isActive && !work.isCompleted) {
                        if (indicator.isCanceled) work.cancel()
                        delay(CANCELLATION_POLL_MS)
                    }
                }
                try {
                    work.await()
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (indicator.isCanceled) throw ProcessCanceledException(e)
            throw e
        }
    }
}
