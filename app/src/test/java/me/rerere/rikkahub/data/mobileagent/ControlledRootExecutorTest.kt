package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class ControlledRootExecutorTest {
    @Test
    fun `commands contain only fixed input operations and bounded coordinates`() {
        assertEquals("[ \"\$(id -u)\" = 0 ] && exec /system/bin/input touchscreen tap 12 34", rootInputCommand(RootInputAction.Tap(12, 34)))
        assertTrue(rootInputCommand(RootInputAction.LongPress(12, 34)).endsWith("touchscreen swipe 12 34 12 34 650"))
        assertTrue(rootInputCommand(RootInputAction.Swipe(1, 2, 3, 4)).endsWith("touchscreen swipe 1 2 3 4 350"))
        assertTrue(rootInputCommand(RootInputAction.Back).endsWith("keyevent 4"))
    }

    @Test
    fun `out of bounds coordinates never become a shell command`() {
        for (action in listOf(RootInputAction.Tap(-1, 0), RootInputAction.Tap(0, 65_536), RootInputAction.Swipe(0, 0, Int.MAX_VALUE, 0))) {
            assertTrue(runCatching { rootInputCommand(action) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `invalid permit never starts a process`() = runBlocking {
        val executor = ControlledRootExecutor(startProcess = { error("Must not start") })
        assertFalse(executor.execute(RootInputAction.Back) { false }.accepted)
    }

    @Test
    fun `STOP racing process registration destroys the new process`() = runBlocking {
        val child = CompletedProcess()
        lateinit var executor: ControlledRootExecutor
        executor = ControlledRootExecutor(startProcess = {
            executor.cancel()
            child
        })
        assertFalse(executor.execute(RootInputAction.Back) { true }.accepted)
        assertTrue(child.destroyed)
    }

    @Test
    fun `oversize unexpected output cannot count as a successful action`() = runBlocking {
        val child = CompletedProcess("x".repeat(8_192))
        val executor = ControlledRootExecutor(startProcess = { child })
        assertFalse(executor.execute(RootInputAction.Back) { true }.accepted)
        assertTrue(child.destroyed)
    }

    @Test
    fun `timeout destroys a real process with open output pipes`() = runBlocking {
        withTimeout(10_000) {
            val child = startBlockingChild()
            try {
                val result = ControlledRootExecutor(timeoutMillis = 250, startProcess = { child })
                    .execute(RootInputAction.Back) { true }
                assertFalse(result.accepted)
                assertTrue(runInterruptible(Dispatchers.IO) { child.waitFor(2, TimeUnit.SECONDS) })
            } finally { child.destroyForcibly() }
        }
    }

    @Test
    fun `caller cancellation destroys a real process and propagates cancellation`() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Process>()
            val request = async {
                ControlledRootExecutor(startProcess = { startBlockingChild().also { started.complete(it) } })
                    .execute(RootInputAction.Back) { true }
            }
            val child = started.await()
            try {
                request.cancelAndJoin()
                assertTrue(request.isCancelled)
                assertTrue(runInterruptible(Dispatchers.IO) { child.waitFor(2, TimeUnit.SECONDS) })
            } finally { child.destroyForcibly() }
        }
    }

    @Test
    fun `direct STOP terminates a real child without waiting for normal command completion`() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Process>()
            val executor = ControlledRootExecutor(startProcess = { startBlockingChild().also { started.complete(it) } })
            val request = async { executor.execute(RootInputAction.Back) { true } }
            val child = started.await()
            try {
                executor.cancel()
                assertFalse(request.await().accepted)
                assertTrue(runInterruptible(Dispatchers.IO) { child.waitFor(2, TimeUnit.SECONDS) })
            } finally { child.destroyForcibly() }
        }
    }

    @Test
    fun `STOP returns before a process destroy blocked on an IO monitor`() = runBlocking {
        withTimeout(10_000) {
            val waiting = CountDownLatch(1)
            val allowDestroy = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val child = object : Process() {
                override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor(): Int { waiting.countDown(); exited.await(); return 0 }
                override fun exitValue() = 0
                override fun isAlive() = exited.count > 0
                override fun destroy() { allowDestroy.await(); exited.countDown() }
            }
            val executor = ControlledRootExecutor(startProcess = { child })
            val request = async { executor.execute(RootInputAction.Back) { true } }
            try {
                assertTrue(runInterruptible(Dispatchers.IO) { waiting.await(2, TimeUnit.SECONDS) })
                withTimeout(1_000) { runInterruptible(Dispatchers.IO) { executor.cancel() } }
                // destroy is still blocked, but STOP has already returned.
                assertEquals(1L, exited.count)
            } finally {
                allowDestroy.countDown()
                request.await()
            }
        }
    }

    private fun startBlockingChild(): Process {
        val javaBin = File(System.getProperty("java.home"), "bin")
        val java = File(javaBin, "java.exe").takeIf { it.exists() } ?: File(javaBin, "java")
        val classpath = listOf(RootInputBlockingChild::class.java, Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain?.codeSource).location.toURI()).absolutePath }
            .distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(java.absolutePath, "-cp", classpath, RootInputBlockingChild::class.java.name).start()
    }

    private class CompletedProcess(private val output: String = "") : Process() {
        var destroyed = false
        override fun getInputStream() = ByteArrayInputStream(output.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() { destroyed = true }
    }
}

object RootInputBlockingChild {
    @JvmStatic
    fun main(args: Array<String>) {
        System.out.print("started")
        System.out.flush()
        Thread.sleep(60_000)
    }
}
