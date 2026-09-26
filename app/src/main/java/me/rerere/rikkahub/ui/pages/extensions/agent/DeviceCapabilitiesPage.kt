package me.rerere.rikkahub.ui.pages.extensions.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.mobileagent.CapabilityStatus
import me.rerere.rikkahub.data.mobileagent.RootState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun DeviceCapabilitiesPage(vm: DeviceCapabilitiesVM = koinViewModel()) {
    val snapshot by vm.capabilities.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var settingsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.refresh() }
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从系统设置返回只刷新现有状态，不触发 Root 或其他权限申请。
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.cancelRootProbe(showMessage = false)
        }
    }

    fun openNotificationSettings() {
        settingsError = null
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(notificationSettings) }
            .recoverCatching { context.startActivity(appSettings) }
            .onFailure { settingsError = "无法打开通知设置，请在系统设置中找到本应用并查看通知权限。" }
    }

    fun openAccessibilitySettings() {
        settingsError = null
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { settingsError = "无法打开无障碍设置，请在系统设置中找到本应用的无障碍服务并自行开启。" }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("设备能力") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = vm::refresh, enabled = !refreshing) {
                        Text(if (refreshing) "刷新中" else "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("手机 Agent · V1") }) {
                    item(
                        headlineContent = {
                            Text(
                                if (snapshot.refreshedAtEpochMillis == null) "正在读取设备信息"
                                else "${snapshot.deviceManufacturer} ${snapshot.deviceModel}"
                            )
                        },
                        supportingContent = {
                            if (snapshot.refreshedAtEpochMillis != null) {
                                Text("Android ${snapshot.androidRelease} · API ${snapshot.apiLevel}")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("能力检测") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("本页显示设备与服务的实际状态。请从聊天的更多菜单进入手机控制面板，选择目标应用并开始任务。刷新不会申请 Root 或无障碍权限。")
                                snapshot.refreshedAtEpochMillis?.let {
                                    Text("最近刷新：${formatCapabilityTime(it)}")
                                }
                            }
                        },
                    )
                }
            }

            if (message != null || settingsError != null) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("提示") },
                            supportingContent = { Text(settingsError ?: message.orEmpty()) },
                            trailingContent = {
                                TextButton(onClick = {
                                    settingsError = null
                                    vm.dismissMessage()
                                }) { Text("关闭") }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("设备 Root") }) {
                    item(
                        headlineContent = {
                            Text(if (snapshot.rootProbeRunning) "正在请求并验证 Root" else snapshot.root.state.label())
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(snapshot.root.detail)
                                snapshot.root.checkedAtEpochMillis?.let {
                                    Text("最近检测：${formatCapabilityTime(it)}")
                                }
                                Text("只有点击下方按钮才会向设备的 Root 管理器申请权限。拒绝或取消后仍可继续使用应用。")
                                if (snapshot.rootProbeRunning) {
                                    CircularProgressIndicator()
                                    TextButton(onClick = { vm.cancelRootProbe() }) { Text("取消检测") }
                                } else {
                                    TextButton(onClick = vm::requestRoot) {
                                        Text(if (snapshot.root.state == RootState.ROOT_GRANTED) "重新验证 Root" else "申请 Root 并验证")
                                    }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("PRoot 与设备 Root") },
                        supportingContent = {
                            Text("工作区中的 PRoot 是应用内的 Linux 运行环境。即使终端显示 root，也不代表已获得 Android 系统 Root，更不代表已能控制其他应用。")
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("权限与执行能力") }) {
                    snapshot.capabilities.forEach { capability ->
                        item(
                            headlineContent = { Text(capability.title) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(capability.status.label(), style = MaterialTheme.typography.labelLarge)
                                    Text(capability.detail)
                                    if (capability.id == "accessibility") {
                                        TextButton(onClick = ::openAccessibilitySettings) { Text("打开系统无障碍设置") }
                                    }
                                    if (capability.id == "notifications" && capability.status in setOf(
                                            CapabilityStatus.AVAILABLE,
                                            CapabilityStatus.PERMISSION_REQUIRED,
                                        )
                                    ) {
                                        TextButton(onClick = ::openNotificationSettings) { Text("打开通知设置") }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun RootState.label(): String = when (this) {
    RootState.UNKNOWN -> "尚未验证"
    RootState.ROOT_GRANTED -> "已验证 Root 权限"
    RootState.ROOT_DENIED -> "未获 Root 授权"
    RootState.ROOT_UNAVAILABLE -> "Root 不可用"
}

private fun CapabilityStatus.label(): String = when (this) {
    CapabilityStatus.AVAILABLE -> "可用"
    CapabilityStatus.PERMISSION_REQUIRED -> "需要授权"
    CapabilityStatus.UNAVAILABLE -> "不可用"
    CapabilityStatus.NOT_IMPLEMENTED -> "尚未接入"
    CapabilityStatus.UNKNOWN -> "待检测"
}

private fun formatCapabilityTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))
