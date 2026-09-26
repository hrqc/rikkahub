package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionRouterTest {
    @Test fun `an accepted node action never consults or invokes root`() = runBlocking {
        val result = standardActionThenRoot(
            standard = { true },
            rootEligible = { error("Already accepted") },
            validateFresh = { error("Already accepted") },
            root = { error("Would duplicate the action") },
        )
        assertTrue(result.accepted)
    }

    @Test fun `unavailable root leaves rejected standard action alone`() = runBlocking {
        val result = standardActionThenRoot(
            standard = { false }, rootEligible = { false },
            validateFresh = { error("No root fallback") }, root = { error("No su allowed") },
        )
        assertFalse(result.accepted)
    }

    @Test fun `root fallback runs once only after explicit rejection and fresh validation`() = runBlocking {
        val order = mutableListOf<String>()
        val result = standardActionThenRoot(
            standard = { order += "standard rejected"; false },
            rootEligible = { order += "root eligible"; true },
            validateFresh = { order += "fresh snapshot" },
            root = { order += "root attempted"; PhoneBackendResult(false, "Attempt failed") },
        )
        assertFalse(result.accepted)
        assertEquals(listOf("standard rejected", "root eligible", "fresh snapshot", "root eligible", "root attempted"), order)
    }

    @Test fun `standard exception and stale snapshot never trigger a second dispatch`() = runBlocking {
        var rootCalls = 0
        val failure = runCatching {
            standardActionThenRoot(
                standard = { throw IllegalStateException("Unknown outcome") }, rootEligible = { true },
                validateFresh = {}, root = { rootCalls++; PhoneBackendResult(true, "wrong") },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        val stale = runCatching {
            standardActionThenRoot(
                standard = { false }, rootEligible = { true },
                validateFresh = { throw PhoneControlException("STALE_WINDOW", "changed") },
                root = { rootCalls++; PhoneBackendResult(true, "wrong") },
            )
        }.exceptionOrNull()
        assertTrue(stale is PhoneControlException)
        assertEquals(0, rootCalls)
    }

    @Test fun `root revocation during fresh read prevents fallback`() = runBlocking {
        var rootAllowed = true
        val result = standardActionThenRoot(
            standard = { false }, rootEligible = { rootAllowed },
            validateFresh = { rootAllowed = false }, root = { error("Root was revoked") },
        )
        assertFalse(result.accepted)
    }
}
