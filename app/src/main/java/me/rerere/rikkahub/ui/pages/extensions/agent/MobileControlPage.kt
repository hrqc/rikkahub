package me.rerere.rikkahub.ui.pages.extensions.agent

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.mobileagent.PhoneSessionStatus
import me.rerere.rikkahub.data.mobileagent.RootState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MobileControlPage(conversationId: String, assistantId: String) {
    val vm: MobileControlVM = koinViewModel(
        key = "mobile-control-$conversationId-$assistantId",
        parameters = { parametersOf(conversationId, assistantId) },
    )
    val session by vm.session.collectAsStateWithLifecycle()
    val backend by vm.backendState.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val preparedSessionId by vm.preparedSessionId.collectAsStateWithLifecycle()
    val modelSessionId by vm.modelSessionId.collectAsStateWithLifecycle()
    val openingTarget by vm.openingTarget.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var task by rememberSaveable(conversationId, assistantId) { mutableStateOf("") }
    var targetPackage by rememberSaveable(conversationId, assistantId) { mutableStateOf("") }
    var useRoot by rememberSaveable(conversationId, assistantId) { mutableStateOf(false) }
    var allowScreenshots by rememberSaveable(conversationId, assistantId) { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showPrepareConfirmation by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    val ownsSession = session.token?.let {
        it.conversationId == conversationId && it.assistantId == assistantId
    } == true
    val activeSession = session.status.isActive()
    val otherSessionActive = activeSession && !ownsSession
    val preparedOnly = ownsSession && preparedSessionId == session.token?.sessionId
    val knownModelSession = ownsSession && modelSessionId == session.token?.sessionId
    val selectedApp = apps.firstOrNull {
        it.packageName == if (ownsSession && activeSession) session.targetPackage else targetPackage
    }
    val boundAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    val model = settings.findModelById(boundAssistant?.chatModelId ?: settings.chatModelId)
    val modelLabel = model?.let { it.displayName.ifBlank { it.modelId } } ?: "未选择"
    val bindingValid = boundAssistant != null && conversation.assistantId.toString() == assistantId
    val canPrepare = !activeSession && !busy && !openingTarget && !generating && backend.connected && !backend.locked &&
        selectedApp != null && bindingValid
    val canStart = canPrepare && task.isNotBlank() && model != null
    val canResume = ownsSession && !busy && !openingTarget && !generating && backend.connected && !backend.locked &&
        bindingValid && (preparedOnly || (knownModelSession && model != null)) && session.status in setOf(
        PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND,
    )

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSettings(intent: Intent, fallback: String) {
        settingsError = null
        runCatching { context.startActivity(intent) }.onFailure { settingsError = fallback }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机控制") },
                navigationIcon = { BackButton() },
                actions = { TextButton(onClick = vm::refresh, enabled = !appsLoading) { Text("刷新") } },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (preparedOnly) "仅准备控制：不会调用模型。打开目标应用需要另行点击。"
                        else "开始/恢复会把目标应用的可见内容交给当前模型，可能产生模型费用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        ownsSession && session.status == PhoneSessionStatus.RUNNING -> {
                            OutlinedButton(onClick = vm::pause, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("暂停")
                            }
                        }
                        ownsSession && session.status in setOf(PhoneSessionStatus.PAUSED, PhoneSessionStatus.WAITING_FOR_FOREGROUND) -> {
                            Button(onClick = vm::resume, enabled = canResume, modifier = Modifier.weight(1f)) {
                                Text(if (preparedOnly) "恢复控制（无模型）" else "恢复并调用模型")
                            }
                        }
                        else -> {
                            Button(
                                onClick = { vm.start(task, targetPackage, useRoot, allowScreenshots) },
                                enabled = canStart,
                                modifier = Modifier.weight(1f),
                            ) { Text("开始并调用模型") }
                        }
                    }
                    Button(
                        onClick = vm::stop,
                        enabled = ownsSession && (activeSession || generating || busy),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("STOP 停止") }
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("当前聊天") }) {
                    item(
                        headlineContent = { Text(conversation.title.ifBlank { "未命名聊天" }) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("助手：${boundAssistant?.name?.ifBlank { "未命名助手" } ?: "不可用"}")
                                Text("模型：$modelLabel")
                                if (!bindingValid) Text("会话与助手已变化，请返回聊天后重新进入。", color = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("执行状态") }) {
                    item(
                        headlineContent = {
                            Text(when {
                                otherSessionActive -> "其他聊天正在使用手机控制"
                                ownsSession && preparedOnly && session.status == PhoneSessionStatus.RUNNING -> "仅准备控制 · 已授权"
                                ownsSession -> session.status.label()
                                else -> "尚未开始"
                            })
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(when {
                                    otherSessionActive -> "请返回该聊天或使用通知中的 STOP 停止，再开始当前任务。"
                                    ownsSession -> session.detail
                                    else -> "选择目标应用并填写任务。只有点击开始后，才会允许当前聊天调用手机工具。"
                                })
                                if (ownsSession) Text("目标：${apps.firstOrNull { it.packageName == session.targetPackage }?.label ?: session.targetPackage}")
                                if (ownsSession && activeSession && !preparedOnly && !knownModelSession) {
                                    Text("当前面板无法确认本次任务的执行方式。请先 STOP 后重新授权，恢复入口暂不可用。")
                                }
                                Text("无障碍服务：${if (backend.connected) "已连接" else "未连接"}")
                                if (backend.connected && backend.locked) Text("设备当前锁定，请解锁后继续。")
                                if (openingTarget) Text("正在打开所选目标应用…")
                                else if (busy) Text("设备操作已撤销，正在结束本轮模型生成…")
                                else if (generating) Text("当前聊天正在生成。")
                                if (preparedOnly) {
                                    Text("准备控制不会读取页面、自动操作或调用模型。点击下方按钮后，会打开本次所选应用并在本地读取页面确认，不调用模型、不上传页面内容。")
                                    OutlinedButton(
                                        onClick = vm::openPreparedTarget,
                                        enabled = !busy && !openingTarget && session.status == PhoneSessionStatus.RUNNING,
                                    ) { Text("打开目标应用") }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("任务预算") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("动作 ${if (ownsSession) session.actionsUsed else 0}/${session.actionLimit} · 观察 ${if (ownsSession) session.observationsUsed else 0}/${session.observationLimit}")
                                Text("每次任务最多 ${session.durationLimitMillis / 60_000} 分钟。恢复不会重置已用预算，达到上限后不再执行新动作。")
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
                                TextButton(onClick = { settingsError = null; vm.dismissMessage() }) { Text("关闭") }
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text("准备本次任务") }) {
                    item(
                        headlineContent = { Text(selectedApp?.label ?: "选择目标应用") },
                        supportingContent = { Text(selectedApp?.packageName ?: "只允许操作所选应用；不会自动选择或启动应用。") },
                        trailingContent = {
                            TextButton(onClick = { showAppPicker = true }, enabled = !activeSession && !appsLoading) {
                                Text(if (appsLoading) "读取中" else "选择")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("要完成的任务") },
                        supportingContent = {
                            OutlinedTextField(
                                value = task,
                                onValueChange = { task = it.take(4_000) },
                                label = { Text("模型任务；仅准备控制时可留空") },
                                enabled = !activeSession,
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("本次可选能力") },
                        supportingContent = {
                            Column {
                                ControlOption(
                                    checked = if (ownsSession && activeSession) session.useRoot else useRoot,
                                    onCheckedChange = { useRoot = it },
                                    enabled = !activeSession && capabilities.root.state == RootState.ROOT_GRANTED,
                                    title = "Root 增强",
                                    detail = if (capabilities.root.state == RootState.ROOT_GRANTED) "仅授权本次任务使用已接入的固定 Root 动作。" else "默认关闭；需先在设备能力页手动验证 Root。",
                                )
                                ControlOption(
                                    checked = if (ownsSession && activeSession) session.allowScreenshots else allowScreenshots,
                                    onCheckedChange = { allowScreenshots = it },
                                    enabled = !activeSession && vm.supportsScreenshot,
                                    title = "允许必要截图",
                                    detail = if (vm.supportsScreenshot) "默认关闭。开启后，模型可在必要时请求目标应用画面。" else "当前系统不支持此截图后端。",
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("只检查本地控制准备") },
                        supportingContent = {
                            Column {
                                Text("无需配置模型，也不发送任务或屏幕内容。仅为当前聊天和所选应用建立有时间、次数限制的控制授权。")
                                OutlinedButton(onClick = { showPrepareConfirmation = true }, enabled = canPrepare) {
                                    Text("仅准备控制（不调用模型）")
                                }
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("屏幕内容与模型") }) {
                    item(
                        headlineContent = { Text("开始即授权本次任务") },
                        supportingContent = {
                            Text("任务内容与目标应用的可见页面文字将交给此助手当前选择的模型；允许截图时，还会交给模型请求的画面。开始和恢复会调用你配置的模型服务，可能产生相应费用。打开此面板、选择应用和刷新状态不会调用模型。")
                        },
                    )
                    item(
                        headlineContent = { Text("前台操作与停止") },
                        supportingContent = {
                            Text("手机操作可能切换到目标应用并占用屏幕。切换到其他应用或锁屏会暂停，无障碍服务断开会停止。请确认当前状态后手动恢复。STOP 会立即撤销手机操作授权并停止该聊天生成，通知中也有 STOP。")
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("系统授权") }) {
                    item(
                        headlineContent = { Text("由你在系统中开启") },
                        supportingContent = {
                            Column {
                                TextButton(onClick = {
                                    openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开无障碍设置，请自行在系统设置中开启本应用的无障碍服务。")
                                }) { Text("打开系统无障碍设置") }
                                TextButton(onClick = {
                                    openSettings(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                        "无法打开通知设置，请自行在系统设置中允许本应用的任务通知。",
                                    )
                                }) { Text("打开通知设置（显示 STOP）") }
                                TextButton(onClick = { navController.navigate(Screen.DeviceCapabilities) }) { Text("设备能力与 Root 检测") }
                            }
                        },
                    )
                }
            }
            if (ownsSession && session.audit.isNotEmpty()) {
                item {
                    CardGroup(title = { Text("最近操作") }) {
                        session.audit.takeLast(8).asReversed().forEach { entry ->
                            item(
                                headlineContent = { Text(entry.operation) },
                                supportingContent = { Text(entry.result) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        TargetAppPicker(apps, onSelect = { targetPackage = it.packageName; showAppPicker = false }, onDismiss = { showAppPicker = false })
    }
    if (showPrepareConfirmation) {
        AlertDialog(
            onDismissRequest = { showPrepareConfirmation = false },
            title = { Text("授权本次本地控制") },
            text = {
                Text("目标应用：${selectedApp?.label ?: targetPackage}\n\n确认后仅建立本次控制授权与 STOP 通知，不读取屏幕、不自动操作，也不调用模型。另行点击“打开目标应用”才会打开应用并在本地读取页面确认，内容不会发送给模型。Root 增强和截图仅按本页勾选项授权。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPrepareConfirmation = false
                        vm.prepare(targetPackage, useRoot, allowScreenshots)
                    },
                    enabled = canPrepare,
                ) { Text("授权并准备") }
            },
            dismissButton = { TextButton(onClick = { showPrepareConfirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ControlOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TargetAppPicker(apps: List<PhoneTargetApp>, onSelect: (PhoneTargetApp) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索应用名称或包名") }, singleLine = true)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (filtered.isEmpty()) item { Text("没有匹配的可启动应用，请关闭后刷新列表。") }
                    items(filtered, key = { it.packageName }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable { onSelect(app) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun PhoneSessionStatus.label(): String = when (this) {
    PhoneSessionStatus.IDLE -> "尚未开始"
    PhoneSessionStatus.RUNNING -> "执行中"
    PhoneSessionStatus.PAUSED -> "已暂停"
    PhoneSessionStatus.WAITING_FOR_FOREGROUND -> "等待前台接管"
    PhoneSessionStatus.STOPPED -> "已停止"
    PhoneSessionStatus.EXPIRED -> "预算已到期"
}
