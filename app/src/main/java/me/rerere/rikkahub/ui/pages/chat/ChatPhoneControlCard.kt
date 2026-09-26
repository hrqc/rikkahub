package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.PhoneChatControlState
import me.rerere.rikkahub.service.PhoneChatPhase

@Composable
fun ChatPhoneControlCard(
    state: PhoneChatControlState,
    onAccept: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onAdvanced: () -> Unit,
) {
    if (state.phase == PhoneChatPhase.HIDDEN) return
    var selectedPackage by remember(state.proposalId) { mutableStateOf(state.candidates.singleOrNull()?.packageName.orEmpty()) }
    var choosing by remember(state.proposalId) { mutableStateOf(false) }
    val selected = state.candidates.firstOrNull { it.packageName == selectedPackage }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (state.phase) {
                    PhoneChatPhase.CONFIRM -> "确认手机操作"
                    PhoneChatPhase.PREPARING -> "正在准备手机操作"
                    PhoneChatPhase.RUNNING -> "手机操作进行中"
                    PhoneChatPhase.PAUSED -> "手机操作已暂停"
                    PhoneChatPhase.ENDED -> "手机操作已结束"
                    else -> "手机操作暂不可用"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.targetLabel.isNotBlank()) Text("目标：${state.targetLabel}", style = MaterialTheme.typography.bodySmall)
            Text(state.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (state.phase == PhoneChatPhase.CONFIRM) {
                Text(state.originalText, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { choosing = true }, enabled = state.candidates.isNotEmpty()) {
                    Text(selected?.label ?: "选择目标应用")
                }
                Text("开始后只操作所选应用，并将可见页面文字交给当前模型。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selected != null && state.proposalId != null,
                        onClick = { state.proposalId?.let { id -> selected?.let { onAccept(id, it.packageName) } } },
                    ) { Text("确认并执行") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phase in setOf(PhoneChatPhase.PREPARING, PhoneChatPhase.RUNNING)) {
                        OutlinedButton(onClick = onPause) { Text("暂停") }
                    }
                    if (state.phase == PhoneChatPhase.PAUSED) {
                        Button(onClick = onResume) { Text("继续执行") }
                    }
                    if (state.phase in setOf(PhoneChatPhase.PREPARING, PhoneChatPhase.RUNNING, PhoneChatPhase.PAUSED)) {
                        Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("STOP 停止")
                        }
                    }
                    TextButton(onClick = onAdvanced) { Text("高级设置") }
                }
            }
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("选择目标应用") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.candidates, key = { it.packageName }) { app ->
                        Column(Modifier.fillMaxWidth().clickable {
                            selectedPackage = app.packageName
                            choosing = false
                        }.padding(vertical = 12.dp)) {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } },
        )
    }
}
