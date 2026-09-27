# M2.11：202 扩展生产读取

更新：2026-09-28。范围仍为 V1；202 已构建、验签并覆盖安装保留数据，完成一次受控生产原生滚动。首轮真实模型到达搜索结果后暂停，第二轮出现后续列表位置后遇到京东安全验证，多页购物业务尚未通过。

## 本次改动

- 在无障碍服务 XML 静态加入 `flagIncludeNotImportantViews`，连同报告资源 ID、读取交互窗口组成默认 flags 82（`0x52`）。生产读取检查实际服务配置是否包含这三个必需位，允许额外无关 flags，不把“恰好等于82”作为条件。
- 实际配置缺失、读取失败或服务替换时拒绝当前生产操作，提示重连服务；不会在读树或动作途中临时修补 flags。失效时只清理对应 token 的旧快照，不误清新授权的快照。
- 生产和诊断统一使用 768 节点上限，读取器和诊断元数据使用同一常量，避免真实预算与日志不一致。
- 快照发布前再次核验当前授权与 revision。
- 单次树读取 2 秒、深度 40、预览最多 100 节点／12,000 文本字符、单字段 240 字符预览和原有有界重读均保持不变。敏感内容、缺口检查、STOP、锁屏、快照时效及受限原生滚动边界没有放宽。

201 曾在独立诊断配置下读取 515 个节点、零缺口；这只是本次改动的工程依据之一，不能移作 202 的生产读取结果。包含非重要节点可能暴露更多结构，也仍可能得到缺口或超限；202 本次独立结果如下。

## 已完成验证与待补证据

- 当前定向 JVM XML：38 个套件、378 项，失败／错误／跳过均为零。覆盖统一 768 节点边界、必需 flags 缺失／额外 flags、生产诊断预算记录及既有控制规则。
- `artifacts/v1-m2.11-expanded-read/unit-tests.log`：`BUILD SUCCESSFUL in 1m 4s`。
- 独立静态审查未报告阻塞问题。
- `apk-build.log`：主包和测试包构建成功，`BUILD SUCCESSFUL in 28s`；`signatures.log`：两个 APK 的 v2 签名验证通过。两包已在一加覆盖安装，保留原数据；归档大小和 SHA-256 见项目根 `artifacts/v1-m2.11-expanded-read/BUILD_INFO.md`。
- 配置版本为 `2.5.4-mobile-agent-v1-m2.11-expanded-read`／versionCode 202，包名 `me.rerere.rikkahub.debug`。源码基线为 `068c9442f237dbca2ff2748e4c9d2a361ab92b84`；202 安全点提交为 `29a70b9309f3e30a1dacfb701e424f2f66b34c3b`，标签 `mobile-agent-v1-m2.11-expanded-read-internal`，分支与标签均已推送并核验远端。后补事实文档不改变 APK 源码身份。

## 202 真机证据

- `jd-native-scroll-202-a.json` 和同名 `.log`：京东搜索结果页前台检查均匹配，实际发出并计入 1 次原生滚动；`accepted=true`、`screenChanged=true`、`engineeringScrollPassed=true`。测试器为 `OK (1 test)`，没有跳过。
- 滚动前后均为 `truncated=false`、`scrollOnly=false`、`sensitive=false`，即通过当次完整树检查；两次输出各为 100 个预览节点，`previewTruncated=true`。100 是预览上限，不是实际树总节点数，更不能据此宣称所有商品均已输出。该次使用 `useRoot=false`、`allowScreenshots=false`，不证明 Root 输入通道。
- `fixture-root-stop-202.log` 首轮为 1 项失败：既有只读 UID 检查后，测试页启动等待 8 秒仍由 `com.oplus.securitypermission` 占据前台，停止于 `launchFixture` 前置检查。该日志记录实际服务 flags 为 82，但没有完成 fixture 动作／STOP 回归；不得算作通过或把前置启动失败改写成 Root 动作失败。
- `fixture-root-stop-202-preopened.log`：由主机 ADB 预先显式打开测试页后复跑，`OK (1 test)`、无跳过。已验证既有 Root UID 授权、点击、长按、中文输入、滚动、返回、通知 STOP 的 PendingIntent／接收器及旧 token 失效；没有修改系统启动授权。此结果不抹去首轮失败，也不证明每个输入都经由 Root 通道或完整 Root 生命周期均已验收。
- 原生滚动记录明确为 `businessTaskAccepted=false`；本轮模型自主购物任务尚未通过。

