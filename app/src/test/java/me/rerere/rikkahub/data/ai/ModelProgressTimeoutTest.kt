package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelProgressTimeoutTest {
    @Test(timeout = 15_000)
    fun `concurrent normal completions cannot fail when watchdog receive races cleanup`(): Unit = runBlocking {
        // Stress the real Default dispatcher: runBlocking alone serializes the old race away.
        // Interleaving is nondeterministic; no assertion depends on the old implementation
        // failing a particular iteration. Every completed model wait must remain successful.
        supervisorScope {
            List(8) {
                async(Dispatchers.Default) {
                    repeat(5_000) {
                        assertEquals("complete", withModelProgressTimeout(60_000) { progress ->
                            progress()
                            yield()
                            progress()
                            "complete"
                        })
                    }
                }
            }.awaitAll()
        }
    }

    @Test(timeout = 5_000)
    fun `model failure preserves its type message and original cause after watchdog cleanup`() = runBlocking {
        val original = IllegalStateException("synthetic provider failure")
        try {
            withModelProgressTimeout(2_000) { progress ->
                progress()
                yield()
                throw original
            }
            fail("Expected the original model failure")
        } catch (error: IllegalStateException) {
            assertEquals(original.javaClass, error.javaClass)
            assertEquals(original.message, error.message)
            // Coroutine debug stack recovery may copy the throwable and attach the original
            // as its cause. Its causal identity must survive even when wrapper identity changes.
            assertTrue("The original provider failure must remain in the causal chain",
                generateSequence<Throwable>(error) { it.cause }.take(16).any { it === original })
        }
    }

    @Test(timeout = 5_000)
    fun `silent provider is cancelled before timeout returns and is never replayed`() = runBlocking {
        var requests = 0
        var cancelled = false
        try {
            withModelProgressTimeout(50) {
                callbackFlow<Unit> {
                    requests++
                    awaitClose { cancelled = true }
                }.collect()
            }
            fail("Expected a model progress timeout")
        } catch (error: ModelProgressTimeoutException) {
            assertEquals(PHONE_MODEL_PROGRESS_TIMEOUT_MESSAGE, error.message)
        }
        assertTrue(cancelled)
        assertEquals(1, requests)
    }

    @Test(timeout = 5_000)
    fun `new model progress resets the idle limit rather than limiting total generation`() = runBlocking {
        val result = withModelProgressTimeout(150) { progress ->
            repeat(5) {
                delay(50)
                progress()
            }
            "complete"
        }
        assertEquals("complete", result)
    }

    @Test(timeout = 5_000)
    fun `stream that stalls after making progress is cancelled`() = runBlocking {
        var cancelled = false
        try {
            withModelProgressTimeout(40) { progress ->
                progress()
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            fail("Expected a timeout after the last progress")
        } catch (_: ModelProgressTimeoutException) {
            assertTrue(cancelled)
        }
    }

    @Test(timeout = 5_000)
    fun `user cancellation is preserved and cancels the provider`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        var timedOut = false
        val request = async {
            try {
                withModelProgressTimeout(2_000) {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            } catch (error: ModelProgressTimeoutException) {
                timedOut = true
                throw error
            }
        }
        entered.await()
        request.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(timedOut)
    }

    @Test(timeout = 5_000)
    fun `ordinary chat has no watchdog`() = runBlocking {
        assertEquals("ordinary", withModelProgressTimeout(null) {
            delay(80)
            "ordinary"
        })
    }

    @Test(timeout = 5_000)
    fun `guarded model output preserves flow emission context`() = runBlocking {
        val chunks = flow {
            withModelProgressTimeout(150) { progress ->
                emit("first")
                progress()
                delay(10)
                emit("second")
            }
        }.toList()
        assertEquals(listOf("first", "second"), chunks)
    }

    @Test(timeout = 5_000)
    fun `completed model wait leaves no watchdog running during the following tool`() = runBlocking {
        val first = withModelProgressTimeout(30) { "first model" }
        // Tool execution happens outside each model wait and can exceed that wait's limit.
        delay(100)
        val second = withModelProgressTimeout(30) { "second model" }
        assertEquals("first model", first)
        assertEquals("second model", second)
    }
}
