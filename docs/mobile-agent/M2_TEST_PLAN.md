# V1 M2 测试计划与当前证据

更新日期：2026-09-26。范围是 V1 M2 手机控制及首批受限 Root/窗口截图实现，不是 V2。

**当前结论：选定 JVM 回归 68 项通过，真机夹具最终三项全部通过；真实模型和完整场景验收尚未完成。** 不能据此声称 OPPO 整体验收通过、真实任务已有可靠闭环，或 V1 已完成。本轮源码为 `3ae621ca70e44fa4c7e956f0ad4ebe1a4d79a340`，APK 构建、签名和安装由主线构建报告单独记录。

## 已读取的 JVM 报告

数字来自本地 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 最终报告快照，时间为 2026-09-26 09:39:05–09:39:14 UTC（北京时间 17:39:05–17:39:14）。本次文档编辑只读取报告，没有重跑测试。这是选定回归集，不是全仓全部测试。

| 测试类 | 用例数 | 验证范围 | 报告结果 |
| --- | ---: | --- | --- |
| `RootCapabilityProbeTest` | 6 | 精确 UID 0、拒绝/缺失/超时分类、截断、原文隐藏、取消传播 | 通过 |
| `ProcessRootCommandRunnerTest` | 6 | 输出限额、取消/超时、真实子进程与阻塞管道清理 | 通过 |
| `DeviceCapabilityRepositoryTest` | 5 | 被动刷新不探测 Root、显式请求、去重、取消与重试 | 通过 |
| `ControlledRootExecutorTest` | 9 | 固定命令、坐标范围、许可、STOP 注册竞态、输出限额、进程取消与清理锁 | 通过 |
| `PhoneControllerTest` | 24 | 聊天/助手隔离、前置条件、STOP/暂停/替换、旧通知、快照、窗口/锁屏/断连、750 毫秒未知窗口等待与已知其他包立即暂停、敏感页、预算与动作验证 | 通过 |
| `PhoneTreeReadPolicyTest` | 6 | 密码 getter 不读取、预览后敏感词、超长字段、文本/节点/深度/时间限额 | 通过 |
| `PhoneToolsTest` | 6 | namespace/旧闭包、新 session、无 start/resume、会话审批、截图 Image、坏参数不回显、取消传播 | 通过 |
| `RequestLoggingPrivacyTest` | 1 | 不读 body，不保存输入/认证头/URL 参数/异常正文，保留必要元数据 | 通过 |
| `UpdateCheckerTest` | 5 | 上游更新源默认关闭、禁用不请求、错误与取消分类 | 通过 |
| **合计** | **68** | **手机 Agent/日志隐私相关 63 项，更新禁用回归 5 项** | **0 failure / 0 error / 0 skipped** |

源码位于 `app/src/test/java/me/rerere/rikkahub/data/mobileagent/`、`data/ai/` 和 `utils/UpdateCheckerTest.kt`。报告对应其执行时刻，后续源码修改需要重新验证。替身后端和桌面子进程不能证明厂商 ROM、真实无障碍节点、SukiSU 或目标窗口截图可用。

## 三项设备仪器测试

文件：`app/src/androidTest/java/me/rerere/rikkahub/data/mobileagent/PhoneControlDeviceSmokeTest.kt`。

夹具位于独立测试 APK，包名取 `InstrumentationRegistry.getInstrumentation().context.packageName`，与被测主应用不同。`androidTest/AndroidManifest.xml` 声明 launcher `PhoneFixtureActivity` 和同包 `PhoneFixtureSecondActivity`，移除测试 APK 网络权限。页面只有原生按钮、次数标签、输入框、滚动列表和第二页，使用合成数据。独立进程首轮暴露 Kotlin 运行库缺失导致的启动失败，夹具已改为只依赖原生 Android 的 Java Activity，并在最终整轮中验证可启动。

主线最终设备记录 `22-final-smoke.txt` 为 **`OK (3 tests)`，55.291 秒**。下表为该整轮通过范围，不将此前部分通过或跳过计入最终通过数。

