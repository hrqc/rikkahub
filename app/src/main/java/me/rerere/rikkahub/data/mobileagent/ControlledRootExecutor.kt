package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** No caller-supplied shell text, application IDs, or text input reaches this executor. */
internal sealed interface RootInputAction {
    data class Tap(val x: Int, val y: Int) : RootInputAction
    data class LongPress(val x: Int, val y: Int) : RootInputAction
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : RootInputAction
    data object Back : RootInputAction
}

internal enum class RootInputFailure { STALE_OR_CANCELLED, BUSY, COMMAND_FAILED, TIMEOUT, UNAVAILABLE, OUTPUT_LIMIT }
internal data class RootInputResult(val accepted: Boolean, val detail: String, val failure: RootInputFailure? = null)

internal fun rootInputCommand(action: RootInputAction): String {
    fun coordinate(value: Int): Int = value.also { require(it in 0..65_535) }
    val arguments = when (action) {
        is RootInputAction.Tap -> "touchscreen tap ${coordinate(action.x)} ${coordinate(action.y)}"
        is RootInputAction.LongPress -> {
            val x = coordinate(action.x)
            val y = coordinate(action.y)
            "touchscreen swipe $x $y $x $y 650"
        }
        is RootInputAction.Swipe -> "touchscreen swipe ${coordinate(action.x1)} ${coordinate(action.y1)} " +
            "${coordinate(action.x2)} ${coordinate(action.y2)} 350"
        RootInputAction.Back -> "keyevent 4"
    }
    // Both the root check and program are constants; all interpolated values above are bounded Ints.
    return "[ \"\$(id -u)\" = 0 ] && exec /system/bin/input $arguments"
}

internal class ControlledRootExecutor(
    private val timeoutMillis: Long = 4_000,
    private val startProcess: (String) -> Process = { command -> ProcessBuilder("su", "-c", command).start() },
) {
    private val epoch = AtomicLong()
    private val activeProcess = AtomicReference<Process?>(null)
    private val cleanupScheduled = AtomicReference<Process?>(null)

    fun cancel() {
        epoch.incrementAndGet()
        // Process implementations may acquire an InputStream monitor while destroying/closing
        // pipes. STOP must revoke authorization synchronously without waiting on that monitor.
        activeProcess.get()?.let { process ->
            if (cleanupScheduled.compareAndSet(null, process)) {
                processCleanup.execute {
                    try { destroy(process) } finally { cleanupScheduled.compareAndSet(process, null) }
                }
            }
        }
    }

    suspend fun execute(action: RootInputAction, canStart: (() -> Boolean)? = null, isValid: () -> Boolean): RootInputResult = withContext(Dispatchers.IO) {
        val command = rootInputCommand(action)
        val startEpoch = epoch.get()
        var processStarted = false
        fun allowed() = startEpoch == epoch.get() && isValid()
        if (!allowed() || canStart?.invoke() == false) return@withContext RootInputResult(false, "执行许可或页面已失效。", RootInputFailure.STALE_OR_CANCELLED)
        try {
            withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    if (!allowed() || canStart?.invoke() == false) return@coroutineScope RootInputResult(false, "执行许可或页面已失效。", RootInputFailure.STALE_OR_CANCELLED)
                    val process = startProcess(command)
                    processStarted = true
                    if (!activeProcess.compareAndSet(null, process)) {
                        destroy(process)
                        return@coroutineScope RootInputResult(false, "已有 Root 动作正在执行。", RootInputFailure.BUSY)
                    }
                    try {
                        // Covers STOP between the last check, Process.start(), and registration.
                        if (!allowed()) return@coroutineScope RootInputResult(false, "执行许可已失效。", RootInputFailure.STALE_OR_CANCELLED)
                        val stdout = async(Dispatchers.IO) { runInterruptible { drainBounded(process.inputStream) } }
                        val stderr = async(Dispatchers.IO) { runInterruptible { drainBounded(process.errorStream) } }
                        val exit = runInterruptible { process.waitFor() }
                        val outputWithinLimit = stdout.await() && stderr.await()
                        when {
                            !allowed() -> RootInputResult(false, "执行期间会话已停止或窗口已改变。", RootInputFailure.STALE_OR_CANCELLED)
                            !outputWithinLimit -> RootInputResult(false, "Root 动作输出超限，可能已执行；请先观察，不要直接重试。", RootInputFailure.OUTPUT_LIMIT)
                            exit != 0 -> RootInputResult(false, "Root 动作未正常完成；先检查页面结果，不要直接重复动作。", RootInputFailure.COMMAND_FAILED)
                            else -> RootInputResult(true, "Root 输入命令已完成，仍需重新观察确认界面结果。")
                        }
                    } finally {
                        activeProcess.compareAndSet(process, null)
                        destroy(process)
                    }
                }
            } ?: RootInputResult(false, "Root 动作超时，已终止命令进程；可能已部分执行，先观察再判断，不要直接重试。", RootInputFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (processStarted) RootInputResult(false, "Root 动作已尝试但结果未知；请先观察，不要直接重试。", RootInputFailure.COMMAND_FAILED)
            else RootInputResult(false, "Root 输入后端不可用，未启动 Root 动作。", RootInputFailure.UNAVAILABLE)
        }
    }

    /** Discard output; a tool result must never contain unexpected shell output or secrets. */
    private fun drainBounded(stream: InputStream): Boolean {
        val buffer = ByteArray(1_024)
        var count = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return count <= 4_096
            count = (count + read).coerceAtMost(4_097)
        }
    }

    private fun destroy(process: Process) {
        runCatching { process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    companion object {
        private val processCleanup = Executors.newSingleThreadExecutor { task ->
            Thread(task, "mobile-agent-root-cleanup").apply { isDaemon = true }
        }
    }
}
