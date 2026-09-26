package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.mobileagent.PhoneSessionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneToolAuthorizationTest {
    private val active = PhoneSessionToken("conversation", "assistant", "grant", 7L)

    @Test fun `ordinary chat has no phone tools even when another phone grant is active`() {
        assertNull(authorizedPhoneToolToken(null, active, "conversation", "assistant", proposing = false))
    }

    @Test fun `only explicit current matching token enables phone tools`() {
        assertEquals(active, authorizedPhoneToolToken(active, active, "conversation", "assistant", proposing = false))
        assertNull(authorizedPhoneToolToken(active.copy(epoch = 6L), active, "conversation", "assistant", proposing = false))
        assertNull(authorizedPhoneToolToken(active.copy(sessionId = "old"), active, "conversation", "assistant", proposing = false))
        assertNull(authorizedPhoneToolToken(active, null, "conversation", "assistant", proposing = false))
    }

    @Test fun `a token from another conversation or assistant cannot enable phone tools`() {
        assertNull(authorizedPhoneToolToken(active, active, "different", "assistant", proposing = false))
        assertNull(authorizedPhoneToolToken(active, active, "conversation", "different", proposing = false))
    }

    @Test fun `proposal generation cannot also receive device execution tools`() {
        assertNull(authorizedPhoneToolToken(active, active, "conversation", "assistant", proposing = true))
    }
}
