# V1 M1.1：原版更新与推广入口清理

## 版本与产物

- 源码：`8c2863489a77e290970ed34225376b6e20e69bb3`。
- 分支：`feature/mobile-agent-v1`。
- 构建检查点：`mobile-agent-v1-m1.1-cleanup`，已推送用户 Fork；不表示完整 V1 验收通过。
- 版本：`2.5.4-mobile-agent-v1-m1.1` / 190。
- 包名：`me.rerere.rikkahub.debug`；ABI：`arm64-v8a`。
- APK：`artifacts/v1-m1.1/RikkaHub-Mobile-Agent-v1-m1.1-8c286348-arm64-debug.apk`（相对于仓库上一级项目目录）。
- 大小：83,794,426 字节。
- SHA-256：`5a246bd53e820696007fb0b44f2203db9ffb971df29efb806e816b9e512036bc`。
- 原 M1 APK 和 `mobile-agent-v1-m1-capabilities` 检查点保留。

## 修改范围

- 移除侧栏原版更新卡、更新提醒设置；通过编译配置同时阻断上游更新请求和 APK 下载。禁用状态为未检查，不冒充最新版。
- 关于页改为 Mobile Agent 项目信息，仅保留用户 Fork 源码与许可证入口。
- 移除原官网、原仓库推广、QQ／抖音群、Discord、原版文档、赞助入口和自动赞助弹窗。
- 旧导航栈中的赞助页面恢复到当前关于页，避免升级后重新显示原版赞助内容。
- 七种语言的分享文案指向当前项目；导出图片的原官网水印改为项目名称。
- 保留原项目来源说明、根 LICENSE 和第三方许可证。

## 构建与主机验证

- 最终 `:app:assembleDebug` 成功，包含旧赞助路由的兼容处理。
- Root 检测与更新逻辑共 22 项 Gradle 单元测试通过：0 失败、0 错误、0 跳过。
- 更新测试确认禁用时不会调用更新源；失败与取消不会误报检查成功。
- APK v2 签名、包名、版本与 ARM64 架构通过校验。
- 生成配置：`UPSTREAM_UPDATES_ENABLED=false`、`FIREBASE_ENABLED=false`。
- 日志：项目根 `.build-cache/m1-1-assemble-final.log`、`.build-cache/m1-1-tests.log`。
- 构建环境与 Windows 测试编码处理见 `M1_BUILD_REPORT.md`。

## 一加真机验证（2026-09-26）

- 用户称一加 Ace 5 Pro；ADB 实读 OnePlus PKR110、Android 16 / API 36。
- 使用 `adb install -r` 从 M1 覆盖到 M1.1，返回 Success；未卸载、未清除应用数据。
- 冷启动成功，耗时约 1.314 秒；所采集的应用进程启动日志未发现 FATAL EXCEPTION / Fatal signal。
- 实际界面已确认侧栏没有原版更新卡，设置页关于区域只保留关于、请求日志、分享。
- 关于页显示当前项目、版本 190、用户 Fork 源码和 AGPL-3.0 许可证入口。
- 扩展管理 → 手机 Agent → 设备能力页可正常进入，读取到正确设备信息；初次进入显示 Root 尚未验证。
- 显式 Root 检测结果为 Root 不可用，提示找不到可运行的 `su`；用户确认没有授权弹窗。
- 随后实际读取 SukiSU Ultra 首页：工作中、LKM，内核 `v4.0.0-ae7b4dcb@main`，管理器 `v4.0.0-spoofed (40114)`；管理器已重命名，不能通过默认包名是否存在判断 Root。
- 用户在 SukiSU 超级用户页为 `me.rerere.rikkahub.debug` 手动开启权限后，本应用显式检测成功，显示已验证 Root 权限，确认 UID 为 0。
- 普通刷新更新设备刷新时间，保持上次 Root 检测时间不变；从管理器返回页面也未自动重新探测 Root。
- 仅强制停止并重启本测试应用（未清数据），重新进入设备能力页，状态恢复尚未验证；再次显式检测成功确认 UID 为 0。
- 该管理器采用按应用授权的模式。官方 [v4.0.0 allowlist](https://raw.githubusercontent.com/SukiSU-Ultra/SukiSU-Ultra/v4.0.0/kernel/allowlist.c) 与 [main sucompat](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/main/kernel/feature/sucompat.c) 可解释未授权时的 `su` 不可见；未取得现场精确提交源码，未声称逐行审计该设备版本。
- 真机证据保存在项目根 `artifacts/v1-m1.1/device-tests/oneplus-20260926/`，不上传手机日志或界面内容到 Git。

Root 允许、被动刷新和进程重启验证已通过；授权前的 `su` 不可见也有实测证据。明确拒绝返回、实际挂起请求的取消／超时及 OPPO 非 Root 真机验收尚未全部完成，不能用主机测试代替。当前电脑经 ADB 的页面操作不是应用已实现自主控机；M2 及之后的 V1 能力仍按实施状态继续开发，V2 不在本次范围内。

## 回滚

源码可在新分支检出 `mobile-agent-v1-m1-capabilities` 或对本次源码提交执行审阅后的 revert；不重写历史。Android 较低版本的 APK 不能保证直接覆盖安装，不使用卸载或清数据强行降级；如需回退应用，应从旧源码构建递增版本号并保留应用数据。
