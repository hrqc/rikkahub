package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A content revision may be retried; an identity change must never be crossed by a read. */
internal data class PhoneObservationVersion(val windowId: Int?, val identity: Long, val revision: Long)

internal class PhoneObservationInvalidated : IllegalStateException()

/** A temporarily missing child can be reread; other inspection gaps retain their refusal. */
internal fun shouldRetryPhoneInspection(
    sensitive: Boolean,
    truncated: Boolean,
    inspectionIssues: List<String>,
): Boolean = !sensitive && truncated && inspectionIssues.isNotEmpty() &&
    inspectionIssues.all { it == "unavailable_child" }

/** Only read-only capture uses this helper. Dispatch and node resolution retain strict revisions. */
internal suspend fun <T : Any> readStablePhoneObservation(
    nowMillis: () -> Long,
    version: () -> PhoneObservationVersion,
    capture: suspend (PhoneObservationVersion) -> T,
    pause: suspend (Long) -> Unit = { delay(it) },
    shouldRetry: (T) -> Boolean = { false },
    acceptLastStable: (T) -> Boolean = { false },
): T {
    val startedAt = nowMillis()
    val initial = version()

    fun checkedVersion(): PhoneObservationVersion = version().also {
        if (it.windowId != initial.windowId || it.identity != initial.identity) {
            throw PhoneControlException("STALE_WINDOW", "读取期间目标窗口已切换，请重新观察。")
        }
    }

    fun exhausted(): Nothing = throw PhoneControlException(
        "PAGE_UNSTABLE", "目标页面在有限次重读或等待内仍无法稳定读取，请稍后重新观察；这不代表系统权限被关闭。",
    )

    return withTimeoutOrNull(4_500) {
        repeat(4) { attempt ->
            if (nowMillis() - startedAt >= 4_500) exhausted()
            val before = checkedVersion()
            try {
                val result = capture(before)
                if (checkedVersion().revision != before.revision) throw PhoneObservationInvalidated()
                if (nowMillis() - startedAt >= 4_500) exhausted()
                if (shouldRetry(result)) {
                    // A caller may grant a narrower capability for this final, completed read.
                    // Never retain an earlier result across invalidation, timeout or failed capture.
                    if (attempt != 3 || !acceptLastStable(result)) throw PhoneObservationInvalidated()
                    if (checkedVersion().revision != before.revision) throw PhoneObservationInvalidated()
                    if (nowMillis() - startedAt >= 4_500) exhausted()
                }
                return@withTimeoutOrNull result
            } catch (_: PhoneObservationInvalidated) {
                // This also rechecks STOP, lock, service and package before waiting or reading again.
                checkedVersion()
                if (attempt == 3 || nowMillis() - startedAt >= 4_500) exhausted()
                pause(120)
            }
        }
        exhausted()
    } ?: run {
        checkedVersion()
        exhausted()
    }
}

internal data class PhoneWindowMetadata(
    val id: Int,
    val active: Boolean,
    val focused: Boolean,
    val accessibilityOverlay: Boolean,
)

/** Ignore only the attached control view's verified, non-focusable accessibility window. */
internal fun selectPhoneWindowId(windows: List<PhoneWindowMetadata>, controlOverlayId: Int?): Int? {
    val eligible = windows.filterNot { isPhoneControlOverlay(it, controlOverlayId) }
    return (eligible.firstOrNull { it.active } ?: eligible.firstOrNull { it.focused })?.id
}

internal fun isPhoneControlOverlay(window: PhoneWindowMetadata, controlOverlayId: Int?): Boolean =
    controlOverlayId != null && window.id == controlOverlayId && window.accessibilityOverlay && !window.focused

internal fun shouldInvalidatePhoneWindowRevision(
    eventWindowId: Int,
    targetWindowId: Int?,
    structuralChange: Boolean,
    verifiedControlOverlay: Boolean,
): Boolean = !verifiedControlOverlay && (structuralChange || eventWindowId == targetWindowId)

/** The live window list can already be back at A when a queued event for B finally arrives. */
internal fun shouldChangePhoneWindowIdentity(
    eventWindowId: Int,
    targetWindowId: Int?,
    structuralChange: Boolean,
    verifiedControlOverlay: Boolean,
): Boolean = !verifiedControlOverlay && structuralChange && eventWindowId != targetWindowId
