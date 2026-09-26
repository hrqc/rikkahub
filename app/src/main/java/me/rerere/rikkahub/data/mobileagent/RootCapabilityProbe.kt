package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal sealed interface RootCommandResult {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val outputTruncated: Boolean = false,
    ) : RootCommandResult

    data object Unavailable : RootCommandResult
    data object TimedOut : RootCommandResult
    data object Failed : RootCommandResult
}

/** Fixed command only. It deliberately has no command/argument parameter. */
internal fun interface RootCommandRunner {
    suspend fun run(): RootCommandResult
}

internal class RootCapabilityProbe(
    private val runner: RootCommandRunner = ProcessRootCommandRunner(),
    private val now: () -> Long = System::currentTimeMillis,
) : RootProbe {
    override suspend fun check(): RootCapability {
        val result = try {
            runner.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RootCommandResult.Failed
        }
        return classifyRootResult(result).copy(checkedAtEpochMillis = now())
    }
}

internal fun classifyRootResult(result: RootCommandResult): RootCapability = when (result) {
    RootCommandResult.Unavailable -> RootCapability(
        RootState.ROOT_UNAVAILABLE,
        "无法找到可运行的 su 入口；当前 Root 后端不可用，这不等于设备一定未 Root。",
    )

    RootCommandResult.TimedOut -> RootCapability(
        RootState.UNKNOWN,
        "检查超时，未确认是否授权。已停止本次探测；Root 管理器的授权状态仍由系统管理。",
    )

    RootCommandResult.Failed -> RootCapability(
        RootState.UNKNOWN,
        "检查未能完成，无法确认 Root 授权。不会把运行错误当作用户拒绝。",
    )

    is RootCommandResult.Completed -> when {
        result.outputTruncated -> RootCapability(
            RootState.UNKNOWN,
            "检查输出超过限制，无法可靠确认 Root 授权。",
        )

        result.exitCode == 0 && result.stdout.trim() == "0" -> RootCapability(
            RootState.ROOT_GRANTED,
            "本次只读检查确认 UID 为 0。此结果只代表本次检查，后续执行仍需验证权限。",
        )

        result.exitCode != 0 && result.stdout.isBlank() &&
            result.stderr.trim().lowercase() in explicitSuDenials -> RootCapability(
            RootState.ROOT_DENIED,
            "本次 su 调用明确返回拒绝访问，未获得 Root 权限。",
        )

        else -> RootCapability(
            RootState.UNKNOWN,
            "命令已退出，但结果不足以确认 Root 授权或明确拒绝。",
        )
    }
}

// Deliberately narrow: a generic non-zero exit or an error from `id` is not an authorization denial.
private val explicitSuDenials = setOf(
    "permission denied",
    "su: permission denied",
    "access denied",
    "su: access denied",
    "request rejected",
    "su: request rejected",
)

internal class ProcessRootCommandRunner(
    private val timeoutMillis: Long = 15_000,
    private val maxOutputBytes: Int = 4_096,
    private val startProcess: () -> Process = {
        ProcessBuilder("su", "-c", "id -u").start()
    },
) : RootCommandRunner {
    init {
        require(timeoutMillis > 0)
        require(maxOutputBytes > 0)
    }

    override suspend fun run(): RootCommandResult = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    val process = try {
                        startProcess()
                    } catch (error: IOException) {
                        // ENOENT is the only launch failure that proves this entry point is unavailable.
                        return@coroutineScope if (Regex("\\berror=2(?:\\D|$)").containsMatchIn(error.message.orEmpty())) {
                            RootCommandResult.Unavailable
                        } else {
                            RootCommandResult.Failed
                        }
                    }
                    try {
                        val stdout = async(Dispatchers.IO) { runInterruptible { readBounded(process.inputStream) } }
                        val stderr = async(Dispatchers.IO) { runInterruptible { readBounded(process.errorStream) } }
                        val exitCode = runInterruptible { process.waitFor() }
                        val out = stdout.await()
                        val err = stderr.await()
                        RootCommandResult.Completed(exitCode, out.text, err.text, out.truncated || err.truncated)
                    } finally {
                        // Destroy before closing pipes: a child blocked on I/O must not keep STOP waiting.
                        runCatching { process.destroy() }
                        runCatching { if (process.isAlive) process.destroyForcibly() }
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                        runCatching { process.outputStream.close() }
                    }
                }
            } ?: RootCommandResult.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RootCommandResult.Failed
        }
    }

    private fun readBounded(stream: InputStream): BoundedOutput {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        var truncated = false
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val keep = minOf(read, maxOutputBytes - output.size())
            if (keep > 0) output.write(buffer, 0, keep)
            if (keep < read) truncated = true
        }
        return BoundedOutput(output.toString(Charsets.UTF_8.name()), truncated)
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)
}
