package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionRouterTest {
    @Test fun `selected root scroll returns acceptance or rejection without native redispatch`() = runBlocking {
        for (accepted in listOf(true, false)) {
            var rootCalls = 0
            var nativeCalls = 0
            val rootResult = PhoneBackendResult(accepted, "fixed Root result", executor = PhoneActionExecutor.ROOT_INPUT)
            val result = preferredRootScrollOrStandard(
                root = { rootCalls++; rootResult },
                standard = { nativeCalls++; PhoneBackendResult(true, "must not run") },
            )
            assertSame(rootResult, result)
            assertEquals(PhoneActionExecutor.ROOT_INPUT, result.executor)
            assertEquals(1, rootCalls)
            assertEquals(0, nativeCalls)
        }
    }

    @Test fun `ineligible root scroll leaves the existing standard route intact`() = runBlocking {
        val order = mutableListOf<String>()
        val nativeResult = PhoneBackendResult(true, "native", executor = PhoneActionExecutor.ACCESSIBILITY)
        val result = preferredRootScrollOrStandard(
            root = { order += "Root not selected"; null },
            standard = { order += "native"; nativeResult },
        )
        assertSame(nativeResult, result)
        assertEquals(listOf("Root not selected", "native"), order)
    }

    @Test fun `root scroll unknown result or stale validation cannot fall back to native`() = runBlocking {
        for (code in listOf("ACTION_RESULT_UNKNOWN", "STALE_WINDOW", "STALE_SNAPSHOT")) {
            var nativeCalls = 0
            val error = runCatching {
                preferredRootScrollOrStandard(
                    root = { throw PhoneControlException(code, "fixed failure") },
                    standard = { nativeCalls++; PhoneBackendResult(true, "must not run") },
                )
            }.exceptionOrNull()
            assertTrue(error is PhoneControlException)
            assertEquals(code, (error as PhoneControlException).code)
            assertEquals(0, nativeCalls)
        }
    }

    @Test fun `cancelled root scroll never dispatches another executor`() = runBlocking {
        var nativeCalls = 0
        val error = runCatching {
            preferredRootScrollOrStandard(
                root = { throw CancellationException("stopped") },
                standard = { nativeCalls++; PhoneBackendResult(true, "must not run") },
            )
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, nativeCalls)
    }

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
