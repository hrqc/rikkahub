# V1 M1 构建与验证记录

## 产物

- 源码提交：`7c5f2491d6329380a7c240ea2161341a7e6188b3`。
- 已推送构建检查点：`mobile-agent-v1-m1-capabilities`（指向上述源码；不表示真机验收通过）。
- 分支：`feature/mobile-agent-v1`。
- 版本：`2.5.4-mobile-agent-v1-m1` / 189。
- 包名：`me.rerere.rikkahub.debug`。
- ARM64 APK：83,615,413 字节；同一安装包用于 Root 与非 Root 手机。
- SHA-256：`9e8b7f5c533fdbc8fc4cfceb4a0fd466c1157b5e5d6476236a9e23f824cf0717`。
- 本地产物保存在项目根的 `artifacts/v1-m1/`，未把二进制、私钥或环境配置上传 Git。

该版本只实现设备能力面板与显式 Root 检测。无障碍操作、截图、购物、资料研究、媒体理解和开发闭环尚未完成，不代表整个 V1 已通过验收。

## 已通过

- `pnpm install --frozen-lockfile` 与原有 `web-ui` 的生产构建。
- `:app:assembleDebug`：独立复核 `BUILD SUCCESSFUL`，产出 ARM64、x86_64 和 universal APK。
- `:app:testDebugUnitTest --tests "me.rerere.rikkahub.data.mobileagent.*"`：17 项，0 失败、0 错误、0 跳过。
- 测试覆盖被动刷新不请求 Root、授权分类、重复请求、取消、超时、输出上限和实际子进程阻塞管道的释放。
- `apksigner verify`：Android Debug 签名，APK v2 签名有效。
- `aapt2 dump badging`：包名、版本和 ARM64 架构与预期一致。
- 合并 Manifest：Analytics deactivated 为 true，Crashlytics collection 为 false；SDK 仍被打包，未声称移除。
- 生成 BuildConfig：`FIREBASE_ENABLED=false`。

## 本机工具链

Gradle 9.6.0、AGP 9.4.0、Kotlin 2.4.10、Android Studio JBR 21.0.10、SDK 37.2、CMake 3.22.1。通过项目外置 init 脚本复用 NDK 29.0.14206865 / Build Tools 37.0.0；这与上游默认 NDK 28.2.13676358 / Build Tools 36.0.0 不同。

最初组合构建已成功生成 APK，但 Gradle 测试 worker 因中文路径的参数文件编码失败。仅为测试 daemon 设置 `file.encoding=GBK` 后正式测试通过；未修改系统编码，APK 保持 UTF-8 构建。取消的 NDK 默认版本下载半成品已单独清理。

本机 CMD 复现（在仓库目录）：

```cmd
"D:\codex\手机agent制作\.build-tools\run-gradle.cmd" :app:assembleDebug
"D:\codex\手机agent制作\.build-tools\run-gradle.cmd" :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.mobileagent.*" "-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=GBK"
```

此脚本含本机工具位置，不作为通用安装脚本。其他机器使用 Gradle Wrapper，并准备相应 JDK、SDK、NDK、CMake 与 pnpm。

## 尚待验证

独立 API 35 模拟器使用本机已有镜像创建于项目缓存。英文目录联接解决了模拟器的一项中文路径写入错误，Guest 日志确认约 22 秒完成启动；但指定的 ADB/console 端口未监听，主机 ADB 没有识别该实例，因此未安装 APK，启动烟测不计通过。原有 `Codex_API_35` 数据未修改。测试实例在检查结束后关闭，故障日志保留于 `.build-cache/m1-emulator/logs/`。英文入口 `D:\codex\mobile-agent-v1-workspace` 只是指向原项目的目录联接，没有移动项目数据。

OPPO Find X6 Pro 与 Root 一加 Ace 5 Pro 的安装、页面、授权、拒绝、取消和超时均尚未实测；Android 与 Root 管理器版本待现场读取。只有后续完整 V1 测试通过并得到用户明确确认，才可创建 `mobile-agent-v1-stable`。

日志位于项目根 `.build-cache/`：`baseline-google-services-check.log`、`m1-build-test.log`、`m1-tests-windows-encoding.log`、`m1-assemble-verified.log`。正式测试 XML 位于 `app/build/test-results/testDebugUnitTest/`。