| 仪器测试 | 实际断言 | 当前状态 |
| --- | --- | --- |
| `fixtureActionsAndStop` | 始终使用无障碍；点击/长按次数、中文文本、滚动位置、第二页/Back；直接 controller STOP 后旧 token 不能观察 | 最终整轮通过 |
| `fixtureRootActionsAndStop` | 仅 `root=true`；共用动作断言；发送本应用通知 ID 2403 的 STOP PendingIntent，3 秒内停止后验证旧 token 失效 | 最终整轮通过 |
| `fixtureWindowScreenshotRemainsLocal` | API 34+ 独立允许截图会话；仅取测试窗口；FileProvider URI 可读、图片宽高有效；本地解码不上传 | 最终整轮通过 |

此前启动超时的诊断实际显示前台为 `com.oplus.securitypermission` 的 `AppStartConfirmDialogActivity`：ColorOS 启动确认阻止 fixture 进入前台，并非目标窗口事件缺失。本次一次性允许该 OEM 启动确认后，`17-oem-smoke` 的无障碍和 Root 两条动作路径通过点击、长按、中文输入、滚动，截图项也通过；两条动作路径当时在第二页切换时因临时空窗口触发控制器暂停并产生 `SESSION_INVALID`。这些历史失败已修复，`22-final-smoke` 随后通过完整第二页、Back 和 STOP 断言。

窗口过渡现已采用 750 毫秒等待：进入目标后前台包未知时不读取节点、不执行动作，返回 `WINDOW_TRANSITION`；超过期限仍未知则暂停，已知前台变为其他包则立即暂停。仪器测试在既有 6 次观察循环内遇到未知前台仅等待 250 毫秒后继续，保留所有目标包/权限断言；`SESSION_INVALID` 仍立即失败，不能泛化重试或自动恢复授权。错误诊断只补充会话状态和固定原因，不输出节点或输入正文。

使用方法名排序，让同一次 instrumentation 进程依次运行无障碍动作、Root 动作、截图。第一项永远使用无障碍；显式参数 `root=true` 只启用第二项，否则第二项跳过。Root 状态因进程重启变为未知时，调用现有 `requestRoot()` 的固定只读 UID 探测，最多等待 20 秒。不修改 SukiSU 授权策略；失败或超时记为跳过及原因。截图独立设置 `allowScreenshots=true`，不依赖 Root。

连接等待默认 8,000 毫秒，可用 `waitForServiceMillis` 正数参数延长，最高 120,000 毫秒（例如 90,000）。期间仅等待用户手动开启服务，不请求权限。部分 ROM 会在 instrumentation 重启应用进程后把已开启服务标为崩溃，需要用户在此等待窗口内手动关开一次；三项同进程执行可减少重复操作。

fixture 前台等待另由 `waitForFixtureMillis` 控制，默认 8,000 毫秒、最高 60,000 毫秒。本轮使用 `waitForFixtureMillis=60000`，留出人工处理本次 OEM 启动确认的时间；只处理本次目标启动确认，不关闭系统确认、不自动授权或更改前台检测。

共同前置条件：用户手动开启无障碍、允许主应用通知、设备解锁，且没有现存运行/暂停的用户控制会话。不满足时明确跳过，不自动授权、不覆盖用户任务。通知频道单独禁用等启动失败也必须保留原因。

测试通过普通 `Context.startActivity` 启动显式测试组件，不使用跨包 `startActivitySync`、shell 权限授予或其他应用数据。观察重试最多 6 次，动作仅对列明的快照/观察暂不可用及窗口过渡错误最多重试 3 次；每次重新观察，平台接受后再校验可见结果。结束时只停止本测试 session，不清主应用数据、不关闭整个控制器。

Root 项通过本应用通知的真实 PendingIntent/receiver 链验证 STOP，不读取或发送其他应用通知。它不等于人工触摸通知按钮的 UI 验证。这些测试仍不验证模型选工具、聊天后台停止、完整 OPPO 系统行为或真实任务完成判断。

## 待测矩阵

“JVM 已覆盖”只表示相关策略有单元测试。最终三项设备通过仅覆盖独立夹具的列明断言，不代替下面场景的完整验收；skip 不等于 pass。

