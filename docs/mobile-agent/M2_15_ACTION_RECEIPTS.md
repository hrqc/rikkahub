# M2.15：206 动作派发回执

更新：2026-09-28。范围仍为 V1。版本 `2.5.4-mobile-agent-v1-m2.15-action-receipts`／206 已完成独立静态审查、最终定向 JVM 回归、APK 构建与 v2 验签，并保留数据安装到一加；源码和标签已推送并远程核对。206 真机业务尚未启动，工程验证不代表购物业务验收。

## 实现内容与边界

- **保存已返回的派发状态。** 动作后端返回后，控制器在再次授权检查或挂起前同步记录 `accepted`。后续暂停、取消或完整工具结果未能发布时，可保留这一已知事实；后端没有返回，仍不能推断动作是否发生。
- **精确绑定调用。** 每次 generation 使用独立内存 ledger，绑定完整会话 token，以及 `messageId`、`toolCallId`、完整 `toolName`；仅记录既有动作工具，截图和只读工具不进入动作回执。重复 key 被标为歧义，不能用同名工具或其他调用的结果补齐，仍降级为 unknown。
- **有界且只存元数据。** 每次最多 64 个调用 key，各身份字段限 1～128 字符；不保存参数、节点正文、截图或原始异常消息。完成时冻结并清理可写 ledger，冻结后拒绝迟到写入；后续观察错误仅保留固定白名单代码，其他代码统一为 `POST_OBSERVATION_FAILED`。
- **区分接受与核实。** 回执可表达 `not_accepted`、`accepted_unverified` 或 `accepted_observed`。只有后续观察完整且不是 `scrollOnly` 才可标记已核实，并保留 `screenChanged`；平台接受或页面变化均不代表商品任务完成。
- **正常工具结果优先。** 会话结束收尾只补本次精确绑定、尚无结果的调用，已有完整结果保持不变。没有可信回执、容量不足或标识歧义时保留 unknown；回执的 `grantsActionPermission=false`，不恢复授权、不增加动作权限，也不允许直接重放未知动作。

这是当次 generation 内的有界内存元数据，解决取消／暂停等收尾期间已知工具输出丢失的问题；不是进程崩溃或被系统杀死后可恢复的持久动作日志。它只影响后续调用，不能回填或反推 205 那次 Back 已派发、被接受或执行成功；203 的历史点击也继续保持未知。

主要连接位于 `PhoneActionReceipts.kt`、`PhoneController.kt`、`PhoneTools.kt`、`GenerationLoop.kt` 和 `ChatService.kt`。

## 已完成的工程验证

- 首轮编译因新上下文对象的 `key` 字段与 `AbstractCoroutineContextElement.key` 冲突失败，已重命名为 `receiptKey`。项目根 `artifacts/v1-m2.15-action-receipts/unit-tests-initial-compile-failed.log` 保留该次失败；这是编译失败，不是已执行的测试失败。
- `unit-tests.log`／`unit-summary.json`：最终定向回归 **455 项／41 套件，失败、错误、跳过均为 0**，包含本轮新增 22 项；构建日志用时 1 分 40 秒。
- `apk-build.log`：主包和测试包构建成功，用时 43 秒；`signatures.log`：两包 v2 验签通过。两包 `adb install -r` 均返回 Success，保留原有数据；`installed-version.txt` 确认已安装版本 206 及对应版本名。
- 安装后只启动本应用做就绪检查，`post-install-state.txt` 确认前台为本应用 `RouteActivity`、Mobile Agent 无障碍服务已连接、`Crashed services={}`、`keyguard showing=false`。没有重连服务、启动新模型或操作京东；这项检查不等于业务验证。

源码检查点为 `61aa80881bb2880df7a3da4c828c748b1caad918`，标签 `mobile-agent-v1-m2.15-action-receipts-internal`。首次推送核对时，远程分支与标签解引用均指向该提交；后补文档不改变 APK 源码身份。回滚点为 205 源码 `873c83ab92b88c9bbe88ed207343c00dbcbd8d16`／标签 `mobile-agent-v1-m2.14-model-completion-internal`。

归档仅留项目本地 `artifacts/v1-m2.15-action-receipts/`：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `RikkaHub-Mobile-Agent-v1-m2.15-206-arm64-debug.apk` | 84,182,185 | `C77EB17EF76D2ADA5F68E5581038944D51C8E474E9C0625F16764ED023C5D539` |
| `Mobile-Agent-v1-m2.15-206-device-tests.apk` | 1,443,051 | `E9CF38048ABFCB92D79E1071739618CC30B639D8210E19504E52917E6854CF13` |

## 真机业务边界

206 新的真机业务尚未启动。主线仍在等待用户确认已手动离开京东收银台、回到普通首页／搜索页；覆盖安装不能作为动作回执或购物业务的实机通过证据。此前收银台交易来源未核实，205 工具序列无 click／payment 调用的边界不变，见 [M2_14_MODEL_COMPLETION.md](M2_14_MODEL_COMPLETION.md)。

三候选同规格比较、商品／SKU 归属、评价抽样、最优优惠券、美团及非 Root 业务仍未验收。本轮是结果记录改进，不能当作完整购物验收。
