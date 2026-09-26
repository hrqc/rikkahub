# V1 M2：手机控制内部测试版

2026-09-26。基于用户 Fork 的 RikkaHub，V2 未修改。已完成控机基础闭环及合成页面实测，尚未完成完整 V1 验收。

## 源码与安装包

- 源码：`3ae621ca70e44fa4c7e956f0ad4ebe1a4d79a340`。
- 分支：`feature/mobile-agent-v1`。
- 内部构建检查点：`mobile-agent-v1-m2-control-internal`，不是用户验收的 V1 stable 标签。
- 版本：`2.5.4-mobile-agent-v1-m2` / 191；主包 `me.rerere.rikkahub.debug`。
- 主 APK：项目根（仓库上一级）`artifacts/v1-m2/RikkaHub-Mobile-Agent-v1-m2-3ae621ca-arm64-debug.apk`。
- 大小：84,302,433 字节；SHA-256：`4d890d5ffb671c3c4aacaa274b7cac77f31baaecab9da7770c5466b07963b366`。
- 独立测试 APK：`Mobile-Agent-v1-m2-3ae621ca-device-tests.apk`，1,286,400 字节；SHA-256：`3eca81f708968c919a1b8e616f80c757742ebd8a2856d207f23b940e0adc9a52`。
- 主 APK v2 签名验证通过，ARM64 架构与版本信息核对通过；默认仍关闭上游更新与 Firebase。
- 原 M1/M1.1 安装包保留；上一构建回滚点 `mobile-agent-v1-m1.1-cleanup`，源码 `8c2863489a77e290970ed34225376b6e20e69bb3`。

## 本次交付

聊天更多菜单增加手机控制面板，绑定当前聊天和助手，明确选择一个目标应用。支持读取有界界面树、打开目标、点击、长按、中文输入、滚动/滑动、返回、暂停/恢复和 STOP。Root 输入与截图各自默认关闭、单独授权；Root 模式的中文输入仍走无障碍。

授权、快照和窗口在每次执行时复核。停止/替换/恢复会话不能复活旧调用。每次最多 30 个动作、90 次观察、5 分钟。同应用换页的窗口未知状态最多等待 750 毫秒，期间不读取页面或派发动作；超时暂停，已知其他应用立即暂停。敏感、受保护或未完整检查的页面交给用户；该策略不能识别所有真实应用风险或人工触摸冲突。

截图仅使用 API 34+ 的目标窗口接口，不退回全屏截图。请求/工具/流日志与通知避免记录输入及页面正文；工具结果仍保存在既有聊天中，用户开始模型任务后会进入当前模型上下文。另有“仅准备控制”模式用于不调用模型的本地授权与打开目标。

## 构建与主机验证

- `:app:testDebugUnitTest` 的 Agent、隐私及更新回归共 **68 项通过，0 失败/错误/跳过**，包括两个真机发现问题对应的窗口过渡用例。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功。
- `git diff --check` 通过；源码与测试未包含凭据、签名文件或设备原始记录。
- 本地日志：`.build-cache/m2-transition-tests.log`、`.build-cache/m2-transition-apk.log`。
- 最终 JVM XML 与测试日志保存在项目根 `artifacts/v1-m2/host-tests/`。
- Windows 单测使用项目构建包装器和 `-Dfile.encoding=GBK` 处理中文路径；APK 构建保持默认 UTF-8。未修改系统区域/全局环境。

## 一加实机结果

设备为用户提供的一加 Ace 5 Pro，实读 OnePlus PKR110、Android 16 / API 36。用户已在 SukiSU Ultra 授予主应用 Root，已开启无障碍和通知，并授权本轮必要的同一无障碍服务重连。

最终证据：项目根 `artifacts/v1-m2/device-tests/oneplus-20260926/22-final-smoke.txt`，**55.291 秒，OK (3 tests)**，各项结束状态为成功，非 skip。

| 测试 | 实际验证 |
| --- | --- |
| 无障碍动作 | 点击与长按计数、中文文本、滚动位置、进入第二页及 Back 返回；直接 STOP 后旧 token 不能观察。 |
| Root 动作 | 进程重启后显式固定 UID 探测成功；Root 点击、长按、滚动、返回有效，中文仍用无障碍；发送本应用通知的 STOP PendingIntent，经真实接收器停止后旧 token 失效。 |
| 目标窗口截图 | 单独允许截图，只截图测试 APK；FileProvider 本地 URI 可读且图片尺寸有效；未上传、未调用模型。 |

测试对象是无网络权限、只含合成数据的独立 APK。主应用使用覆盖安装，未卸载或清空用户数据。新聊天菜单与控制面板能正常进入。测试结束后重新安装同一已归档主 APK 恢复普通运行，实读无障碍已绑定、Crashed services 为空。

实测修复与环境限制：

1. 独立测试包会去重主包 Kotlin 依赖，夹具改为纯 Android Java Activity 后冷启动通过。
2. ColorOS 会弹出主应用打开测试应用的确认；本轮选择“仅本次允许”，未添加 30 天许可，也未让模型处理系统授权页。
3. Instrumentation 重启/结束主进程时，ROM 可能把无障碍标记为断开；测试时通过已授权的系统开关重连，普通运行最终已恢复。
4. 同一应用换页时短暂无窗口元数据，原逻辑误暂停；现保留禁止动作的检查并给予有上限的过渡等待，最终第二页与 Back 均通过。

## 仍待验收

- OPPO Find X6 Pro 的非 Root 兼容性；一加上的非 Root 执行通道通过不等于 OPPO 已通过。
- 配置模型后的真正聊天工具调用、长任务完成判断和网络取消；本轮没有调用收费模型 API。
- 真实应用场景、人工触摸并发、输入法/遮挡、合成敏感页的设备验证、锁屏/断连/预算完整设备矩阵。
- 物理点击通知 STOP 与模型后台生成组合测试；当前覆盖的是该通知真实 PendingIntent/接收器链路。
- V1 购物、研究、视频、编码和存储能力的后续开发与整体验收。详见 `M2_TEST_PLAN.md`。

回滚优先使用 Git 新分支/revert 或重新构建旧检查点，不使用 reset/clean。旧版本号较低的 APK 是否能覆盖安装取决于 Android 的降级限制；不要通过卸载主应用来绕过并丢失数据。
