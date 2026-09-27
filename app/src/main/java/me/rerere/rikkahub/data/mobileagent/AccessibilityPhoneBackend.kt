package me.rerere.rikkahub.data.mobileagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.MobileAgentNotifications
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** The only Android bridge that can read nodes or dispatch phone actions. */
class AccessibilityPhoneBackend(
    context: Context,
    private val rootAllowed: () -> Boolean = { false },
    private val onRootUnavailable: () -> Unit = {},
) : PhoneBackend {
    private val context = context.applicationContext
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val notifications = MobileAgentNotifications(this.context)
    private val mutableState = MutableStateFlow(PhoneBackendState())
    override val state: StateFlow<PhoneBackendState> = mutableState.asStateFlow()
    override val supportsScreenshot: Boolean get() = Build.VERSION.SDK_INT >= 34
    private val revision = AtomicLong()
    private val windowIdentity = AtomicLong()
    private val operationLock = Mutex()
    private val reader = AccessibilityTreeReader()
    private val rootExecutor = ControlledRootExecutor()
    private val service = AtomicReference<AccessibilityService?>(null)
    private val activeNotice = AtomicReference<SessionNotice?>(null)
    private val snapshot = AtomicReference<Snapshot?>(null)
    // Accessed only on Main. Contains package metadata, never accessibility text or event.source.
    private val windowPackages = mutableMapOf<Int, String>()
    private var controlOverlayWindowId: Int? = null
    private val recentEvents = ArrayDeque<EventMetadata>()

    private data class SessionNotice(val token: PhoneSessionToken, val targetPackage: String, val onStop: (PhoneBackendStopReason) -> Unit)
    private data class Snapshot(val token: PhoneSessionToken, val observation: PhoneObservation, val tree: AndroidTreeCapture)
    private data class EventMetadata(val type: Int, val windowId: Int, val packageName: String?)

    override fun isTargetAllowed(packageName: String): Boolean {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) || packageName == context.packageName) return false
        if (packageName in forbiddenTargets) return false
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val label = context.packageManager.getApplicationLabel(info).toString().lowercase()
            info.enabled && !listOf("magisk", "kernelsu", "sukisu", "apatch").any(label::contains) &&
                context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }

    override fun showSessionNotice(token: PhoneSessionToken, targetPackage: String, onStop: (PhoneBackendStopReason) -> Unit): Boolean {
        if (!notifications.show(token, targetPackage)) return false
        activeNotice.set(SessionNotice(token, targetPackage, onStop))
        return true
    }

    override fun endSessionNotice() {
        activeNotice.set(null)
        notifications.cancel()
    }

    /** A stale notification from a previous session must not stop a later session. */
    fun stopFromNotification(sessionId: String) {
        activeNotice.get()?.takeIf { it.token.sessionId == sessionId }?.onStop?.invoke(PhoneBackendStopReason.NOTIFICATION_STOP)
    }

    fun onServiceConnected(connectedService: AccessibilityService) {
        if (service.getAndSet(connectedService) !== connectedService) {
            val stopPreviousSession = activeNotice.get()?.onStop
            invalidate()
            windowPackages.clear()
            controlOverlayWindowId = null
            stopPreviousSession?.invoke(PhoneBackendStopReason.SERVICE_REPLACED)
            mutableState.value = PhoneBackendState(connected = true, windowRevision = revision.get(), locked = isLocked())
        }
    }

    fun onServiceDisconnected(disconnectedService: AccessibilityService) {
        if (!service.compareAndSet(disconnectedService, null)) return
        val stopSession = activeNotice.get()?.onStop
        invalidate()
        windowPackages.clear()
        controlOverlayWindowId = null
        // Revoke synchronously with the actual cause before the state collector can stop it.
        stopSession?.invoke(PhoneBackendStopReason.SERVICE_DISCONNECTED)
        mutableState.value = PhoneBackendState(windowRevision = revision.get(), locked = isLocked())
    }

    fun onServiceInterrupted() {
        invalidate()
        activeNotice.get()?.onStop?.invoke(PhoneBackendStopReason.SERVICE_INTERRUPTED)
    }

    /** Main only. The overlay owner supplies its attached view's actual accessibility window ID. */
    fun setControlOverlayWindowId(windowId: Int?) {
        val attachedId = windowId?.takeIf { it >= 0 }
        if (controlOverlayWindowId == attachedId) return
        controlOverlayWindowId = attachedId
        revision.incrementAndGet()
        snapshot.set(null)
        refreshEnvironment()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventWindowId = event.windowId
        val packageName = event.packageName?.toString()?.take(256)
        recentEvents.addLast(EventMetadata(event.eventType, eventWindowId, packageName))
        if (recentEvents.size > 12) recentEvents.removeFirst()
        if (event.eventType !in observedEvents) return
        if (eventWindowId >= 0 && !packageName.isNullOrBlank()) windowPackages[eventWindowId] = packageName
        if (windowPackages.size > 64) windowPackages.clear()
        val previous = mutableState.value
        val structuralChange = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val ownOverlayEvent = eventWindowId == controlOverlayWindowId && withWindows { windows ->
            windows.firstOrNull { it.id == eventWindowId }
                ?.let { isPhoneControlOverlay(windowMetadata(it), controlOverlayWindowId) } == true
        }
        // Do not hide A -> B -> A inside a content retry when the event queue trails the live list.
        if (shouldChangePhoneWindowIdentity(eventWindowId, previous.windowId, structuralChange, ownOverlayEvent)) {
            windowIdentity.incrementAndGet()
        }
        if (shouldInvalidatePhoneWindowRevision(eventWindowId, previous.windowId, structuralChange, ownOverlayEvent)) {
            revision.incrementAndGet()
            snapshot.set(null)
        }
        refreshEnvironment()
        val current = mutableState.value
        if (current.locked || (activeNotice.get()?.targetPackage?.let { current.foregroundPackage != it } == true)) {
            rootExecutor.cancel()
        }
    }

    /** On-demand test diagnostics only: no nodes, window titles, event text, or persistent logs. */
    internal suspend fun diagnosticMetadata(): String = withContext(Dispatchers.Main.immediate) {
        val current = mutableState.value
        val connectedService = service.get()
        val serviceMetadata = runCatching {
            connectedService?.serviceInfo?.let { "flags=${it.flags},eventTypes=${it.eventTypes}" }
                ?: "unavailable"
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val windowsMetadata = runCatching {
            withWindows { windows ->
                val selectedId = selectedWindow(windows)?.id
                val entries = windows.take(32).joinToString { window ->
                    "{id=${window.id},type=${window.type},active=${window.isActive},focused=${window.isFocused}}"
                }
                "count=${windows.size},selectedId=$selectedId,selectedPackage=${selectedId?.let(windowPackages::get)},entries=[$entries]"
            }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val eventsMetadata = recentEvents.joinToString { event ->
            "{type=${AccessibilityEvent.eventTypeToString(event.type)},id=${event.windowId},package=${event.packageName}}"
        }
        "state={connected=${current.connected},revision=${current.windowRevision},windowId=${current.windowId}," +
            "package=${current.foregroundPackage},locked=${current.locked}};service={$serviceMetadata};" +
            "windows={$windowsMetadata};maps=$windowPackages;recentEvents=[$eventsMetadata]"
    }

    override fun invalidate() {
        windowIdentity.incrementAndGet()
        revision.incrementAndGet()
        snapshot.set(null)
        rootExecutor.cancel()
        mutableState.update { it.copy(windowRevision = revision.get()) }
    }

    override suspend fun observe(permit: PhonePermit): PhoneObservation = operationLock.withLock {
        withContext(Dispatchers.Main.immediate) {
            try {
                requirePermit(permit)
                val captured = readStablePhoneObservation(
                    nowMillis = SystemClock::elapsedRealtime,
                    version = {
                        requirePermit(permit)
                        val current = mutableState.value
                        PhoneObservationVersion(current.windowId, windowIdentity.get(), current.windowRevision)
                    },
                    capture = { version ->
                        val root = authorizedRoot(permit)
                        try {
                            val tree = withContext(Dispatchers.Default) {
                                val readContext = currentCoroutineContext()
                                if (!readContext.isActive || !validAtRevision(permit, version.revision) || !root.refresh()) {
                                    throw TreeReadAborted()
                                }
                                // Reacquiring a root can still return cached metadata. Refresh before
                                // capture, then verify identity before any node content is inspected.
                                if (root.windowId != version.windowId || root.packageName?.toString() != permit.targetPackage) {
                                    fail("FOREGROUND_CONFLICT", "刷新后目标窗口身份已改变，请重新观察。")
                                }
                                reader.capture(root, permit.targetPackage) {
                                    readContext.isActive && validAtRevision(permit, version.revision)
                                }
                            }
                            requireRevision(permit, version.revision)
                            if (Build.VERSION.SDK_INT >= 33 && shouldRetryPhoneInspection(
                                    tree.sensitive, tree.truncated, tree.inspectionIssues,
                                )
                            ) {
                                // Discard stale descendant metadata only for this verified target.
                                // The retry policy discards this entire capture; no old paths are reused.
                                service.get()?.clearCachedSubtree(root)
                            }
                            val observation = PhoneObservation(
                                id = UUID.randomUUID().toString(),
                                packageName = permit.targetPackage,
                                windowId = root.windowId,
                                windowRevision = version.revision,
                                capturedAtMillis = System.currentTimeMillis(),
                                nodes = if (tree.sensitive) tree.nodes.map { it.copy(text = "", description = "") } else tree.nodes,
                                truncated = tree.truncated,
                                sensitive = tree.sensitive,
                                fingerprint = tree.fingerprint,
                                previewTruncated = tree.previewTruncated,
                                inspectionIssues = tree.inspectionIssues,
                            )
                            Snapshot(permit.token, observation, tree)
                        } catch (_: TreeReadAborted) {
                            throw PhoneObservationInvalidated()
                        } catch (error: PhoneControlException) {
                            if (error.code == "STALE_WINDOW") throw PhoneObservationInvalidated()
                            throw error
                        } finally {
                            recycleNode(root)
                        }
                    },
                    shouldRetry = { captured ->
                        shouldRetryPhoneInspection(
                            captured.tree.sensitive, captured.tree.truncated, captured.tree.inspectionIssues,
                        )
                    },
                )
                // Nothing is published until a whole read finishes at its original revision.
                snapshot.set(captured)
                captured.observation
            } catch (error: PhoneControlException) {
                throw error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw PhoneControlException("OBSERVATION_UNAVAILABLE", "当前窗口暂时无法读取，请重新打开目标应用。")
            }
        }
    }

    override suspend fun execute(permit: PhonePermit, observation: PhoneObservation?, action: PhoneAction): PhoneBackendResult =
        operationLock.withLock {
            withContext(Dispatchers.Main.immediate) {
                try {
                    requirePermit(permit, requireTarget = action != PhoneAction.OpenApp)
                    if (action == PhoneAction.OpenApp) return@withContext openTarget(permit)
                    val stored = validatedSnapshot(permit, observation)
                    // Re-read the bounded target tree immediately before dispatch. A newly displayed
                    // payment/password prompt elsewhere in the window must invalidate this action too.
                    val root = authorizedRoot(permit)
                    try {
                        val latest = withContext(Dispatchers.Default) {
                            reader.capture(root, permit.targetPackage) {
                                validAtRevision(permit, stored.observation.windowRevision)
                            }
                        }
                        if (latest.sensitive || latest.truncated) {
                            throw PhoneControlException("USER_REQUIRED", "页面含敏感内容或未能完整检查，请由用户接手。")
                        }
                        if (latest.fingerprint != stored.tree.fingerprint) {
                            throw PhoneControlException("STALE_WINDOW", "页面内容已改变，请重新观察后操作。")
                        }
                        val actionNodeId = when (action) {
                            is PhoneAction.Click -> action.nodeId
                            is PhoneAction.LongClick -> action.nodeId
                            is PhoneAction.InputText -> action.nodeId
                            else -> null
                        }
                        if (actionNodeId != null && latest.nodes.any { it.id == actionNodeId && it.requiresUserConfirmation }) {
                            fail("PURCHASE_CONFIRMATION_REQUIRED", "该动作涉及下单、付款或账户权益变更，请用户亲自确认。")
                        }
                        requireRevision(permit, stored.observation.windowRevision)
                        when (action) {
                            PhoneAction.Screenshot -> takeTargetScreenshot(permit, stored.observation)
                            PhoneAction.Back -> standardActionThenRoot(
                                standard = { service.get()!!.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) },
                                rootEligible = { canUseRoot(permit) },
                                validateFresh = { requireRootFallbackSnapshot(permit, stored) },
                                root = { runRoot(permit, RootInputAction.Back, stored.observation.windowRevision) },
                            )
                            is PhoneAction.Swipe -> swipe(permit, action.direction, activeWindowBounds())
                            is PhoneAction.Click -> withNode(root, stored, action.nodeId, permit) { node ->
                                if (!node.isClickable) fail("ACTION_UNSUPPORTED", "该节点不支持点击。")
                                standardActionThenRoot(
                                    standard = { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) },
                                    rootEligible = { canUseRoot(permit) },
                                    validateFresh = { requireRootFallbackSnapshot(permit, stored) },
                                    root = {
                                        val point = nodeCenter(node)
                                        requireGestureRegion(point.first, point.second, point.first, point.second)
                                        runRoot(permit, RootInputAction.Tap(point.first, point.second), stored.observation.windowRevision)
                                    },
                                )
                            }
                            is PhoneAction.LongClick -> withNode(root, stored, action.nodeId, permit) { node ->
                                if (!node.isLongClickable) fail("ACTION_UNSUPPORTED", "该节点不支持长按。")
                                standardActionThenRoot(
                                    standard = { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) },
                                    rootEligible = { canUseRoot(permit) },
                                    validateFresh = { requireRootFallbackSnapshot(permit, stored) },
                                    root = {
                                        val point = nodeCenter(node)
                                        requireGestureRegion(point.first, point.second, point.first, point.second)
                                        runRoot(permit, RootInputAction.LongPress(point.first, point.second), stored.observation.windowRevision)
                                    },
                                )
                            }
                            is PhoneAction.InputText -> withNode(root, stored, action.nodeId, permit) { node ->
                                if (!node.isEditable || action.text.length > 2_000) fail("ACTION_UNSUPPORTED", "该节点不可编辑或输入长度超限。")
                                val arguments = Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                                }
                                accepted(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                            }
                            is PhoneAction.Scroll -> withNode(root, stored, action.nodeId, permit) { node ->
                                if (!node.isScrollable) fail("ACTION_UNSUPPORTED", "该节点不支持滚动。")
                                standardActionThenRoot(
                                    standard = { node.performAction(if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) },
                                    rootEligible = { canUseRoot(permit) },
                                    validateFresh = { requireRootFallbackSnapshot(permit, stored) },
                                    root = {
                                        val bounds = Rect().also(node::getBoundsInScreen)
                                        if (!bounds.intersect(activeWindowBounds())) fail("STALE_WINDOW", "节点已离开目标窗口。")
                                        swipe(permit, if (action.forward) PhoneSwipeDirection.UP else PhoneSwipeDirection.DOWN, bounds)
                                    },
                                )
                            }
                            PhoneAction.OpenApp -> error("Handled above")
                        }
                    } finally {
                        recycleNode(root)
                        snapshot.set(null) // An action is single-use, including failed or uncertain dispatch.
                    }
                } catch (_: TreeReadAborted) {
                    throw PhoneControlException("STALE_WINDOW", "窗口或执行许可已改变，请重新观察。")
                } catch (error: PhoneControlException) {
                    throw error
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw PhoneControlException("ACTION_FAILED", "系统未能完成本次动作，请重新观察。")
                }
            }
        }

    private fun isLocked(): Boolean = keyguard.isKeyguardLocked || keyguard.isDeviceLocked || !power.isInteractive

    private fun refreshEnvironment() {
        val connectedService = service.get()
        var windowId: Int? = null
        if (connectedService != null) {
            withWindows { windows ->
                windowId = selectedWindow(windows)?.id
            }
        }
        val packageName = windowId?.let(windowPackages::get)
        val locked = isLocked()
        val previous = mutableState.value
        if (previous.windowId != windowId || previous.foregroundPackage != packageName || previous.locked != locked) {
            windowIdentity.incrementAndGet()
            revision.incrementAndGet()
            snapshot.set(null)
        }
        mutableState.value = PhoneBackendState(connectedService != null, revision.get(), packageName, windowId, locked)
    }

    private fun requirePermit(permit: PhonePermit, requireTarget: Boolean = true) {
        val notice = activeNotice.get()
        if (!permit.isValid() || notice == null || notice.token != permit.token || notice.targetPackage != permit.targetPackage) {
            fail("SESSION_INACTIVE", "手机控制会话已停止或执行许可已失效。")
        }
        if (!notifications.canPost()) fail("STOP_UNAVAILABLE", "STOP 通知不可用，请恢复通知权限后重新开启会话。")
        if (service.get() == null) fail("ACCESSIBILITY_DISCONNECTED", "无障碍服务尚未连接。")
        refreshEnvironment()
        if (mutableState.value.locked) fail("DEVICE_LOCKED", "设备已锁屏，请先手动解锁。")
        if (requireTarget && mutableState.value.foregroundPackage == null) {
            fail("WINDOW_TRANSITION", "窗口正在切换，暂时无法确认目标应用，请稍后重新观察。")
        }
        if (requireTarget && mutableState.value.foregroundPackage != permit.targetPackage) {
            fail("FOREGROUND_CONFLICT", "前台已不是获准目标应用，请手动回到目标应用。")
        }
        if (!requireTarget && mutableState.value.foregroundPackage !in setOf(context.packageName, permit.targetPackage)) {
            fail("FOREGROUND_CONFLICT", "前台不是本应用或目标应用，请用户确认后继续。")
        }
    }

    private fun validAtRevision(permit: PhonePermit, expected: Long): Boolean =
        permit.isValid() && activeNotice.get()?.token == permit.token && service.get() != null &&
            !isLocked() && revision.get() == expected && mutableState.value.foregroundPackage == permit.targetPackage

    private fun requireRevision(permit: PhonePermit, expected: Long) {
        requirePermit(permit)
        if (!validAtRevision(permit, expected)) fail("STALE_WINDOW", "窗口已改变，请重新观察。")
    }

    private fun authorizedRoot(permit: PhonePermit): AccessibilityNodeInfo {
        requirePermit(permit)
        val expectedWindow = mutableState.value.windowId
        // The active root can be our STOP overlay. Read only the selected, package-verified window.
        val root = withWindows { windows ->
            val target = selectedWindow(windows)
                ?.takeIf { it.id == expectedWindow && windowPackages[it.id] == permit.targetPackage }
                ?: fail("FOREGROUND_CONFLICT", "活动窗口与获准目标不符，已取消读取。")
            if (Build.VERSION.SDK_INT >= 33) target.getRoot(0) else target.root
        }
        if (root == null) fail("WINDOW_UNAVAILABLE", "当前没有可读取的目标窗口。")
        // Window changes can race the metadata read. Inspect only identity before any contents.
        if (root.windowId != expectedWindow || root.packageName?.toString() != permit.targetPackage || !permit.isValid()) {
            recycleNode(root)
            fail("FOREGROUND_CONFLICT", "活动窗口与获准目标不符，已取消读取。")
        }
        return root
    }

    private fun validatedSnapshot(permit: PhonePermit, observation: PhoneObservation?): Snapshot {
        val stored = snapshot.get() ?: fail("STALE_SNAPSHOT", "界面快照已失效，请重新观察。")
        if (observation == null || stored.token != permit.token || stored.observation != observation ||
            System.currentTimeMillis() - observation.capturedAtMillis !in 0..15_000) {
            fail("STALE_SNAPSHOT", "界面快照不属于当前会话或已过期。")
        }
        requireRevision(permit, observation.windowRevision)
        if (observation.sensitive || observation.truncated) fail("USER_REQUIRED", "页面含敏感内容或未能完整检查，请由用户接手。")
        return stored
    }

    private suspend fun withNode(root: AccessibilityNodeInfo, stored: Snapshot, nodeId: String, permit: PhonePermit, action: suspend (AccessibilityNodeInfo) -> PhoneBackendResult): PhoneBackendResult {
        val handle = stored.tree.handles[nodeId] ?: fail("NODE_UNAVAILABLE", "节点不在当前快照中。")
        if (handle.signature.sensitive || !handle.signature.enabled) fail("USER_REQUIRED", "敏感或不可用节点不能自动操作。")
        val node = withContext(Dispatchers.Default) {
            reader.resolve(root, handle) { validAtRevision(permit, stored.observation.windowRevision) }
        }
            ?: fail("STALE_NODE", "节点已改变，请重新观察。")
        try {
            requireRevision(permit, stored.observation.windowRevision)
            if (node.isPassword || !node.isEnabled) fail("USER_REQUIRED", "敏感或不可用节点不能自动操作。")
            return action(node)
        } finally {
            recycleNode(node)
        }
    }

    private fun openTarget(permit: PhonePermit): PhoneBackendResult {
        if (!isTargetAllowed(permit.targetPackage)) fail("TARGET_NOT_ALLOWED", "该应用不可作为自动控制目标。")
        requirePermit(permit, requireTarget = false)
        val intent = context.packageManager.getLaunchIntentForPackage(permit.targetPackage)
            ?: fail("TARGET_UNAVAILABLE", "目标应用没有可启动页面。")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        snapshot.set(null)
        return PhoneBackendResult(true, "已请求打开目标应用，请等待前台切换后观察。")
    }

    private fun canUseRoot(permit: PhonePermit): Boolean = permit.useRoot && rootAllowed()

    /** A false node result does not entitle us to reuse a changed or incompletely inspected page. */
    private suspend fun requireRootFallbackSnapshot(permit: PhonePermit, stored: Snapshot) {
        requireRevision(permit, stored.observation.windowRevision)
        val root = authorizedRoot(permit)
        try {
            val latest = withContext(Dispatchers.Default) {
                reader.capture(root, permit.targetPackage) { validAtRevision(permit, stored.observation.windowRevision) }
            }
            if (latest.sensitive || latest.truncated) fail("USER_REQUIRED", "页面需要用户接手，未使用 Root 后备动作。")
            if (latest.fingerprint != stored.tree.fingerprint) fail("STALE_WINDOW", "页面已经变化，未使用 Root 重复动作。")
            requireRevision(permit, stored.observation.windowRevision)
        } finally { recycleNode(root) }
    }

    private suspend fun runRoot(permit: PhonePermit, action: RootInputAction, expectedRevision: Long = revision.get()): PhoneBackendResult {
        requireRevision(permit, expectedRevision)
        if (!canUseRoot(permit)) return PhoneBackendResult(false, "Root 当前不可用，未执行 Root 动作；请重新观察。")
        val result = rootExecutor.execute(action, canStart = { canUseRoot(permit) && validAtRevision(permit, expectedRevision) }) {
            permit.isValid() && activeNotice.get()?.token == permit.token && !isLocked() &&
                mutableState.value.foregroundPackage == permit.targetPackage && canUseRoot(permit)
        }
        if (!result.accepted && permit.isValid() && result.failure in setOf(
                RootInputFailure.COMMAND_FAILED, RootInputFailure.TIMEOUT, RootInputFailure.UNAVAILABLE, RootInputFailure.OUTPUT_LIMIT,
            )
        ) onRootUnavailable()
        if (result.failure in setOf(RootInputFailure.COMMAND_FAILED, RootInputFailure.TIMEOUT, RootInputFailure.OUTPUT_LIMIT)) {
            fail("ACTION_RESULT_UNKNOWN", "Root 动作已尝试但未能确认结果，请重新观察核对；不要重复提交。")
        }
        return PhoneBackendResult(result.accepted, result.detail)
    }

    private suspend fun swipe(permit: PhonePermit, direction: PhoneSwipeDirection, bounds: Rect): PhoneBackendResult {
        if (snapshot.get()?.tree?.nodes?.any { it.requiresUserConfirmation } == true) {
            fail("PURCHASE_CONFIRMATION_REQUIRED", "当前页面包含需要用户确认的交易动作，未执行坐标滑动；请使用安全节点滚动或由用户接手。")
        }
        if (bounds.width() < 24 || bounds.height() < 24) fail("ACTION_UNSUPPORTED", "目标区域太小，无法安全滑动。")
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val left = bounds.left + bounds.width() / 4
        val right = bounds.right - bounds.width() / 4
        val top = bounds.top + bounds.height() / 4
        val bottom = bounds.bottom - bounds.height() / 4
        val points = when (direction) {
            PhoneSwipeDirection.UP -> intArrayOf(centerX, bottom, centerX, top)
            PhoneSwipeDirection.DOWN -> intArrayOf(centerX, top, centerX, bottom)
            PhoneSwipeDirection.LEFT -> intArrayOf(right, centerY, left, centerY)
            PhoneSwipeDirection.RIGHT -> intArrayOf(left, centerY, right, centerY)
        }
        requireGestureRegion(points[0], points[1], points[2], points[3])
        requirePermit(permit)
        if (canUseRoot(permit)) return runRoot(permit, RootInputAction.Swipe(points[0], points[1], points[2], points[3]))
        val path = Path().apply { moveTo(points[0].toFloat(), points[1].toFloat()); lineTo(points[2].toFloat(), points[3].toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        val completed = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                val dispatched = service.get()?.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, null) == true
                if (!dispatched && continuation.isActive) continuation.resume(false)
            }
        } ?: false
        if (!permit.isValid()) fail("SESSION_INACTIVE", "动作等待期间会话已停止，请手动检查界面。")
        return accepted(completed)
    }

    private fun nodeCenter(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.intersect(activeWindowBounds()) || bounds.isEmpty) fail("STALE_NODE", "节点已离开目标窗口。")
        return bounds.centerX() to bounds.centerY()
    }

    private fun activeWindowBounds(): Rect = withWindows { windows ->
        val window = selectedWindow(windows)?.takeIf { it.id == mutableState.value.windowId }
            ?: fail("FOREGROUND_CONFLICT", "目标窗口不再活动。")
        Rect().also(window::getBoundsInScreen)
    }

    /** Coordinate input must not hit an IME, permission dialog, or any overlay, including our STOP. */
    private fun requireGestureRegion(x1: Int, y1: Int, x2: Int, y2: Int) = withWindows { windows ->
        val target = selectedWindow(windows)?.takeIf { it.id == mutableState.value.windowId }
            ?: fail("FOREGROUND_CONFLICT", "目标窗口不再活动。")
        val area = Rect(min(x1, x2), min(y1, y2), max(x1, x2) + 1, max(y1, y2) + 1)
        val targetBounds = Rect().also(target::getBoundsInScreen)
        if (!targetBounds.contains(area) || area.left < 0 || area.top < 0) fail("INVALID_BOUNDS", "输入位置不在目标窗口中。")
        if (windows.any { it.id != target.id && it.layer > target.layer && Rect.intersects(Rect().also(it::getBoundsInScreen), area) }) {
            fail("FOREGROUND_CONFLICT", "操作区域被其他窗口覆盖，请由用户先关闭遮挡。")
        }
    }

    private fun selectedWindow(windows: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val selectedId = selectPhoneWindowId(windows.map(::windowMetadata), controlOverlayWindowId)
        return windows.firstOrNull { it.id == selectedId }
    }

    private fun windowMetadata(window: AccessibilityWindowInfo) = PhoneWindowMetadata(
        window.id, window.isActive, window.isFocused, window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
    )

    private inline fun <T> withWindows(block: (List<AccessibilityWindowInfo>) -> T): T {
        val windows = service.get()?.windows.orEmpty()
        try { return block(windows) } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) windows.forEach { it.recycle() }
        }
    }

    private suspend fun takeTargetScreenshot(permit: PhonePermit, observation: PhoneObservation): PhoneBackendResult {
        if (Build.VERSION.SDK_INT < 34 || !permit.allowScreenshots) fail("SCREENSHOT_UNAVAILABLE", "本会话未授权截图或系统不支持按目标窗口截图。")
        requireRevision(permit, observation.windowRevision)
        val result = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { continuation ->
                service.get()!!.takeScreenshotOfWindow(observation.windowId, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (continuation.isActive) {
                            continuation.resume(screenshot) { _, value, _ -> value?.hardwareBuffer?.close() }
                        } else screenshot.hardwareBuffer.close()
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } ?: fail("SCREENSHOT_UNAVAILABLE", "目标窗口无法截图，可能受系统安全策略保护；未改用全屏截图。")
        var file: File? = null
        try {
            requireRevision(permit, observation.windowRevision)
            val root = authorizedRoot(permit)
            try {
                val latest = withContext(Dispatchers.Default) {
                    reader.capture(root, permit.targetPackage) { validAtRevision(permit, observation.windowRevision) }
                }
                if (latest.sensitive || latest.truncated || latest.fingerprint != observation.fingerprint) {
                    fail("STALE_WINDOW", "截图期间界面已改变，已丢弃图片。")
                }
            } finally { recycleNode(root) }
            val bytes = withContext(Dispatchers.Default) {
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ?: fail("SCREENSHOT_UNAVAILABLE", "截图图像无法读取。")
                try {
                    val copied = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        ?: fail("SCREENSHOT_UNAVAILABLE", "截图图像无法复制。")
                    try {
                        val scale = minOf(1.0, 1_600.0 / max(copied.width, copied.height))
                        val scaled = if (scale < 1.0) Bitmap.createScaledBitmap(copied, max(1, (copied.width * scale).toInt()), max(1, (copied.height * scale).toInt()), true) else copied
                        try {
                            val output = LimitedImageOutput()
                            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)) fail("SCREENSHOT_UNAVAILABLE", "截图编码失败。")
                            output.toByteArray()
                        } finally { if (scaled !== copied) scaled.recycle() }
                    } finally { copied.recycle() }
                } finally { hardware.recycle() }
            }
            requireRevision(permit, observation.windowRevision)
            val folder = File(context.cacheDir, "mobile_agent_screenshots")
            val outputFile = File(folder, "${UUID.randomUUID()}.jpg")
            // Retain the path before the suspension point so cancellation cannot orphan image data.
            file = outputFile
            withContext(Dispatchers.IO) {
                if (!folder.isDirectory && !folder.mkdirs()) fail("SCREENSHOT_UNAVAILABLE", "无法创建本地截图缓存。")
                outputFile.writeBytes(bytes)
            }
            requireRevision(permit, observation.windowRevision)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile).toString()
            file = null // Retained only as this chat's local tool attachment.
            return PhoneBackendResult(true, "已截取获准目标窗口，图片保存在本地聊天缓存。", uri)
        } finally {
            result.hardwareBuffer.close()
            file?.delete()
        }
    }

    private class LimitedImageOutput : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (size().toLong() + len > 2_097_152) fail("SCREENSHOT_TOO_LARGE", "截图大小超限，已丢弃图片。")
            super.write(b, off, len)
        }
        override fun write(b: Int) {
            if (size() >= 2_097_152) fail("SCREENSHOT_TOO_LARGE", "截图大小超限，已丢弃图片。")
            super.write(b)
        }
    }

    private fun accepted(value: Boolean) = PhoneBackendResult(value,
        if (value) "系统已接受本次动作，仍需重新观察确认结果。" else "系统没有完成本次动作，请重新观察或由用户接手。")

    companion object {
        private fun fail(code: String, message: String): Nothing = throw PhoneControlException(code, message)
        private val forbiddenTargets = setOf(
            "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.oplus.securitypermission",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.intentresolver",
            "com.topjohnwu.magisk", "me.weishu.kernelsu", "com.sukisu.ultra", "me.bmax.apatch",
        )
        private val observedEvents = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
        )
    }
}
