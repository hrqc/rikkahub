# V1 动态页面可靠性专项：审查后的执行方案

日期：2026-09-27。审查来源：用户提供的 `CODEX_V1_DYNAMIC_UI_RELIABILITY_SPRINT.md`。用户已要求先审查、完善，然后开发；本文为完善后的实施依据，下载目录中的原文件保留不改。

## 1. 范围与基线

仅处理 V1 动态页面诊断、读取可靠性、任务暂停恢复和最小购物验收。保留已实现的聊天入口、语义理解、Root 复核、悬浮窗、公开研究和购物计算。不新增 V2、Shizuku、独立显示、媒体流水线、产品多 Agent 或长期队列。

- 开发分支：`feature/mobile-agent-v1`。
- 开发前 HEAD：`be6d89f97a630c9be3506d010cfb637b0ff7e0a9`。
- 开发前已安装主 APP：198，源码 `e0530732414e978381a5a1043c736790c7154f3d`；本专项 D1 的 199 版结果见 [M2_8_DYNAMIC_UI_DIAGNOSTICS.md](M2_8_DYNAMIC_UI_DIAGNOSTICS.md)。
- 198 内部标签：`mobile-agent-v1-m2.7-reliability-internal`。
- 197 回滚依据：`3d84accd` / `mobile-agent-v1-m2.6-research-shopping`。
- 用户已明确授权本专项经审查的源码、测试及文档推送到公开 Fork `hrqc/rikkahub`；APK、原始手机记录、聊天数据、密钥均不上传。
- `origin/feature/mobile-agent-v1`、备份分支 `backup/v1-m2.7-pre-dynamic-ui-fix` 和 198 内部标签已于本轮成功推送。备份分支指向开发前 HEAD。
- 用户已回复京东平台验证完成，可继续普通页面实测；如再次出现验证，重新暂停，由用户完成。

## 2. 审查结论与必要修订

原方案“先找证据、再改读取、最后验收”的方向保留，修正以下内容：

| 原方案需澄清处 | 审查后的决定 |
| --- | --- |
| 返回 `PAGE_UNSTABLE` 等同于 Agent 已暂停 | 当前只是确定本次读取失败，控制器未必进入 PAUSED。分别记录失败码、是否派发动作、真实会话状态；补齐持续读取失败后的明确暂停 |
| 新建一套 ObservationSnapshot | 现有 `PhoneObservation`、后端 token 绑定和动作前二次读取已经存在。在原链路上增加诊断关联，不建立平行授权系统 |
| 顶部商品完整即可 PARTIAL_BUT_ACTIONABLE | 空子节点没有可验证的边界，不能证明它不覆盖目标或不含敏感弹窗。本批不开放局部动作，仅收集诊断证据 |
| 异常节点记录 text / contentDescription，尽量脱敏 | 默认不导出这些字段、输入值、事件正文、通知正文或截图；仅记录有界结构元数据 |
| 按 0、100、250ms 等精确采样 | 时间点是计划值，实际读取可能更慢。串行采样，记录真实起止、迟到和跳过；不并发堆积，也不伪造时间 |
| 测试驱动读取结果代表 APP 读取能力 | USB 驱动和生产服务的根节点选择、遍历、预算与缓存不同。主要证据必须来自生产 TreeReader / Backend；驱动仅作辅助对照 |
| LastSuccessfulAction 表示点击返回成功 | 区分未派发、已派发但业务结果未确认、业务结果已验证；accepted 不等于领券成功 |
| 60 秒超时已实现即算验收 | 保留 JVM 证据，另用测试配置注入真机故障，不修改用户 API 或关闭整机网络来制造超时 |

## 3. 开发批次与依赖

### D1：同源只读诊断与明确暂停

