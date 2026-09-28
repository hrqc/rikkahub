package me.rerere.rikkahub.data.mobileagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.shopping.ShoppingObservedEvidence
import me.rerere.rikkahub.data.shopping.ShoppingObservedNode
import java.util.UUID

/** In-memory user authorization. Model tools cannot start or resume it. */
class PhoneController(
    private val backend: PhoneBackend,
    private val ownPackageName: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val settleMillis: Long = 350,
    private val foregroundGraceMillis: Long = 750,
) {
    private val gate = Any()
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(PhoneSessionState())
    val state: StateFlow<PhoneSessionState> = mutableState.asStateFlow()
    val taskControls = PhoneTaskControlRegistry { state.value }
    private var epoch = 0L
    private var observation: PhoneObservation? = null
    private val evidence = ArrayDeque<StoredPhoneContent>()
    private var enteredTarget = false
    private var lastUnchangedAction: String? = null
    private var unchangedActions = 0
    private var partialScrolls = 0
    private var restrictedToNativeScroll = false
    private var deadline: Job? = null
    private var foregroundDeadline: Job? = null

    init {
        scope.launch {
            backend.state.collect { environment ->
                synchronized(gate) {
                    val current = state.value
                    if (current.status != PhoneSessionStatus.RUNNING) return@synchronized
                    if (environment.foregroundPackage != null) {
                        foregroundDeadline?.cancel()
                        foregroundDeadline = null
                    }
                    when {
                        !environment.connected -> end(PhoneSessionStatus.STOPPED, "无障碍服务已断开", current.token?.sessionId)
                        environment.locked -> pauseWith(PhoneSessionStatus.PAUSED, "手机已锁定，请解锁后手动恢复", current.token)
                        enteredTarget && environment.foregroundPackage == null -> {
                            // Window metadata can briefly disappear between activities in the same app.
                            // All observation/dispatch paths remain blocked until identity is known.
                            observation = null
                            if (foregroundDeadline?.isActive != true) {
                                val expected = current.token
                                foregroundDeadline = scope.launch {
                                    delay(foregroundGraceMillis)
                                    synchronized(gate) {
                                        if (backend.state.value.foregroundPackage == null) {
                                            pauseWith(PhoneSessionStatus.WAITING_FOR_FOREGROUND, "暂时无法确认目标窗口，请检查后恢复", expected)
                                        }
                                    }
                                }
                            }
                        }
                        enteredTarget && environment.foregroundPackage != current.targetPackage ->
                            pauseWith(PhoneSessionStatus.WAITING_FOR_FOREGROUND, "前台应用已改变，请确认后手动恢复", current.token)
                    }
                }
            }
        }
    }

    fun start(
        conversationId: String,
        assistantId: String,
        targetPackage: String,
        useRoot: Boolean = false,
        allowScreenshots: Boolean = false,
        replaceExisting: Boolean = true,
    ): PhoneSessionToken = synchronized(gate) {
        require(conversationId.isNotBlank() && assistantId.isNotBlank())
        if (!replaceExisting && state.value.status in setOf(PhoneSessionStatus.RUNNING, PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND)) {
            fail("SESSION_ALREADY_ACTIVE", "已有手机控制任务，请先停止当前任务")
        }
        if (targetPackage == ownPackageName || !backend.isTargetAllowed(targetPackage)) {
            fail("TARGET_NOT_ALLOWED", "请选择允许打开的其他应用，不能控制本应用或系统授权界面")
        }
        if (!backend.state.value.connected) fail("SERVICE_DISCONNECTED", "请先在系统设置中开启本应用的无障碍服务")
        if (backend.state.value.locked) fail("DEVICE_LOCKED", "请先解锁手机")
        if (allowScreenshots && !backend.supportsScreenshot) fail("SCREENSHOT_UNAVAILABLE", "当前设备尚不支持本次会话的受限窗口截图")
        stop("已替换为新的手机控制会话")
        val token = synchronized(gate) {
            PhoneSessionToken(conversationId, assistantId, newId(), ++epoch).also {
                observation = null
                foregroundDeadline?.cancel()
                foregroundDeadline = null
                enteredTarget = false
                lastUnchangedAction = null
                unchangedActions = 0
                partialScrolls = 0
                restrictedToNativeScroll = false
                mutableState.value = PhoneSessionState(
                    token = it, targetPackage = targetPackage, status = PhoneSessionStatus.RUNNING,
                    detail = "已允许本聊天控制选定应用", useRoot = useRoot,
                    allowScreenshots = allowScreenshots, startedAtMillis = now(),
                    audit = listOf(PhoneAuditEntry(now(), "start", "用户开启会话")), activityStartedAtMillis = now(),
                )
            }
        }
        if (!backend.showSessionNotice(token, targetPackage) { stopFromBackend(token, it) }) {
            stop("无法显示停止通知")
            fail("STOP_NOTIFICATION_REQUIRED", "请先允许应用通知，以便随时停止手机控制")
        }
        deadline = scope.launch {
            delay(state.value.durationLimitMillis)
            expire(token.sessionId)
        }
        authorize(token)
        token
    }

    fun activeToken(conversationId: String, assistantId: String): PhoneSessionToken? = synchronized(gate) {
        val current = mutableState.value
        current.token?.takeIf {
            current.status == PhoneSessionStatus.RUNNING && it.conversationId == conversationId &&
                it.assistantId == assistantId && withinTime(current)
        }
    }

    fun pause() = pauseWith(PhoneSessionStatus.PAUSED, "用户暂停，旧动作和页面快照已失效")

    /** Only the current grant may use a bounded history of actual, non-sensitive observations. */
    fun shoppingEvidence(token: PhoneSessionToken): List<ShoppingObservedEvidence> = synchronized(gate) {
        if (!valid(token)) emptyList() else evidence.map { it.evidence }
    }

    /** Reads saved text only; does not refresh snapshots, change budgets or access the device. */
    fun readObservedContent(token: PhoneSessionToken, snapshotId: String, cursor: String = "0"): PhoneObservedContentPage = synchronized(gate) {
        authorize(token)
        if (restrictedToNativeScroll) fail("SCROLL_ONLY", "当前页面仅可原生滚动，请先取得完整观察再读取正文")
        val saved = evidence.firstOrNull { it.evidence.snapshotId == snapshotId }
            ?: fail("CONTENT_UNAVAILABLE", "本次授权未保留该页正文，请重新观察；旧授权或已淘汰证据不可恢复")
        saved.page(cursor)
    }

    /** UI events carry the exact displayed epoch; validation and owner callbacks are atomic. */
    fun requestTaskControl(expected: PhoneSessionToken, action: PhoneTaskControl): Boolean = synchronized(gate) {
        taskControls.dispatch(expected, action)
    }

    /** Generation lifecycle only; never expose model text or hidden reasoning in status. */
    fun setModelWorking(token: PhoneSessionToken, working: Boolean) = synchronized(gate) {
        val current = state.value
        if (current.token != token || current.status != PhoneSessionStatus.RUNNING) return@synchronized
        mutableState.value = current.copy(
            modelWorking = working,
            activity = if (working) PhoneActivity.WAITING_MODEL else PhoneActivity.READY,
            activityStartedAtMillis = now(),
        )
    }

    /** A delayed preparation timeout/cancellation must never pause a newer authorization. */
    fun pauseIfCurrent(token: PhoneSessionToken, reason: String) =
        pauseWith(PhoneSessionStatus.PAUSED, reason, token)

    fun resume(useRoot: Boolean? = null): PhoneSessionToken = synchronized(gate) {
        val environment = backend.state.value
        if (!environment.connected) fail("SERVICE_DISCONNECTED", "无障碍服务尚未连接")
        if (environment.locked) fail("DEVICE_LOCKED", "请先解锁手机")
        val token = synchronized(gate) {
            val current = mutableState.value
            if (current.status !in setOf(PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND)) {
                fail("NOT_PAUSED", "当前会话不能恢复，请重新开启")
            }
            if (!withinTime(current) || current.actionsUsed >= current.actionLimit || current.observationsUsed >= current.observationLimit) {
                fail("BUDGET_EXHAUSTED", "本次会话已到预算上限，请检查结果后重新开启")
            }
            val updated = checkNotNull(current.token).copy(epoch = ++epoch)
            observation = null
            foregroundDeadline?.cancel()
            foregroundDeadline = null
            enteredTarget = false
            mutableState.value = current.copy(token = updated, status = PhoneSessionStatus.RUNNING, detail = "用户恢复，请重新观察页面",
                modelWorking = false, activity = PhoneActivity.READY, activityStartedAtMillis = now(), useRoot = useRoot ?: current.useRoot)
            updated
        }
        backend.invalidate()
        if (!backend.showSessionNotice(token, state.value.targetPackage) { stopFromBackend(token, it) }) {
            stop("无法显示停止通知")
            fail("STOP_NOTIFICATION_REQUIRED", "无法显示停止通知")
        }
        authorize(token)
        token
    }

    fun stopForConversation(conversationId: String) = synchronized(gate) {
        if (state.value.token?.conversationId == conversationId) stop()
    }

    fun stop(reason: String = "用户停止") = end(PhoneSessionStatus.STOPPED, reason)

    fun stopIfCurrent(token: PhoneSessionToken, reason: String = "用户停止") = synchronized(gate) {
        if (state.value.token == token) end(PhoneSessionStatus.STOPPED, reason, token.sessionId)
    }

    private fun stopFromBackend(token: PhoneSessionToken, reason: PhoneBackendStopReason) = end(
        PhoneSessionStatus.STOPPED,
        when (reason) {
            PhoneBackendStopReason.NOTIFICATION_STOP -> "用户从通知停止"
            PhoneBackendStopReason.SERVICE_INTERRUPTED -> "无障碍服务被系统中断，请检查后重新开启任务"
            PhoneBackendStopReason.SERVICE_DISCONNECTED -> "无障碍服务已断开"
            PhoneBackendStopReason.SERVICE_REPLACED -> "无障碍服务已重新连接，请重新开启任务"
        },
        token.sessionId,
    )

    private fun expire(sessionId: String) = end(PhoneSessionStatus.EXPIRED, "本次会话已到时间上限", sessionId)

    private fun end(status: PhoneSessionStatus, reason: String, sessionId: String? = null) = synchronized(gate) {
        val current = mutableState.value
        if (sessionId != null && current.token?.sessionId != sessionId) return@synchronized
        if (current.status in setOf(PhoneSessionStatus.IDLE, PhoneSessionStatus.STOPPED, PhoneSessionStatus.EXPIRED)) return@synchronized
        mutableState.value = current.copy(
            token = current.token?.copy(epoch = ++epoch), status = status, detail = reason,
            audit = (current.audit + PhoneAuditEntry(now(), "stop", reason)).takeLast(60),
            modelWorking = false, activity = PhoneActivity.READY, activityStartedAtMillis = now(),
        )
        current.token?.let { taskControls.clearSession(it.sessionId) }
        evidence.clear()
        observation = null
        enteredTarget = false
        deadline?.cancel()
        foregroundDeadline?.cancel()
        foregroundDeadline = null
        backend.invalidate()
        backend.endSessionNotice()
    }

    private fun pauseWith(status: PhoneSessionStatus, reason: String, expectedToken: PhoneSessionToken? = null) = synchronized(gate) {
        val current = mutableState.value
        if (expectedToken != null && current.token != expectedToken) return@synchronized
        if (current.status != PhoneSessionStatus.RUNNING) return@synchronized
        mutableState.value = current.copy(
            token = current.token?.copy(epoch = ++epoch), status = status, detail = reason,
            audit = (current.audit + PhoneAuditEntry(now(), "pause", reason)).takeLast(60),
            modelWorking = false, activity = PhoneActivity.READY, activityStartedAtMillis = now(),
        )
        evidence.clear()
        observation = null
        foregroundDeadline?.cancel()
        foregroundDeadline = null
        backend.invalidate()
    }

    suspend fun observe(token: PhoneSessionToken): PhoneObservation = operations.withLock {
        guarded(token) { capture(token) }
    }

    /** Host launch preparation only: no tree read, action snapshot, or shopping evidence. */
    internal suspend fun openForTaskPreparation(token: PhoneSessionToken): PhoneBackendResult = operations.withLock {
        guarded(token) {
            authorize(token)
            checkOpenApp(token)
            beginAction(token, null)
            val result = try {
                backend.execute(permit(token), null, PhoneAction.OpenApp)
            } catch (error: PhoneControlException) {
                // The launcher has no model tool result to retain this fixed preparation error.
                // Preserve it before the generic action guard can replace the pause reason.
                pauseIfCurrent(token, error.message ?: "目标应用启动失败，未发送模型请求")
                throw error
            }
            authorize(token)
            record(token, PhoneAction.OpenApp, if (result.accepted) "已请求启动，等待目标应用就绪" else "平台未接受启动")
            result
        }
    }

    suspend fun act(token: PhoneSessionToken, snapshotId: String?, action: PhoneAction): PhoneActionResult =
        act(token, snapshotId, action, null)

    internal suspend fun act(
        token: PhoneSessionToken,
        snapshotId: String?,
        action: PhoneAction,
        recorder: PhoneActionReceiptRecorder?,
    ): PhoneActionResult {
        val receipt = recorder?.forAction(token, action)
        try {
            return actWithReceipt(token, snapshotId, action, receipt)
        } catch (error: CancellationException) {
            receipt?.postObserveFailed("CANCELLED")
            throw error
        } catch (error: PhoneControlException) {
            receipt?.postObserveFailed(error.code)
            throw error
        }
    }

    private suspend fun actWithReceipt(
        token: PhoneSessionToken,
        snapshotId: String?,
        action: PhoneAction,
        receipt: PhoneActionReceiptRecorder?,
    ): PhoneActionResult = operations.withLock {
        guarded(token) {
            authorize(token)
            val before = if (action == PhoneAction.OpenApp) null else requireObservation(token, snapshotId, action)
            if (action == PhoneAction.OpenApp) {
                checkOpenApp(token)
            } else {
                checkAction(before!!, action)
            }
            beginAction(token, before)
            val result = backend.execute(permit(token), before, action)
            // Metadata only, before authorization checks or suspension can lose the returned result.
            receipt?.backendResult(result.accepted, result.executor)
            authorize(token)
            if (!result.accepted) {
                record(token, action, "平台未接受动作")
                return@guarded PhoneActionResult(false, detail = result.detail, executor = result.executor)
            }
            if (action == PhoneAction.Screenshot) {
                record(token, action, "已获取目标窗口截图")
                return@guarded PhoneActionResult(true, detail = "仅取得本次目标窗口截图", screenshotUri = result.screenshotUri,
                    executor = result.executor)
            }
            delay(settleMillis)
            val after = try {
                capture(token)
            } catch (error: PhoneControlException) {
                // The action may already have happened; never report task success without observation.
                receipt?.postObserveFailed(error.code)
                pauseForSafetyFailure(token, error)
                if (state.value.token?.sessionId != token.sessionId) throw error
                return@guarded PhoneActionResult(true, detail = "动作已提交，但未能验证后续页面；请检查并重新观察",
                    executor = result.executor)
            }
            val beforeFingerprint = result.beforeActionFingerprint ?: before?.fingerprint
            val changed = beforeFingerprint == null || beforeFingerprint != after.fingerprint
            val key = actionKey(action, before)
            synchronized(gate) {
                authorize(token)
                if (!after.truncated && !after.scrollOnly) receipt?.observed(changed)
                if (!changed && lastUnchangedAction == key) unchangedActions++ else unchangedActions = if (changed) 0 else 1
                lastUnchangedAction = if (changed) null else key
                record(token, action, if (changed) "页面已变化" else "页面未确认变化")
                if (unchangedActions >= 3) pauseWith(PhoneSessionStatus.PAUSED, "同一动作连续三次未观察到变化，请人工检查", token)
            }
            PhoneActionResult(true, changed, if (changed) "动作已提交且观察到页面变化；仍需核对任务目标" else "动作已提交，但页面未确认变化", after,
                executor = result.executor)
        }
    }

    private fun checkOpenApp(token: PhoneSessionToken) {
        if (synchronized(gate) { restrictedToNativeScroll }) {
            fail("SCROLL_ONLY", "当前页面检查不完整，仅允许原生节点滚动；请先重新观察。")
        }
        val foreground = backend.state.value.foregroundPackage
        if (foreground != ownPackageName && foreground != state.value.targetPackage) {
            pauseWith(PhoneSessionStatus.WAITING_FOR_FOREGROUND, "前台不是本应用或目标应用，请确认后恢复", token)
            fail("FOREGROUND_CHANGED", "需要用户确认当前前台应用")
        }
    }

    private fun beginAction(token: PhoneSessionToken, before: PhoneObservation?) = synchronized(gate) {
        authorize(token)
        val current = mutableState.value
        if (current.actionsUsed >= current.actionLimit) {
            pauseWith(PhoneSessionStatus.PAUSED, "已到本次操作次数上限", token)
            fail("ACTION_LIMIT", "已到本次操作次数上限")
        }
        if (current.observationsUsed >= current.observationLimit) {
            pauseWith(PhoneSessionStatus.PAUSED, "观察预算不足，不能验证下一步动作", token)
            fail("OBSERVATION_LIMIT", "观察预算不足")
        }
        if (before?.scrollOnly == true) {
            if (partialScrolls >= 5) {
                pauseWith(PhoneSessionStatus.PAUSED, "页面持续无法完整读取，已达到五次受限滚动上限", token)
                fail("SCROLL_ONLY_LIMIT", "已达到本任务受限滚动上限，请检查页面；未确认商品筛选完成")
            }
            partialScrolls++
        }
        observation = null // A snapshot is single-use, including rejected platform actions.
        mutableState.value = current.copy(actionsUsed = current.actionsUsed + 1,
            activity = PhoneActivity.ACTING, activityStartedAtMillis = now())
    }

    private suspend fun capture(token: PhoneSessionToken): PhoneObservation {
        authorize(token)
        requireForeground(token)
        synchronized(gate) {
            authorize(token)
            val current = mutableState.value
            if (current.observationsUsed >= current.observationLimit) {
                pauseWith(PhoneSessionStatus.PAUSED, "已到本次观察次数上限")
                fail("OBSERVATION_LIMIT", "已到本次观察次数上限")
            }
            mutableState.value = current.copy(observationsUsed = current.observationsUsed + 1,
                activity = PhoneActivity.OBSERVING, activityStartedAtMillis = now())
        }
        val raw = backend.observe(permit(token))
        authorize(token)
        if (raw.packageName != state.value.targetPackage) {
            pauseWith(PhoneSessionStatus.WAITING_FOR_FOREGROUND, "前台应用已改变", token)
            fail("FOREGROUND_CHANGED", "前台应用已改变，未提供界面内容")
        }
        if (raw.sensitive || raw.nodes.any { it.password || PhoneContentPolicy.isSensitive(it.text + "\n" + it.description) } ||
            raw.readOnlyContent?.nodes?.any { PhoneContentPolicy.isSensitive(it.text + "\n" + it.description) } == true) {
            pauseWith(PhoneSessionStatus.PAUSED, "当前页面涉及密码、支付或授权，请用户接手", token)
            fail("USER_HANDOVER_REQUIRED", "当前页面需要用户接手，未提供敏感界面内容")
        }
        val scrollOnly = raw.scrollOnly && canUseNativeScrollOnly(
            raw.sensitive, raw.truncated, raw.inspectionIssues, raw.nodes,
        )
        if ((raw.truncated && !scrollOnly) || (raw.scrollOnly && !scrollOnly)) {
            fail("INCOMPLETE_SCREEN", "无法完整确认页面安全状态，未发布本次页面或购物证据")
        }
        val safe = raw.copy(
            nodes = if (scrollOnly) raw.nodes.filter {
                it.scrollable && it.enabled && !it.password && !it.requiresUserConfirmation &&
                    !PhonePurchasePolicy.requiresUser(it.text + "\n" + it.description)
            }.map { it.copy(parentId = null, text = "", description = "", clickable = false, longClickable = false, editable = false) }.take(100)
            else raw.nodes.map { it.copy(text = it.text.take(300), description = it.description.take(300),
                requiresUserConfirmation = it.requiresUserConfirmation || PhonePurchasePolicy.requiresUser(it.text + "\n" + it.description)) }.take(100),
            previewTruncated = raw.previewTruncated || raw.nodes.size > 100,
            readOnlyContent = null,
            readOnlyContentAvailable = !scrollOnly,
            readOnlyContentTruncated = !scrollOnly && (raw.readOnlyContent?.let { it.truncated || it.nodes.any { node -> node.truncated } }
                ?: (raw.previewTruncated || raw.nodes.size > 100)),
        )
        synchronized(gate) {
            authorize(token)
            enteredTarget = true
            observation = safe
            restrictedToNativeScroll = scrollOnly
            if (!scrollOnly) {
                val content = raw.readOnlyContent?.nodes?.map {
                    ShoppingObservedNode(it.id, it.text.take(300), it.description.take(300),
                        truncated = it.truncated || it.text.length > 300 || it.description.length > 300)
                } ?: safe.nodes.map { ShoppingObservedNode(it.id, it.text, it.description) }
                // Copy the backend list and bound the retained prefix independently.
                val bounded = mutableListOf<ShoppingObservedNode>()
                var characters = 0
                for (node in content) {
                    val size = node.text.length + node.description.length
                    if (bounded.size >= 768 || characters + size > 64_000) break
                    bounded += node
                    characters += size
                }
                evidence.removeAll { it.evidence.snapshotId == safe.id }
                evidence.addLast(StoredPhoneContent(ShoppingObservedEvidence(safe.id, safe.packageName, bounded.toList()),
                    safe.readOnlyContentTruncated || bounded.size < content.size))
            }
            // Keep comparison evidence across list pages and detail visits; action snapshots
            // remain single-use and retain their independent 10-second freshness check.
            while (evidence.size > 32 || evidence.sumOf { page -> page.evidence.nodes.sumOf { it.text.length + it.description.length } } > 256_000) {
                evidence.removeFirst()
            }
            val current = mutableState.value
            val detail = if (scrollOnly) "页面检查不完整，仅可原生滚动；未登记商品证据" else "已观察目标页面"
            mutableState.value = current.copy(detail = detail,
                audit = (current.audit + PhoneAuditEntry(now(), "observe", detail)).takeLast(60))
        }
        return safe
    }

    private fun requireObservation(token: PhoneSessionToken, snapshotId: String?, action: PhoneAction): PhoneObservation {
        authorize(token)
        requireForeground(token)
        val current = synchronized(gate) { authorize(token); observation }
        if (snapshotId.isNullOrBlank() || current == null || current.id != snapshotId) fail("STALE_SNAPSHOT", "请先重新观察页面，旧快照不可执行")
        val age = now() - current.capturedAtMillis
        val environment = backend.state.value
        val revisionChanged = current.windowRevision != environment.windowRevision
        val windowChanged = current.windowId != environment.windowId
        val eligibleRefresh = action is PhoneAction.Click && backend.canRevalidateClick(token, current, action.nodeId)
        if (age !in 0..10_000 || windowChanged || (revisionChanged && !eligibleRefresh)) {
            throw PhoneControlException("STALE_SNAPSHOT", "页面已变化或快照超时，请重新观察；不得直接重放旧动作",
                PhoneSnapshotRejection(when {
                    age !in 0..10_000 -> PhoneSnapshotRejectionReason.AGE_LIMIT
                    windowChanged -> PhoneSnapshotRejectionReason.WINDOW_CHANGED
                    else -> PhoneSnapshotRejectionReason.REVISION_CHANGED
                }, age.coerceIn(-300_000, 300_000), revisionChanged, windowChanged))
        }
        return current
    }

    private fun checkAction(current: PhoneObservation, action: PhoneAction) {
        if (current.scrollOnly) {
            if (!canUseNativeScrollOnly(current.sensitive, current.truncated, current.inspectionIssues, current.nodes)) {
                fail("INCOMPLETE_SCREEN", "当前页面不具备受限滚动条件，请重新观察")
            }
            if (action !is PhoneAction.Scroll) fail("SCROLL_ONLY", "当前页面检查不完整，仅允许原生节点滚动，不能点击、输入或截图")
        } else if (current.truncated || current.sensitive) fail("INCOMPLETE_SCREEN", "无法完整确认页面安全状态，请用户接手")
        if (action == PhoneAction.Screenshot && !state.value.allowScreenshots) fail("SCREENSHOT_NOT_ALLOWED", "本次会话未允许截图")
        if (action is PhoneAction.Swipe && current.nodes.any { it.requiresUserConfirmation }) {
            fail("PURCHASE_CONFIRMATION_REQUIRED", "页面含需要用户确认的控件，不能使用坐标滑动；可读取或使用普通滚动节点。")
        }
        val id = when (action) {
            is PhoneAction.Click -> action.nodeId
            is PhoneAction.LongClick -> action.nodeId
            is PhoneAction.InputText -> action.nodeId
            is PhoneAction.Scroll -> action.nodeId
            else -> null
        }
        if (id != null) {
            val node = current.nodes.singleOrNull { it.id == id } ?: fail("UNKNOWN_NODE", "节点不属于本次快照")
            if (!node.enabled || node.password) fail("NODE_NOT_ALLOWED", "该节点不可操作")
            if (node.requiresUserConfirmation && action !is PhoneAction.Scroll) {
                fail("PURCHASE_CONFIRMATION_REQUIRED", "下单、付款、会员、试用或账户资产变更需用户亲自确认；未执行该动作")
            }
            when (action) {
                is PhoneAction.Click -> if (!node.clickable) fail("NODE_NOT_CLICKABLE", "该节点不可点击")
                is PhoneAction.LongClick -> if (!node.longClickable) fail("NODE_NOT_CLICKABLE", "该节点不支持长按")
                is PhoneAction.InputText -> {
                    if (!node.editable) fail("NODE_NOT_EDITABLE", "该节点不是可编辑输入框")
                    if (action.text.length > 2_000 || action.text.any { it == '\u0000' }) fail("INVALID_TEXT", "输入文本超出本次限制")
                }
                is PhoneAction.Scroll -> if (!node.scrollable) fail("NODE_NOT_SCROLLABLE", "该节点不可滚动")
                else -> Unit
            }
        }
    }

    private fun requireForeground(token: PhoneSessionToken) = synchronized(gate) {
        authorize(token)
        if (backend.state.value.foregroundPackage == null) {
            fail("WINDOW_TRANSITION", "目标窗口信息尚未就绪，请稍候重新观察；此时不执行动作")
        }
        if (backend.state.value.foregroundPackage != state.value.targetPackage) {
            // Before the first open_app, simply explain the required next step.
            if (enteredTarget) pauseWith(PhoneSessionStatus.WAITING_FOR_FOREGROUND, "目标应用不在前台", token)
            fail("TARGET_NOT_FOREGROUND", "请先打开已选定的目标应用，再读取界面")
        }
    }

    private fun permit(token: PhoneSessionToken): PhonePermit = synchronized(gate) {
        authorize(token)
        val current = state.value
        PhonePermit(token, current.targetPackage, { valid(token) }, current.useRoot, current.allowScreenshots)
    }

    private fun valid(token: PhoneSessionToken): Boolean = synchronized(gate) {
        val current = mutableState.value
        current.token == token && current.status == PhoneSessionStatus.RUNNING && withinTime(current) &&
            backend.state.value.connected && !backend.state.value.locked
    }

    private fun authorize(token: PhoneSessionToken) {
        if (!valid(token)) {
            if (state.value.token?.sessionId == token.sessionId && !withinTime(state.value)) expire(token.sessionId)
            fail("SESSION_INVALID", "手机控制已暂停、停止或会话已失效，需要用户确认")
        }
    }

    private fun withinTime(current: PhoneSessionState): Boolean = now() - current.startedAtMillis in 0 until current.durationLimitMillis

    private suspend fun <T> guarded(token: PhoneSessionToken, operation: suspend () -> T): T {
        try {
            return withTimeout(20_000) { authorize(token); operation() }
        } catch (cancelled: CancellationException) {
            pauseWith(PhoneSessionStatus.PAUSED, "操作已取消或超时，请检查后恢复", token)
            throw cancelled
        } catch (failure: PhoneControlException) {
            synchronized(gate) {
                val current = state.value
                if (current.token == token && current.status == PhoneSessionStatus.RUNNING) {
                    mutableState.value = current.copy(
                        audit = (current.audit + PhoneAuditEntry(now(), "error", failure.code)).takeLast(60),
                    )
                }
            }
            pauseForSafetyFailure(token, failure)
            throw failure
        } catch (_: Exception) {
            pauseWith(PhoneSessionStatus.PAUSED, "操作未能完成，请人工检查", token)
            fail("OPERATION_FAILED", "操作未能完成，未确认任务成功")
        } finally {
            synchronized(gate) {
                val current = state.value
                if (current.token == token && current.status == PhoneSessionStatus.RUNNING) {
                    mutableState.value = current.copy(
                        activity = if (current.modelWorking) PhoneActivity.WAITING_MODEL else PhoneActivity.READY,
                        activityStartedAtMillis = now(),
                    )
                }
            }
        }
    }

    private fun pauseForSafetyFailure(token: PhoneSessionToken, failure: PhoneControlException) {
        val reason = when (failure.code) {
            "PAGE_UNSTABLE" -> "目标页面在有限次读取后仍不稳定，任务已暂停；请等待页面稳定后手动继续。"
            "INCOMPLETE_SCREEN" -> "未能完整检查目标页面，任务已暂停；请检查页面后手动继续。"
            "USER_REQUIRED", "USER_HANDOVER_REQUIRED", "PURCHASE_CONFIRMATION_REQUIRED", "ACTION_RESULT_UNKNOWN",
            "DEVICE_LOCKED", "FOREGROUND_CONFLICT", "STOP_UNAVAILABLE", "ACCESSIBILITY_DISCONNECTED" ->
                "当前环境需要用户接手，请检查后恢复"
            else -> return
        }
        // Backend retries have already finished. Revoke only this exact grant, including
        // when a submitted action could not be observed; never retry its dispatch here.
        pauseWith(PhoneSessionStatus.PAUSED, reason, token)
    }

    private fun actionKey(action: PhoneAction, before: PhoneObservation?): String {
        val id = when (action) {
            is PhoneAction.Click -> action.nodeId
            is PhoneAction.LongClick -> action.nodeId
            is PhoneAction.InputText -> action.nodeId
            is PhoneAction.Scroll -> action.nodeId
            else -> ""
        }
        val node = before?.nodes?.firstOrNull { it.id == id }
        return "${action.javaClass.simpleName}:${node?.viewId}:${node?.bounds}"
    }

    private fun record(token: PhoneSessionToken, action: PhoneAction, result: String) = synchronized(gate) {
        authorize(token)
        val current = mutableState.value
        mutableState.value = current.copy(audit = (current.audit + PhoneAuditEntry(now(), action.javaClass.simpleName, result)).takeLast(60))
    }

    /** Tests or an explicitly destroyed owner only; the app owns one instance for the process. */
    fun close() { stop("控制器已关闭"); scope.cancel() }

    private fun fail(code: String, message: String): Nothing = throw PhoneControlException(code, message)
}

/** Conservative handover markers, supplemented by Android password/data-sensitive flags. */
object PhoneContentPolicy {
    private val markers = listOf(
        "支付密码", "付款码", "验证码", "一次性密码", "短信验证",
        "指纹验证", "人脸验证", "生物识别", "解锁密码", "超级用户", "安装未知应用",
        "京东验证", "请点击下方按钮完成安全验证", "拖动滑块完成验证",
        "payment password", "one-time password", "verification code",
        "biometric", "enter pin", "enter password", "superuser", "sukisu", "magisk", "apatch",
    )
    fun isSensitive(text: String): Boolean = markers.any { text.contains(it, ignoreCase = true) }
}