| 场景 | 现有验证 | 待做验证与通过标准 |
| --- | --- | --- |
| OPPO 无障碍基线 | 前置条件、断连策略 JVM | 用户手动开启后稳定连接；能力页与面板一致；重启后旧授权不恢复。 |
| 普通聊天隔离 | token 聊天/助手匹配、旧 namespace | 两个聊天仅获准者有 Phone Tools；换助手、STOP、新会话后旧调用不能执行。 |
| 准备模式 | 独立 UI/VM 分支已实现 | 无模型配置也可准备；准备本身不启动/读页；另点“打开目标应用”才本地执行。 |
| 无障碍动作 | 控制器 JVM；最终夹具点击/长按/中文输入/滚动/第二页/Back 全通过 | 其他 ROM、真实应用和 UI 入口仍需单独验证，保留设备及主/test APK 标识。 |
| Root 输入 | 固定命令、进程生命周期 JVM；最终 `root=true` 夹具动作全通过 | 其他 ROM、Root 管理器与真实应用兼容性；中文仍走无障碍，不外推任意 shell 能力。 |
| Root 拒绝和停止 | 超时/取消/竞态 JVM | 拒绝或撤权不换后端、不挂起；进行中命令遇 STOP 后无新动作。 |
| 窗口截图 | opt-in/Image JVM；最终 API 34+ 夹具窗口图片可读、尺寸有效 | 未选、低 API、安全窗口不产生全屏替代图片；真实页面及多模态模型链另测。 |
| 锁屏与切换前台 | token/窗口/环境 JVM；夹具第二页短暂未知窗口过渡通过 | 人工切出 fixture、锁屏或出现系统授权界面时暂停；用户恢复后重新观察，不操作锁屏/授权页。 |
| 遮挡与输入法 | 后端区域和上层窗口检查代码 | 人工制造测试遮挡，坐标动作不能穿透；输入法不能被当作目标窗口。 |
| 旧节点与页面变化 | 快照年龄、版本、单次使用 JVM | 人工改变测试页，旧 snapshot/node 拒绝；不重复已生效动作。 |
| 敏感/截断页面 | 密码、词表、文本与树限额 JVM | 合成敏感页面不输出受保护字段；截断不能动作/截图；不用真实密码、付款或验证码。 |
| 通知 STOP 与后台 LLM | 旧通知/许可 JVM；最终真实 PendingIntent/receiver STOP 链与旧 token 拒绝通过；生成取消代码 | 人工点击通知 STOP 或删除通知立即撤权；等待的模型请求/pending 结束；通知不被聊天进度覆盖。 |
| 日志和通知隐私 | 请求日志 JVM、生成级脱敏代码 | 合成输入标记不进入 logcat、请求日志或通知；STOP 后迟到输出仍隐藏正文。 |
| 预算与无变化 | 30 动作/90 观察/5 分钟、三次无变化 JVM | UI 计数一致，到限拒绝新动作；恢复不续期，无无限模型动作循环。 |
| 真实模型任务 | 本次尚无通过证据 | 用户确认模型、费用和内容发送后，先让模型完成 fixture 明确任务，验证最终 UI；accepted/screenChanged 不替代成功。 |
| 真实应用与人工共用 | 本次尚无通过证据 | 仅测用户明确授权的低风险任务，记录部分完成、失败和接手点；承认同应用内人工操作竞态。 |

## 执行和记录

1. 主线统一构建、执行并核对 APK/test APK 与代码版本。先用独立测试页面，保留现有用户数据。
2. 先测默认无障碍，再测 Root 和 API 34+ 截图。权限不足、系统不支持、管理器拒绝分别记 `SKIP` 和原因。
3. 用户明确同意后再验证真实模型；会发送获准内容并可能产生费用，与离线仪器测试分开记录。
4. 每项记录设备/系统、时间、APK 标识、后端选项、PASS/FAIL/SKIP、可见结果和 STOP 收尾。只留合成测试数据，不保存密钥、真实号码或完整真实页面正文。
5. 按“测试 → 反馈 → 修改 → 再验证”处理失败。设备、模型和待测边界取得实际证据后再更新验收结论；当前不能宣布 V1 完成。
