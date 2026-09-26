package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCapabilityProbeTest {
    @Test
    fun `only successful exact uid zero grants root`() {
        assertEquals(RootState.ROOT_GRANTED, result(0, "0\n").state)
        assertEquals(RootState.UNKNOWN, result(1, "0").state)
        assertEquals(RootState.UNKNOWN, result(0, "2000").state)
        assertEquals(RootState.UNKNOWN, result(0, "uid=0(root)").state)
        assertEquals(RootState.UNKNOWN, result(0, "0\nextra data").state)
    }

    @Test
    fun `denial requires an explicit su refusal rather than a generic exit failure`() {
        assertEquals(RootState.ROOT_DENIED, result(1, "", "su: Permission denied\n").state)
        assertEquals(RootState.ROOT_DENIED, result(1, "", "Request rejected").state)
        assertEquals(RootState.UNKNOWN, result(1, "", "id: Permission denied").state)
        assertEquals(RootState.UNKNOWN, result(127, "", "id: not found").state)
        assertEquals(RootState.UNKNOWN, result(1, "", "").state)
        assertEquals(RootState.UNKNOWN, result(0, "", "Permission denied").state)
    }

    @Test
    fun `missing entry point timeout and failure remain distinct`() {
        assertEquals(RootState.ROOT_UNAVAILABLE, classifyRootResult(RootCommandResult.Unavailable).state)
        assertEquals(RootState.UNKNOWN, classifyRootResult(RootCommandResult.TimedOut).state)
        assertEquals(RootState.UNKNOWN, classifyRootResult(RootCommandResult.Failed).state)
    }

    @Test
    fun `truncated output cannot grant root and raw output is not shown`() {
        val secret = "private-command-output"
        val truncated = classifyRootResult(RootCommandResult.Completed(0, "0", secret, true))
        assertEquals(RootState.UNKNOWN, truncated.state)
        assertFalse(truncated.detail.contains(secret))
        assertFalse(result(1, secret, secret).detail.contains(secret))
    }

    @Test
    fun `unexpected runner error becomes unknown and records check time`() = runBlocking {
        val probe = RootCapabilityProbe(RootCommandRunner { error("sensitive error") }, now = { 123L })
        val result = probe.check()
        assertEquals(RootState.UNKNOWN, result.state)
        assertEquals(123L, result.checkedAtEpochMillis)
        assertFalse(result.detail.contains("sensitive error"))
    }

    @Test
    fun `cancellation propagates instead of becoming denied or a generic failure`() = runBlocking {
        val probe = RootCapabilityProbe(RootCommandRunner { throw CancellationException("stop") })
        var cancelled = false
        try {
            probe.check()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun result(exit: Int, out: String, err: String = "") =
        classifyRootResult(RootCommandResult.Completed(exit, out, err))
}