1. 为现有 TreeReader 增加可选的结构统计，记录缺口父路径、计数、实际预算和完成度；不影响动作放行。
2. 在 Backend 原有 observe 尝试中记录 Root refresh、缓存清理、窗口 identity/revision 和真实结束原因。
3. 事件只保留固定元数据，采用有界内存缓冲；诊断默认关闭，显式测试启用，限时、限量并在结束时清理。
4. 提供 debug instrumentation 入口，针对明确选定的前台目标，只观察并导出本地诊断。不能点击、滚动、打开目标、领取或操作验证页。
5. 补齐最终 `PAGE_UNSTABLE` / 无法完整检查时的控制器暂停；不在单次可恢复重读时提前暂停，也不自动重放动作。
6. 本地测试覆盖诊断内容、上限、关闭、取消、窗口变化和旧 token；主 APK / 测试 APK 构建通过后开展真机采样。

### D2：依据真实证据修正读取策略

形成 `DYNAMIC_UI_ROOT_CAUSE_REPORT.md`，逐项回答缺口位置、持续时间、refresh、窗口变化等问题。未知项明确写未知。

优先修复已经被证实的缓存、时序、事件误判或读取错误。事件、结构摘要可帮助选择有限等待，但不是忽略窗口切换的理由。保持有界次数、时间、内存和 STOP。

状态分类可增加 `STABLE`、`TRANSITIONING`、`INCOMPLETE`、`UNREADABLE`；诊断分类不能直接产生执行许可。`PARTIAL_BUT_ACTIONABLE` 只有在具体缺口与操作区域隔离可证明、祖先路径可复核、覆盖窗口和敏感检查完整、Root 后备遵循同样校验后，才另行实现与验证。

### D3：平台验证与恢复

识别有明确验证界面证据的安全验证页，返回 `PLATFORM_VERIFICATION_REQUIRED`，暂停并交给用户。不要因商品说明单独出现“验证”二字就判定验证页。

用户完成并明确继续后，重新建立有效任务代次、重新观察。不恢复旧快照或旧节点句柄，不把待办动作直接重放。应用进程重启不自动恢复控机授权。

动作恢复至少区分：`NOT_DISPATCHED`、`DISPATCHED_UNVERIFIED`、`VERIFIED`。业务验证需有证据；不能用页面变化或 API true 充当领取、发送、提交已成功。

### D4：业务与设备验收

先京东搜索 → 首屏 → 滚动 → 再读取 → 至少三个同规格、同数量候选 → 价格及费用证据 → `shopping_compare`。未知运费标记未知，不输出确定实付；不下单、不支付。

该流程稳定后，再验证一个普通免费券的发现、规则读取、领取、成功确认、应用及金额复核。无券可测要如实记录，合成页面只能补逻辑回归。

之后补齐暂停、继续、STOP、模型无输出/增量输出、网络失败、断连、前后台、锁屏/解锁和平台验证后继续。OPPO Find X6 Pro 非 Root 单独验收，再依次扩展淘宝、拼多多、美团。

## 4. 诊断数据契约

### 4.1 最小记录

- 关联：随机 diagnosticRunId、sampleId、attempt、任务/授权代次、snapshotId；失败时 snapshotId 为空。
- 时间：wall-clock 时间戳、单调开始/结束时间、计划偏移、迟到或跳过原因；事件源时钟与接收时钟分开，不直接相减。
- 环境：APP 版本、Android API、采样来源、实际服务 flags/eventTypes、遍历预算。
- 窗口：开始/结束 windowId、identity、revision、窗口数量及筛选结果；只输出与获准目标有关的必要元数据。
- 树：实际访问、可见、输出节点、缺失子节点、保护子树、刷新成功/失败/未尝试、缓存清理结果、检查缺口和预算结束原因。
- 缺口：父路径、类型、资源 ID、边界、可见性、childCount、缺失 index；每次最多 20 条，额外数量另计。
- 事件：有限的 eventType、windowId、contentChangeTypes、windowChanges、事件时间与接收时间，标记丢弃数；不获取 event.source 或正文。

`activityName` 没有可靠来源则为 null；事件/节点 className 不能冒充 Activity。加载或动画状态无法判断时为 unknown。缺失 child 没有自身 bounds，不能把父节点 bounds 当作它的边界。

