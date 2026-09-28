# M2.14：205 模型完成时的 watchdog 清理

更新：2026-09-28。范围仍为 V1。205 已通过最终定向 JVM 回归、APK 构建和 v2 验签，并保留数据安装到一加；源码与标签已推送并远程核对。解锁后的真实模型运行已验证正文工具调用、一次成功滚动及未知调用收尾；任务随后暂停并 STOP，购物业务仍未验收。

## 已复现问题与修复

- 独立本地并发实验使用八个 `Dispatchers.Default` worker，三个正常完成场景各执行 80,000 次模型等待。原实现合计 240,000 次中出现 246 次关闭通道异常；等待 watchdog 清理完成的实现同样执行 240,000 次，关闭通道异常为 0。实验不使用手机、网络或模型 API；调度具有非确定性，不能承诺旧代码每轮都复现。
- 原实现先 `cancel()` 再关闭进度通道，没有等待 watchdog 结束，可能与另一线程下一次 `activity.receive()` 的关闭通道快速路径竞争。生产修复仅在 `finally` 中以 `NonCancellable` 执行 `watchdog.cancelAndJoin()`，随后 `activity.close()`，让正常完成和外部取消都先等待子协程退出。
- 原有 60 秒无进展限制、普通聊天不启用该限制、仅约束模型／准备等待而不计工具执行的边界保持；没有新增自动重放、恢复授权或手机动作。
- 该实验确认了代码缺陷及修复的本地证据，不能反推它就是 203 那次只剩点击参数、204 或所有历史模型中断的原因。204 的未知结果收尾是独立保护，既不确定历史点击是否执行，也不能替代本次并发修复。

本地实验统计与来源说明保存在项目根 `artifacts/v1-m2.13-observed-content/progress-timeout-race/README.md`。本页只记录非敏感统计，不复制原始日志或设备内容。

## 最终验证与版本身份

- 版本：`2.5.4-mobile-agent-v1-m2.14-model-completion`／205，包名 `me.rerere.rikkahub.debug`。源码提交 `873c83ab92b88c9bbe88ed207343c00dbcbd8d16`，标签 `mobile-agent-v1-m2.14-model-completion-internal`；源码分支和标签均已推送；首次推送核对时远程分支与标签解引用指向该提交，后补文档提交不改变 APK 源码身份。204 的后补证据文档提交为 `06daaa4730e177e74caa3341de5b4b00d497ec05`，不改变 204 APK 身份。
- 项目根 `artifacts/v1-m2.14-model-completion/unit-tests.log`／`unit-summary.json`：最终 **433 项／40 套件，失败、错误、跳过均为 0**，构建日志用时 35 秒。新增回归包含 40,000 次并发正常返回，以及模型异常、外部取消、流上下文和后续工具期间没有残留 watchdog 等边界；这里的 40,000 次属于单元测试，不与独立 240,000 次实验合并计数。
- `unit-tests-initial.log` 保留首轮 433 项中一项失败：测试错误要求最外层异常对象保持同一身份，而协程 debug／stacktrace recovery 可以复制该对象。修正断言后验证异常类型、消息及 cause 链保留原始异常身份；`watchdog-debug-after.log` 记录显式开启 debug／recovery 的独立 9 项测试通过。首轮失败不改写为通过，也不与最终回归相加。
- `apk-build.log`：主包和测试包构建成功，用时 37 秒；`signatures.log`：两包 v2 验签通过。主线已通过覆盖安装和设备版本信息核对确认 205，保留原有数据。归档为 `RikkaHub-Mobile-Agent-v1-m2.14-205-arm64-debug.apk` 与 `Mobile-Agent-v1-m2.14-205-device-tests.apk`，均仅保留本地。

## 205 真实模型复测

初次安装后手机锁屏，未开始模型任务；本轮解锁后复测时已核对版本 205、无障碍服务已连接且无 crashed 标记。证据位于项目根 `artifacts/v1-m2.14-model-completion/followup-20260928/`，以下仅记录调用与状态，不附设备私密内容。

- `RUN_NOTES.md` 记录已确认仅在京东浏览、比较同规格候选并查看评价的任务；没有授权领券、加入购物车、下单或付款。模型执行期间主线没有在京东滑动、点击或返回。
- 展开的实际工具顺序为 `observe → read_observed_content × 2 → scroll → back`，证明模型已调用保留正文工具；调用次数不等于三候选或评价样本已核证。
- `run205-scroll-details.txt`：滚动参数为初始快照的 `n1`、`forward=false`，真实返回 `accepted=true`、`screenChanged=true` 并带新观察。这是 APP 模型发起的一次实际滚动成功，不是主线手势或仅凭模型文字判断。
- 任务卡以 `PAGE_UNSTABLE` 暂停；`run205-back-details.txt` 的最后返回为 `interrupted`／`execution_outcome=unknown`。未知调用收尾已经出现在真实模型结果中，但不能据此判断 Back 是否实际派发或执行。主线检查暂停状态后明确 STOP，结束该次授权；不得重放这次未知 Back。
- 主线在任务暂停后查看京东时，现场为收银台页面；本轮已观察调用序列没有点击或付款调用。该交易来源以及 Back 是否造成页面切换均未建立，不能推断模型创建或支付了订单。主线没有操作收银台控件，已请用户手动回到普通首页／搜索页，等待回复后再开始新任务。

本轮不提供模型正常完成整项任务或 watchdog 真机缺陷根因的证明，203 的历史点击仍保持未知。206 正准备将派发回执绑定到精确 `toolCallId`，目前尚未完成或验收；它只影响后续调用，不能回填或反推 205 这次 Back 已派发、被接受或执行成功。

## 仍待验收

204 已通过的只读正文分页和此前跳过／未采样结果继续保留，见 [M2_13_OBSERVED_CONTENT.md](M2_13_OBSERVED_CONTENT.md)。三候选同规格比较、商品卡片／SKU 归属、中差评和追评采样、最优优惠券、美团及非 Root 设备业务仍未验收；本次没有扩大购物能力或绕过平台验证。
