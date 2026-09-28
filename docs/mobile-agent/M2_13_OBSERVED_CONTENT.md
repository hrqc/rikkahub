# M2.13：204 已观察正文与证据约束

更新：2026-09-28。范围仍为 V1。204 已完成最终定向 JVM 回归、APK 构建与 v2 验签，并安装到一加；重连后已验证超过预览范围的保留正文分页。本页保留此前跳过和未采样记录，不代表购物业务验收。

## 203 本轮复测

本地证据目录：项目根 `artifacts/v1-m2.12-read-budget/followup-20260928/`。本页只记录状态和验证边界，不附聊天、商品、评价、账号或截图正文。

- 复测开始时，无障碍服务处于 enabled 但 crashed 的状态；仅重连用户既有授权的 Mobile Agent 服务后恢复。不能把“设置中已启用”当作服务当前已连接，也不能据此认定之前每次中断均由此导致。
- 初始聊天请求没有进入实际执行；普通“继续”只得到等待确认的回复，界面显示该回复耗时 120.7 秒。这段等待不是实际操控用时或任务通过证据。
- 明确重新提议并确认后，主线现场记录于 08:44:33 开始执行。手机实际从京东详情页到达买家评价页面；`run203-start.txt`、`run203-progress.png` 等保存当时本地记录。页面到达不证明完成三个同规格候选、有效评价抽样或最终推荐。
- `run203-chat-latest.txt`、`run203-final.txt` 显示任务已结束；`run203-last-tool.txt`／`run203-last-tool-result.txt` 对应最新 assistant 的最后一次 `phone_click`，只有调用参数，没有调用结果。该调用结果未知，不能记为确定成功或确定没有发生；这暴露了当时结束／取消时缺少 unknown 收尾的问题。204 的实现见下，不能用新实现反推该次历史点击结果。
- 更早提议消息中的 `Channel was closed` 与最后一次实际调用不能直接归为同一故障；现有记录不足以证明它是实际执行中断的根因。

## 204 已实现内容

- **读取保留正文。** 新增会话绑定的 `phone_*_read_observed_content`，分页返回宿主在完整、非敏感观察中保存的可见文字。它不重新读取手机、不刷新动作快照、不增加动作许可；初次 cursor 为 `0`，后续使用 `nextCursor`，没有下一游标即停止。
- **节点身份与预算。** `n*` 保留预览引用，额外 `r*` 只供阅读与引用，不具备动作句柄；分页顺序不表示页面空间顺序。每快照最多 768 节点／64,000 个 `String.length` 字符单位，每字段最多 240；每页最多 40 节点／8,000 字符单位，历史最多 32 快照／256,000 字符单位。这些是文本与节点上限，不是 UTF-8 字节数或进程内存大小。
- **完整性与清理。** 不完整／受限滚动观察不发布新正文或新购物证据。正文宿主传输字段不序列化进普通观察结果，后端动作快照不保留该正文；动作快照比较仅剥离只读正文元数据。暂停、STOP、旧授权失效和历史淘汰仍阻止读取对应正文。
- **单候选不得跨快照拼接。** 身份、规格、单价必须来自同一快照，否则返回 `candidate_evidence_mismatch`；不同候选仍可分别来自不同快照做跨页预筛。同快照只是最低约束，不能证明标题、价格、店铺属于同一商品或 SKU；`product_binding_verified=false` 保持不变。
- **截断引用不进入正式分析。** 节点级 `truncated` 由宿主生成并保留；购物比较和评价分析在核对真实引用后，对截断节点返回 `incomplete_evidence`。正文仍可展示，但缩短 quote、更换候选标签或模型自报完整不能绕过。页级 `contentTruncated` 包括字段裁切或保留预算不足，不能解读成已读完整页；未引用的截断节点不妨碍使用其他完整节点。
- **补齐未知结果收尾。** 生成结束或取消时，在不可取消的保存流程中，仅对本次聊天、助手与授权前缀下尚无结果的手机调用写入 `interrupted`／`execution_outcome=unknown`，保留已有结果。即使生成表面正常结束，只要仍有未决手机调用也不计生成成功，并按当前授权暂停；恢复后须重新观察，不能直接重放。
- **测试驱动等待根节点。** 测试驱动在新 instrumentation 连接暂时没有无障碍根节点时，最多等待 2 秒／20 次；记录等待耗时与错误类别。已知前台包名不匹配立即拒绝，不等待到另一个应用碰巧出现。这是测试读取可靠性改进，不是申请 Root 超级用户权限，也不放宽 APP 授权。

