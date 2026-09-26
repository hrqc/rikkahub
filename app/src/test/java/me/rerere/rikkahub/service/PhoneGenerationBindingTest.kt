package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGenerationBindingTest {
    private val run = PhoneSessionToken("conversation", "assistant", "grant", 3L)

    @Test fun `revocation cancels only an earlier epoch of the same grant`() {
        assertTrue(isRevokedPhoneGeneration(run, run.copy(epoch = 4L)))
        assertFalse(isRevokedPhoneGeneration(run, run))
        assertFalse(isRevokedPhoneGeneration(run, run.copy(epoch = 2L)))
    }

    @Test fun `old stop from a different identity cannot cancel replacement run`() {
        assertFalse(isRevokedPhoneGeneration(run, run.copy(epoch = 4L, sessionId = "other")))
        assertFalse(isRevokedPhoneGeneration(run, run.copy(epoch = 4L, conversationId = "other")))
        assertFalse(isRevokedPhoneGeneration(run, run.copy(epoch = 4L, assistantId = "other")))
    }
}
