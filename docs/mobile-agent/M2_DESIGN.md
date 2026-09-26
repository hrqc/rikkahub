# V1 M2 手机控制实现设计

更新日期：2026-09-26。M2 是 **V1 的第二个里程碑，不是 V2**。本文描述当前已落入源码的实现，包括首批受限 Root 输入和目标窗口截图。最终选定 JVM 回归 68 项通过，真机夹具三项通过；这些证据不代表 OPPO 专项兼容性、真实模型任务闭环或 V1 整体验收完成，见 [M2_TEST_PLAN.md](M2_TEST_PLAN.md)。本轮源码为 `3ae621ca70e44fa4c7e956f0ad4ebe1a4d79a340`，构建、签名和交付结果由主线构建报告记录。

## 用户入口与授权

`Screen.MobileControl(conversationId, assistantId)` 打开 `MobileControlPage`，由 `MobileControlVM` 绑定聊天与助手。用户明确选择一个可启动目标应用；本应用、已知系统授权/安装界面和 Root 管理器不在允许目标内。

- **开始并调用模型**：用户填写任务，确认目标、预算、页面内容发送和可能产生的模型费用后开始；控制器成功建立会话后，才把任务发送给指定聊天。
- **仅准备控制**：不调用模型，不自动读取页面或打开应用；用户另行点击“打开目标应用”，才执行启动及本地页面验证。

Root 增强和允许截图均默认关闭。Root 增强要求设备能力页已通过用户发起的 Root 检测；允许截图要求后端支持 API 34+ 的目标窗口截图。无障碍、通知权限由用户在系统界面显式开启，被动刷新不会申请 Root。

无障碍权限或 App 已获 Root 都不自动授权普通聊天。模型没有开始、恢复或扩大目标范围的工具。

## 实际组件

| 组件 | 当前职责 |
| --- | --- |
| `PhoneControlModels.kt` | `PhoneSessionToken`、`PhonePermit`、`PhoneObservation`、`PhoneNode`、`PhoneAction` 和结果 DTO。 |
| `PhoneController` | 单个内存会话、状态、预算、快照及审计；串行观察/动作，复核授权。没有单独的 `AgentSessionManager`。 |
| `PhoneBackend` / `AccessibilityPhoneBackend` | Android 环境、目标过滤、节点读取和动作派发；派发前复核许可、窗口和新鲜节点。 |
| `AccessibilityTreeReader` / `PhoneTreeReadPolicy` | 有界目标树、节点身份、敏感内容、快照和内部节点句柄；没有 `UiTreeCompressor` 类。 |
| `MobileAgentAccessibilityService` | 连接无障碍并转交窗口事件；无会话时不读界面树，仍更新连接/窗口等环境元数据。 |
| `ControlledRootExecutor` | 固定格式、有限参数的 Root 输入；取消、超时与进程清理。 |
| `MobileAgentNotifications` / `MobileAgentStopReceiver` | 独立 STOP 通知，隐藏任务/节点/输入文本；旧通知不能停止新会话。 |
| `ChatToolFactory` / `PhoneTools.kt` | 只给获准聊天提供当次工具，闭包持有不可变 token，执行继续经过控制器。 |
| `ChatService` / `GenerationLoop` | 聊天生成、工具恢复、撤权后的生成/pending 取消；旧工具名安全失败。 |
| `MobileControlPage` / `MobileControlVM` | 选择目标、任务、权限范围，开始/准备/暂停/恢复/停止，展示状态与预算。 |

状态为 `IDLE`、`RUNNING`、`PAUSED`、`WAITING_FOR_FOREGROUND`、`STOPPED`、`EXPIRED`。授权仅在内存中，进程重启不恢复；暂停/恢复不重置动作、观察和时间预算。

## 工具命名与会话隔离

`ChatToolFactory.createTools()` 接收 `conversationId`，通过 `activeToken(conversationId, assistantId)` 取运行中授权，无匹配授权时不提供 Phone Tools。

