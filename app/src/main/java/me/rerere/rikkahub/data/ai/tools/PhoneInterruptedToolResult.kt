package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessagePart

/** A cancellation can race a dispatched phone action's result publication. Never infer non-execution. */
internal fun interruptedToolResult(tool: UIMessagePart.Tool): UIMessagePart.Tool {
    if (tool.isExecuted) return tool
    val result = if (isPhoneToolName(tool.toolName)) {
        """{"status":"interrupted","execution_outcome":"unknown","detail":"手机工具调用已中断，执行结果未知；此前动作可能已经发生。恢复后必须使用当前授权重新观察并核对结果，不得直接重放；无法核实时请用户接手。"}"""
    } else {
        """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
    }
    return tool.copy(output = listOf(UIMessagePart.Text(result)))
}
