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
) {
    constructor(context: Context) : this(
        AndroidPassiveDeviceProbe(context.applicationContext),
        RootCapabilityProbe(),
    )

    private val state = MutableStateFlow(DeviceCapabilities())
    val capabilities: StateFlow<DeviceCapabilities> = state.asStateFlow()

    private val rootProbeLock = Mutex()
    private val activeRootProbe = AtomicReference<Job?>(null)
    private val cancellationEpoch = AtomicLong()

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
        if (!rootProbeLock.tryLock()) return
        val epoch = cancellationEpoch.get()
        try {
            coroutineScope {
                val probe = async(start = CoroutineStart.LAZY) { rootProbe.check() }
                activeRootProbe.set(probe)
                state.update {
                    it.copy(
                        rootProbeRunning = true,
                        root = RootCapability(detail = "正在检查 Root；系统可能显示授权提示。"),
                    )
                }
                try {
                    if (epoch != cancellationEpoch.get()) probe.cancel()
                    probe.start()
                    val result = probe.await()
                    currentCoroutineContext().ensureActive()
                    if (epoch == cancellationEpoch.get()) {
                        state.update { it.copy(root = result) }
                    } else {
                        throw CancellationException("Root probe cancelled")
                    }
                } catch (cancelled: CancellationException) {
                    state.update {
                        it.copy(root = RootCapability(detail = "本次检查已取消，Root 授权状态未确认。"))
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
