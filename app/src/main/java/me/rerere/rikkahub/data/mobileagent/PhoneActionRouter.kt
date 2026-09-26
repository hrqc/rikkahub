package me.rerere.rikkahub.data.mobileagent

/** Only an explicit platform rejection can select a second executor for the same action. */
internal suspend fun standardActionThenRoot(
    standard: () -> Boolean,
    rootEligible: () -> Boolean,
    validateFresh: suspend () -> Unit,
    root: suspend () -> PhoneBackendResult,
): PhoneBackendResult {
    if (standard()) return PhoneBackendResult(true, "系统已接受本次动作，仍需重新观察确认结果。")
    if (!rootEligible()) return PhoneBackendResult(false, "系统未接受本次动作；请重新观察或由用户接手。")
    validateFresh()
    if (!rootEligible()) return PhoneBackendResult(false, "Root 当前不可用，未执行后备动作；请重新观察。")
    return root()
}