240 字之后的价格／优惠条件可能改变结论，因此可展示片段不等于可用于确认价格或完整评价语义。即使引用未截断，商品归属、独立评论数和评价真实性仍未核证。

## 最终本地验证与版本

- 版本：`2.5.4-mobile-agent-v1-m2.13-observed-content`／204，包名 `me.rerere.rikkahub.debug`；源码提交 `40b48318c9a150063666a36aab861bdb0af319a8`，已推送，标签 `mobile-agent-v1-m2.13-observed-content-internal`。后补文档不改变该 APK 的源码身份。
- 项目根 `artifacts/v1-m2.13-observed-content/unit-tests.log`：`BUILD SUCCESSFUL in 55s`；最终 `unit-summary.json` 为 **431 项／40 套件，失败、错误、跳过均为 0**。包含正文、证据约束、截断引用及 unknown 生命周期收尾的本轮定向回归；不是完整 V1 实机验收。
- `apk-build.log`：主包和测试包构建成功，`BUILD SUCCESSFUL in 48s`；`signatures.log`：两包 v2 验签通过，主线已确认安装成功。归档文件为 `RikkaHub-Mobile-Agent-v1-m2.13-204-arm64-debug.apk` 和 `Mobile-Agent-v1-m2.13-204-device-tests.apk`，留在本地 artifact 目录。
- 早期 412 项属于截断与生命周期修复之前：`unit-tests-initial.log` 保留工具数量旧断言导致的一项失败；`unit-tests-paging-stage.log`／`unit-summary-paging-stage.json` 记录修正断言后的阶段结果。它们不替代最终 431 项，也不相加。

## 204 真机证据与限制

- `jd-content-smoke.json`／`.log`：实际返回 `INCOMPLETE_SCREEN`，没有取得完整安全正文；动作 0、正文页数 0、`engineeringReadPassed=false`、`businessTaskAccepted=false`。测试使用 assumption skip（状态码 `-4`）；日志末尾 `OK (1 test)` 不能算作通过，也不能声称已核证超过预览的正文分页。
- `jd-review-read-diagnostic/instrumentation.log`：目标窗口元数据尚未就绪，诊断在进入采样前停止。没有取得该次页面树，不能将其解释成页面正文读取故障、固定缺口或与先前 `INCOMPLETE_SCREEN` 相同的根因。
- 重连后的 `jd-review-read-diagnostic-reconnect/dynamic-ui-diagnostic.jsonl`：四次生产观察均 `COMPLETE`，每次访问 497 节点、输出 100 预览节点、缺口 0、实际 flags 82；动作 0、`businessTaskAccepted=false`。这是四次完整观察，不是四项购物业务验收。
- `jd-content-smoke-reconnect.json`／`.log`：`COMPLETE_CONTENT_VALIDATED`、`engineeringReadPassed=true`，测试真实通过且无跳过。100 个预览节点中 61 个含正文；保留正文为 67 节点／747 字符，其中额外 `r*` 节点 6 个，分两页返回 40＋27 节点。67 个引用均校验通过，截断节点 0，正文未截断；分页前后控制器观察次数均为 1，动作 0、Root 关闭、截图关闭，证明分页没有重新观察或操作手机。
- 同一记录 `beyondPreviewValidated=true`，但 `over100ContentNodesValidated=false`：验证了预览之外的正文，不是超过 100 个正文节点的真机覆盖。记录总耗时 87.75 秒包含服务重连等待，不能当作读取或分页性能。`businessTaskAccepted=false` 保持。
- 多线程测试已另行复现 watchdog 关闭通道竞态，主线正准备 205 修复；204 的 unknown 收尾不等于该竞态已经修复，也不能直接证明它是历史模型中断的全部原因。新正文能力在真实模型业务中的使用及 unknown 收尾的真机验收仍待完成，203 那次只留参数的点击仍为未知结果。
- 三候选同规格比较、商品卡片／SKU 绑定、中差评和追评采样、优惠券最优选择及非 Root 设备业务均未验收。不得将到达评价页、工程读取或单元测试成功改写为完整购物任务通过。

203 的安装、391 项回归和此前边界见 [M2_12_READ_BUDGET.md](M2_12_READ_BUDGET.md)；本轮新证据不抹去已有失败或未知结果。原始日志、截图和 APK 仅留项目本地。