名称格式为 `phone_<sessionId 前 12 位十六进制>_<epoch>_<operation>`。sessionId 来自随机 UUID 的 32 位十六进制表示；新会话更换 sessionId，暂停/恢复/停止更新 epoch。闭包保存完整 token，模型参数不能提供 token，执行时也不改绑当前 token。

| operation | 参数与用途 |
| --- | --- |
| `observe` | 无参数；返回目标页面结构及快照 `id`。 |
| `open_app` | 只打开选定目标，无包名参数；唯一允许 `snapshotId = null` 的动作。 |
| `click` / `long_click` | `snapshot_id`、`node_id`；点击/长按新鲜节点。 |
| `input_text` | `snapshot_id`、`node_id`、`text`；只写入可编辑节点。 |
| `scroll` | `snapshot_id`、`node_id`、`forward`；滚动指定节点。 |
| `swipe` | `snapshot_id`、`direction`；方向为 UP/DOWN/LEFT/RIGHT，不接受模型坐标。 |
| `back` | `snapshot_id`；在当前许可和目标前台检查后返回。 |
| `screenshot` | `snapshot_id`；仅本 token 对应会话已允许截图、启动时后端支持时注册。 |

Phone Tools 使用面板建立的受限会话授权，`needsApproval = false`，避免逐步审批强迫切回聊天并使目标快照失效。其他工具审批不变；敏感页和策略限制在执行时强制检查，普通审批不能绕过。

恢复旧审批时重新创建工具集，旧 session/epoch 名称不会匹配新工具，也没有别名迁移。仍持有旧闭包的调用会被控制器与后端拒绝。模型没有 start、resume 或 stop 工具；STOP 由用户界面/通知触发。

## 观察与动作的检查链

1. 控制器确认完整 token、运行状态、时间预算、服务连接和设备解锁。
2. 观察只读获准目标的活动窗口。进入目标后，已知前台变为其他包立即暂停；前台包短暂未知时给予 750 毫秒窗口过渡等待，期间返回 `WINDOW_TRANSITION`，不读取节点、不执行动作，超过期限仍未知则暂停。等待不扩大目标范围，也不自动恢复已失效授权。
3. 除 `open_app` 外必须引用最近快照。快照最长 10 秒，windowId/windowRevision 一致且只能消费一次。
4. 检查节点身份、可用性及动作能力。输入最多 2,000 字符，不接受 NUL。
5. 后端派发前重读有界目标树，检查敏感标记、截断、指纹与节点签名。坐标型底层动作还检查目标区域和上层遮挡。
6. 普通动作派发后短暂等待并重新观察。`accepted` 仅表示平台接受，`screenChanged` 仅表示观察到变化，都不等于任务完成。同一动作连续三次未确认变化会暂停。

会话限额为 **30 次动作、90 次观察、5 分钟**，控制器单次操作超时 20 秒；观察预算不足以验证下一动作时不再派发。无法验证的结果明确报告“动作已提交但无法验证”。

树读取最多访问 256 节点、输出 100 节点、深度 24、每节点遍历 64 子节点、总文本 12,000 字符、时间 1,500 毫秒。公开字段预览最多 240 字符，敏感检查先查较完整字段；超过 4,096 字符按无法确认安全处理。密码/系统敏感字段不调用文本 getter；敏感页不返回正文。截断快照不能执行动作或截图。

支付确认、密码、验证码、生物识别、授权相关词和 Android 敏感标志触发人工接手。该保守策略不是所有敏感页面的完备识别，也不能完备检测同应用内人工触摸冲突。

## 首批 Root 与窗口截图

`ControlledRootExecutor` 仅接受 `Tap`、`LongPress`、`Swipe`、`Back`。固定 UID 0 检查后执行 `/system/bin/input`，插入值仅为 0–65,535 内整数坐标，长按/滑动时长固定。坐标来自已验证节点或目标区域，无任意 shell、包名或坐标字符串入口。