## 首轮真实模型与任务后诊断

- 确认任务为京东搜索双 Type-C、60W、1 米数据线，向下翻两次，比较至少三个同规格候选并查看中差评／追评；仅浏览。`model-chat-proposal.txt` 保留该任务。模型完成搜索，`model-jd-progress-2.png` 确认已到结果页；`model-paused-detail.png` 显示约第 13 秒暂停，控制器计数为动作 5／30、观察 7／90。这是计数记录，不代表五个动作结果均已逐一核证。
- `model-paused-chat.txt` 显示宿主因有限次读取后页面仍不稳定而暂停（`PAGE_UNSTABLE`）；`model-tool-error-detail.txt` 中最后一次 click 返回 `status=interrupted`、`execution_outcome=unknown`。不能将该点击记为确定成功或确定未发生，恢复时必须重新观察，不直接重放。主线现场记录：排查并等待原因期间，原会话随后达到五分钟时间预算而结束。
- 随后另开受控生产读取，证据为 `jd-after-model-stable/dynamic-ui-diagnostic.jsonl`：实际 flags 82、节点预算 768；四次读取均为 617 个已访问节点、1 个 `unavailable_child` 缺口，分别耗时 651／787／907／930 毫秒，整轮 3,660 毫秒。四次目标 window／identity／revision 与结构 hash 一致，root refresh 与 cache clear 均成功；最终只返回 `scrollOnly`，动作 0、`businessTaskAccepted=false`。617 是已访问数量，缺口仍使总节点数未知；缺口父容器一像素高也不能证明缺失子节点无害。
- 此诊断发生在模型任务之后，证明该次生产页仍可能不完整；不能据此反推原模型暂停尝试的具体根因。首轮未取得完成两次翻页、三商品比较或评价抽样的证据。

## 第二轮模型：安全验证阻塞

- 第二轮确认使用当前结果、不重新搜索。`model-b-progress.png`（浮窗 0:47）和 `model-b-progress-2.png`（1:39）显示相对初始结果页已经变化的后续商品区域，均为“等待 AI”。两张图自身处于同一列表位置，不能证明恰好完成两次滚动，也不能证明已收集三个可比候选。
- `model-b-progress-3.png` 显示“京东验证／快速验证”页面；主线现场记录约在 2:56 出现。主线没有操作验证，已请用户手动完成。记录时手机仍安装 202 并停留在该页，后续手机业务验证等待人工完成此步骤。
- 尚未取得完整模型比价／评价工具原始结果，不将页面变化或等待状态计为比价、评价抽样或购物业务通过，也不将此页面反推为首轮 `PAGE_UNSTABLE` 的原因。

## 业务边界

当前仍未核证宿主商品卡片／SKU 归属、模型自主多页收集三个可比候选、评价抽样与最优券领取应用。真实引用不自动证明跨节点属于同一商品；重复文案不能确认刷单或推出质量排名。美团、淘宝、拼多多及非 Root 设备的业务验收仍未完成。

后续 203 的本地测试、构建及覆盖安装已完成，见 [M2_12_READ_BUDGET.md](M2_12_READ_BUDGET.md)；这些不改变本页 202 的历史证据，也不表示新逻辑已真机验收。仍先等待人工完成验证，再继续核对模型自主多页执行、商品证据绑定和完整 Root 生命周期。一次工程滚动、完整观察和独立 fixture 回归不等于三个商品比价、评价分析或最优券业务通过。
