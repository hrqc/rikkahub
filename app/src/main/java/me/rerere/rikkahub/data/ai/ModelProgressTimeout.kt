package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val PHONE_MODEL_PROGRESS_TIMEOUT_MILLIS = 60_000L
internal const val PHONE_MODEL_PROGRESS_TIMEOUT_MESSAGE =
    "模型或输入准备已连续 60 秒没有新进展，本次请求已取消，手机任务已暂停；请检查网络或模型后手动继续。"

/** Not a network error: never automatically replay a phone step after this timeout. */
internal class ModelProgressTimeoutException : IllegalStateException(PHONE_MODEL_PROGRESS_TIMEOUT_MESSAGE)

/**
 * Limits only one model/preparation wait, never a tool execution. Its child watchdog cancels
 * the entire block and waits for cleanup (including callbackFlow.awaitClose) before returning.
 * A null limit leaves ordinary chat behavior unchanged.
 */
internal suspend fun <T> withModelProgressTimeout(
    timeoutMillis: Long?,
    block: suspend (progress: () -> Unit) -> T,
): T {
    if (timeoutMillis == null) return block {}
    require(timeoutMillis > 0)
    return coroutineScope {
        val activity = Channel<Unit>(Channel.CONFLATED)
        val watchdog = launch {
            while (true) {
                val progressed = withTimeoutOrNull(timeoutMillis) {
                    activity.receive()
                    true
                } ?: false
                if (!progressed) throw ModelProgressTimeoutException()
            }
        }
        try {
            block { activity.trySend(Unit) }
        } finally {
            // cancel() alone can race a new receive's closed-channel fast path on another
            // thread. Join before closing, including when the model request was cancelled.
            withContext(NonCancellable) {
                watchdog.cancelAndJoin()
                activity.close()
            }
        }
    }
}