Root 模式接入点击、长按、滑动/滚动和返回。**中文文本仍走无障碍 `ACTION_SET_TEXT`**；启动走允许目标的 Intent，截图走窗口截图 API。Root 失败不会暗中换后端。动作超时 4 秒；STOP 先失效 epoch，再异步清理进程，避免阻塞在管道锁。输出被消费并丢弃，超限不算成功，不回传 shell 原文。已生效点击不能撤销。PRoot 工作区仍不是设备 Root。

截图只在 API 34+ 使用 `takeScreenshotOfWindow(windowId, ...)`，前后均校验 token、窗口版本与页面安全状态。低版本、受保护、超时或页面变化时拒绝，不回退全屏截图。

截图长边不超过 1,600 像素，JPEG 质量 80，上限 2 MiB，保存在私有 `mobile_agent_screenshots` 缓存并通过 FileProvider 提供。`PhoneTools` 输出 `UIMessagePart.Image`，文字 JSON 不包含本地 URI；后续实际模型调用按多模态链路处理该图片，受用户本次截图和模型内容发送授权约束。独立仪器测试只本地解码测试页面图片，不上传或调用模型。

## 停止与隐私

`ChatService.stopGeneration()` 先 `stopForConversation()` 撤权，再取消生成并结束 pending；没有活动 job 也会清理当前 pending。状态订阅对 PAUSED/WAITING_FOR_FOREGROUND/STOPPED/EXPIRED 走仅取消路径，不把暂停改成停止。用户恢复后必须重新打开/观察目标。

控制通知频道为 `mobile_agent_control`、ID 为 2403，与聊天生成通知独立。STOP 绑定 sessionId，接收器不导出，删除通知也停止。无法显示可用 STOP 通知则不建立控制会话。

聊天事件使用生成级 `redactContent`，STOP 后标记不会消失；含 Phone Tool 历史的后续生成也隐藏通知正文。本次清理的日志路径不记录工具参数、请求/流正文或异常原文。HTTP 日志仅保留方法、来源主机、状态、耗时及异常类别，不读取 body、不保存 headers/query。手机工具输出不另存工作区长输出文件，审计仅保存动作类别和固定结果。

日志脱敏不等于聊天不保存或不发送模型：获准页面结构、工具结果及允许的图片仍进入既有聊天数据和模型上下文。设备隐私、停止及完整任务表现仍需按测试计划验证。

## 设备验证边界

独立测试 APK 的两个夹具 Activity 使用纯 Java 与原生 Android 类，避免其独立进程依赖主 APK 的 Kotlin 运行库。同一次 instrumentation 按序执行三项：无障碍动作与直接 STOP、显式 `root=true` 的 Root 动作与通知 STOP PendingIntent/receiver、API 34+ 本地窗口截图。真实通知链测试不等同于人工点击通知 UI，也不验证模型任务完成。

部分 ROM 会在 instrumentation 重启主应用后将已开启的无障碍服务标为崩溃；测试只能等待用户手动关开服务，不能自动授权。`waitForServiceMillis` 默认 8 秒、最高 120 秒。`waitForFixtureMillis` 默认 8 秒、最高 60 秒，本轮指定 `60000`，给人工处理本次 OEM 启动确认留出时间；没有关闭系统确认或自动授予权限。

历史失败已修复并经最终整轮复验：纯 Java 夹具解决独立测试进程缺少 Kotlin 运行库；本次一次性允许 ColorOS 的 `AppStartConfirmDialogActivity` 后目标可以进入前台；750 毫秒未知窗口等待解决第二页切换时过早暂停。测试只对窗口过渡等列明错误做有限重试，`SESSION_INVALID` 仍立即失败。

主线 `22-final-smoke.txt` 记录 **`OK (3 tests)`，55.291 秒**，覆盖无障碍及 Root 两条路径的点击、长按、中文输入、滚动、第二页与 Back，直接 STOP、真实通知 PendingIntent/receiver STOP、旧 token 拒绝和本地窗口截图。最终 JVM XML 快照为 2026-09-26 09:39:05–09:39:14 UTC，共 68 项，失败、错误、跳过均为 0。真实模型任务、OPPO 专项兼容性、真实遮挡、人工点击/删除通知及后台模型取消等仍待验证，不能据此宣布 V1 完成。
