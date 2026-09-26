package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessRootCommandRunnerTest {
    @Test
    fun `absent su is unavailable but unrelated launch errors are unknown`() = runBlocking {
        val absent = ProcessRootCommandRunner(startProcess = { throw IOException("error=2, No such file") })
        val deniedByOs = ProcessRootCommandRunner(startProcess = { throw IOException("error=13, Permission denied") })
        val tooManyFiles = ProcessRootCommandRunner(startProcess = { throw IOException("error=24, Too many open files") })
        assertEquals(RootCommandResult.Unavailable, absent.run())
        assertEquals(RootCommandResult.Failed, deniedByOs.run())
        assertEquals(RootCommandResult.Failed, tooManyFiles.run())
    }

    @Test
    fun `both output streams are bounded and oversized output cannot grant root`() = runBlocking {
        val process = FakeProcess("0\n" + "x".repeat(50_000), "e".repeat(50_000), completed = true)
        val result = ProcessRootCommandRunner(maxOutputBytes = 32, startProcess = { process }).run()
            as RootCommandResult.Completed
        assertEquals(32, result.stdout.toByteArray().size)
        assertEquals(32, result.stderr.toByteArray().size)
        assertTrue(result.outputTruncated)
        assertEquals(RootState.UNKNOWN, classifyRootResult(result).state)
        assertTrue(process.destroyed)
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
    }

    @Test
    fun `timeout destroys process and is not classified as denial`() = runBlocking {
        withTimeout(5_000) {
            val process = FakeProcess()
            val result = ProcessRootCommandRunner(timeoutMillis = 100, startProcess = { process }).run()
            assertEquals(RootCommandResult.TimedOut, result)
            assertEquals(RootState.UNKNOWN, classifyRootResult(result).state)
            assertTrue(process.destroyed)
            assertTrue(process.stdout.closed)
            assertTrue(process.stderr.closed)
        }
    }

    @Test
    fun `caller cancellation destroys owned process and propagates cancellation`() = runBlocking {
        withTimeout(5_000) {
            val process = FakeProcess()
            val request = async { ProcessRootCommandRunner(startProcess = { process }).run() }
            assertTrue(runInterruptible(Dispatchers.IO) { process.waitStarted.await(2, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertTrue(process.destroyed)
            assertTrue(process.stdout.closed)
            assertTrue(process.stderr.closed)
        }
    }

    @Test
    fun `cancellation closes actual child pipes even when a read is blocked`() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<ObservedProcess>()
            val request = async {
                ProcessRootCommandRunner(startProcess = {
                    ObservedProcess(startBlockingChild()).also { started.complete(it) }
                }).run()
            }
            val process = started.await()
            try {
                assertTrue(runInterruptible(Dispatchers.IO) { process.blockingRead.await(5, TimeUnit.SECONDS) })
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertTrue(process.delegate.waitFor(2, TimeUnit.SECONDS))
            } finally {
                process.delegate.destroyForcibly()
            }
        }
    }

    @Test
    fun `timeout releases real process pipe readers`() = runBlocking {
        withTimeout(10_000) {
            val process = startBlockingChild()
            try {
                val result = ProcessRootCommandRunner(timeoutMillis = 500, startProcess = { process }).run()
                assertEquals(RootCommandResult.TimedOut, result)
                assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            } finally {
                process.destroyForcibly()
            }
        }
    }

    private fun startBlockingChild(): Process {
        val javaBin = File(System.getProperty("java.home"), "bin")
        val java = File(javaBin, "java.exe").takeIf { it.exists() } ?: File(javaBin, "java")
        // Gradle test workers load test classes separately from java.class.path.
        val childClasspath = listOf(RootProbeBlockingChild::class.java, Unit::class.java)
            .map { type ->
                File(requireNotNull(type.protectionDomain?.codeSource).location.toURI()).absolutePath
            }
            .distinct()
            .joinToString(File.pathSeparator)
        return ProcessBuilder(
            java.absolutePath, "-cp", childClasspath,
            RootProbeBlockingChild::class.java.name,
        ).start()
    }

    private class ObservedProcess(val delegate: Process) : Process() {
        val blockingRead = CountDownLatch(1)
        private val observedStdout = object : FilterInputStream(delegate.inputStream) {
            private var receivedData = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (receivedData) blockingRead.countDown()
                return super.read(buffer, offset, length).also { if (it > 0) receivedData = true }
            }
        }
        override fun getInputStream() = observedStdout
        override fun getErrorStream() = delegate.errorStream
        override fun getOutputStream() = delegate.outputStream
        override fun waitFor() = delegate.waitFor()
        override fun exitValue() = delegate.exitValue()
        override fun isAlive() = delegate.isAlive
        override fun destroy() = delegate.destroy()
        override fun destroyForcibly(): Process { delegate.destroyForcibly(); return this }
    }

    private class TrackingInputStream(content: String) : ByteArrayInputStream(content.toByteArray()) {
        @Volatile var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeProcess(out: String = "", err: String = "", completed: Boolean = false) : Process() {
        val stdout = TrackingInputStream(out)
        val stderr = TrackingInputStream(err)
        private val stdin = ByteArrayOutputStream()
        private val exited = CountDownLatch(if (completed) 0 else 1)
        val waitStarted = CountDownLatch(1)
        @Volatile var destroyed = false

        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun getOutputStream() = stdin
        override fun waitFor(): Int {
            waitStarted.countDown()
            exited.await()
            return 0
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun isAlive() = exited.count > 0
        override fun destroy() {
            destroyed = true
            exited.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}

/** Child used only by JVM lifecycle tests; it never invokes su or changes machine settings. */
internal object RootProbeBlockingChild {
    @JvmStatic
    fun main(args: Array<String>) {
        System.out.println("ready")
        System.out.flush()
        Thread.sleep(60_000)
    }
}