### 4.2 哈希与隐私

结构摘要使用版本化规范，包括路径、结构字段、缺口标记及完成度；不使用节点对象 hashCode，不导出正文 hash，也不把 hash 相同当作页面完整。

首批 `screenHash=null`，原因是未启用截图；不为填字段增加截图。密码和 Android 标记的敏感子树不读取正文或向下遍历。诊断输出不进入模型请求、不上传源码仓库。

### 4.3 开关、上限和导出

第一批采用 debug 测试显式启用的短生命周期诊断，不新增常驻监听、公开广播或放宽主 APP 权限。结束、取消、STOP、失效或达到预算即停止记录。

观察样本不超过 7 个，单次缺口不超过 20 条；事件、尝试、总导出大小均有独立上限，并记录截断。按实际实现固化具体数值和测试。

原方案四类 JSONL 可由同一版本化诊断包区分 recordType，避免复制记录和关联丢失。没有实现的恢复/快照事件不能用空文件假装完成。导出写入本地 `artifacts/`，不得提交到 Git。

## 5. 时间序列采样规则

建议偏移仍为 0、100、250、500、1000、2000、3500ms，作为一次已授权滚动或明确时间原点后的计划。

读取串行进行；前一次尚未结束时，不并行启动后一次。过期时隙记录跳过，后续从仍可执行的时隙继续。记录每次真实时间、耗时及局限，不能声称所有时间点都有完整树。

只读诊断本身不触发滚动。若以滚动为时间原点，应由已有授权的任务动作或用户手动操作，并记录动作/事件关联；普通诊断启动时间不能伪称滚动完成时间。失败暂停后不得为了凑足样本自动恢复会话。

Android Binder 调用不一定能由协程即时中断，时间预算属于可检查位置上的限制；超时输出真实耗时与超限，不承诺硬实时。

## 6. 回归与真实验收标准

必须分别报告：纯逻辑测试、合成设备测试、生产服务只读采样、真实模型业务任务、非 Root 验收。

首批诊断完成意味着能够取得或准确报告失败的结构证据，不等于京东问题修复。后续修复至少在真实第三方应用连续重复完成搜索、滚动、重新读取和继续操作，记录成功次数及失败次数。一次成功、首屏成功、延长等待、合成测试通过都不能单独作为已解决依据。

安全回归必须包含：旧快照、窗口切换及往返、STOP 期间读取、敏感节点/祖先路径、节点变化、Root 后备前复核、诊断达到上限和退出后不再采集。

60 秒模型无进展使用 debug 假 provider 或可控测试注入，验证取消、PAUSED、增量重置、工具执行不重放、恢复新观察及晚到旧超时。实测前保留用户现有 API 配置。

## 7. 交付与状态报告

每批按审查 → 合适测试 → 构建 → 审查 diff → 本地提交 → 授权远程推送 → 标记产物来源执行。安装包必须对应明确源码提交；仅文档或测试工具变化不能假装产生了主 APP 新功能。

报告内容：修改、已确认根因/未知项、真实证据、通过/失败/跳过、Root/非 Root 状态、未解决问题、分支、提交、推送、标签、回滚点、工作区、APK 路径/版本/SHA-256、下一步。

不 reset/clean/force push，不清除用户数据，不上传手机原始记录或密钥。专项完成仍不等于完整 V1 验收，V2 继续关闭。

## 8. 依据

- 本地实现：`PhoneControlModels.kt`、`PhoneController.kt`、`AccessibilityPhoneBackend.kt`、`AccessibilityTreeReader.kt`、`PhoneObservationReadPolicy.kt`。
- [198 证据与限制](M2_7_RELIABILITY.md)。
- [Android UiAutomation](https://developer.android.com/reference/android/app/UiAutomation)：保留无障碍服务的标志及事件回调接口。
- [Android AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh())：refresh 的含义；失败不能当成节点仍有效。
- [Android AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)：事件类型、窗口变化与元数据来源。
