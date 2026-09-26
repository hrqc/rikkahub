package me.rerere.rikkahub.data.mobileagent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DeviceCapabilityRepository internal constructor(
    private val passiveProbe: PassiveDeviceProbe,
    private val rootProbe: RootProbe,
    private val now: () -> Long = System::currentTimeMillis,
    private val rootUsagePreferences: RootUsagePreferences = MemoryRootUsagePreferences(),
    private val automaticRootProbe: RootProbe = rootProbe,
    private val rootCacheMillis: Long = 60_000,
) {
    constructor(context: Context, phoneBackend: PhoneBackend) : this(
        AndroidPassiveDeviceProbe(context.applicationContext, phoneBackend),
        RootCapabilityProbe(),
        rootUsagePreferences = LocalRootUsagePreferences(context.applicationContext),
        automaticRootProbe = RootCapabilityProbe(ProcessRootCommandRunner(timeoutMillis = 1_500)),
    )

    private val state = MutableStateFlow(DeviceCapabilities(rootUsage = rootUsagePreferences.read()))
    val capabilities: StateFlow<DeviceCapabilities> = state.asStateFlow()

    private val rootProbeLock = Mutex()
    private val activeRootProbe = AtomicReference<Job?>(null)
    private val cancellationEpoch = AtomicLong()
    private val policyGate = Any()
    private var lastSuccessfulCheckMillis: Long? = null

    /** Changes only the remembered choice. Turning it on never requests a fresh authorization. */
    fun setRootUsageEnabled(enabled: Boolean) = synchronized(policyGate) {
        val updated = state.value.rootUsage.copy(enabled = enabled)
        rootUsagePreferences.write(updated)
        state.update { it.copy(rootUsage = updated) }
        if (!enabled) cancelRootProbe()
    }

    /** A task may reuse a recent real check, or briefly recheck an already verified device. */
    suspend fun ensureRootForTask(): Boolean {
        synchronized(policyGate) {
            val usage = state.value.rootUsage
            if (!usage.enabled || !usage.previouslyVerified || !usage.autoVerificationAllowed) return false
            val checked = lastSuccessfulCheckMillis
            if (state.value.root.state == RootState.ROOT_GRANTED && checked != null && now() - checked in 0 until rootCacheMillis) return true
        }
        val result = checkRoot(automaticRootProbe, automatic = true) ?: return false
        return result.state == RootState.ROOT_GRANTED && state.value.rootUsage.enabled && state.value.rootUsage.autoVerificationAllowed
    }

    /** The executor rechecks this immediately before its fixed Root command. */
    fun canUseRootNow(): Boolean = state.value.let {
        it.rootUsage.enabled && it.rootUsage.autoVerificationAllowed && it.root.state == RootState.ROOT_GRANTED
    }

    /** Backend failure is terminal for automatic retries until the user explicitly verifies again. */
    fun reportRootUnavailable(): Unit = synchronized(policyGate) {
        cancellationEpoch.incrementAndGet()
        activeRootProbe.get()?.cancel()
        lastSuccessfulCheckMillis = null
        val updated = state.value.rootUsage.copy(autoVerificationAllowed = false)
        // Revoke in memory even if the device cannot persist preferences at this moment.
        state.update { it.copy(rootUsage = updated, root = RootCapability(detail = "Root 执行未能完成，已停用自动 Root。请检查管理器后重新验证。")) }
        runCatching { rootUsagePreferences.write(updated) }
        Unit
    }

    /** Reads local state only. Safe on page entry/resume; never calls the Root probe. */
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) { passiveProbe.capture() }
        state.update {
            it.copy(
                deviceManufacturer = snapshot.deviceManufacturer,
                deviceModel = snapshot.deviceModel,
                androidRelease = snapshot.androidRelease,
                apiLevel = snapshot.apiLevel,
                capabilities = snapshot.capabilities,
                refreshedAtEpochMillis = now(),
            )
        }
    }

    /** Call only from an explicit user action. Duplicate requests never queue a second dialog. */
    suspend fun requestRoot() {
        checkRoot(rootProbe, automatic = false)
    }

    private suspend fun checkRoot(selectedProbe: RootProbe, automatic: Boolean): RootCapability? {
        if (!rootProbeLock.tryLock()) return null
        val epoch = cancellationEpoch.get()
        try {
            if (automatic && !state.value.rootUsage.let { it.enabled && it.previouslyVerified && it.autoVerificationAllowed }) return null
            return coroutineScope {
                val probe = async(start = CoroutineStart.LAZY) { selectedProbe.check() }
                activeRootProbe.set(probe)
                state.update {
                    it.copy(
                        rootProbeRunning = true,
                        root = RootCapability(detail = if (automatic) "正在短时复核曾授予的 Root 权限。" else "正在检查 Root；系统可能显示授权提示。"),
                    )
                }
                try {
                    if (epoch != cancellationEpoch.get()) probe.cancel()
                    probe.start()
                    val result = probe.await().copy(checkedAtEpochMillis = now())
                    currentCoroutineContext().ensureActive()
                    if (epoch == cancellationEpoch.get()) {
                        synchronized(policyGate) {
                            if (epoch != cancellationEpoch.get()) throw CancellationException("Root probe cancelled")
                            val success = result.state == RootState.ROOT_GRANTED
                            val updated = state.value.rootUsage.copy(
                                previouslyVerified = state.value.rootUsage.previouslyVerified || success,
                                autoVerificationAllowed = success,
                            )
                            runCatching { rootUsagePreferences.write(updated) }
                            lastSuccessfulCheckMillis = now().takeIf { success }
                            state.update { it.copy(root = result, rootUsage = updated) }
                        }
                        result
                    } else {
                        throw CancellationException("Root probe cancelled")
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(policyGate) {
                        lastSuccessfulCheckMillis = null
                        val updated = state.value.rootUsage.copy(autoVerificationAllowed = false)
                        state.update { it.copy(rootUsage = updated, root = RootCapability(detail = "本次检查已取消，Root 授权状态未确认。")) }
                        runCatching { rootUsagePreferences.write(updated) }
                    }
                    throw cancelled
                } finally {
                    activeRootProbe.compareAndSet(probe, null)
                    state.update { it.copy(rootProbeRunning = false) }
                }
            }
        } finally {
            rootProbeLock.unlock()
        }
    }

    /** Cancels the owned probe, never the page's or application's whole coroutine scope. */
    fun cancelRootProbe() {
        cancellationEpoch.incrementAndGet()
        activeRootProbe.get()?.cancel()
    }
}
