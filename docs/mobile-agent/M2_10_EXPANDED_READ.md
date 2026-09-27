# M2.10：201 结构诊断与后续扩展读取计划

更新：2026-09-28。范围仍为 V1。201 是受控工程验证检查点，不能代替模型自主多页购物、最优优惠或评价业务验收。

## 201 已实现和验证的范围

201 新增独立 DEBUG 结构采样：复用同一无障碍 backend 的读取、授权、窗口及配置恢复路径，在树遍历时保存真实子节点路径、父路径、资源 ID、边界和缺口。该导出不接收正文、描述或图片，不进入现有生产诊断 JSONL，也不发布可执行观察或购物证据。

- 每次最多 768 个节点、256 KiB（预算含换行）、15 秒；超限或撤销后拒绝整份导出。
- 完整性及 inspection issues 来自调用方已检查的树，不能用“没有记录 gap”自行声明完整。
- 最多三个所选路径只标注待检查根，不裁掉祖先及其余已记录结构；不据路径或重复资源 ID 直接认定商品归属。
- 产物先写应用私有 cache，再经 USB 取到本地 artifacts；测试日志只含状态、路径、字节数及哈希。未完成的本次文件会清理，既有记录保留。
- `productScopeVerified=false`、`contentIncluded=false`。结构采样不赋予点击、下单、支付或其他新权限。

200 的原生滚动烟测曾在观察前返回 `ACTIVITY_CHECK_EXCEPTION`，动作数为零。201 修正测试前置检查后另行实测；以下成功结果不能倒填为 200 已成功。

## 201 真机事实

主 APK 与测试 APK 已安装到用户的一加。原始证据位于项目根 `artifacts/v1-m2.10-structure-diagnostics/`，均保留本地，不进入公开 Git。

### 一次原生滚动实际成功

`jd-native-scroll-201-a.json`、`jd-native-scroll-201-a.log` 记录：

- 观察前及滚动前的 Activity 检查均为 `MATCH`，目标为京东 `ProductListActivity`。
- 通过生产 Controller 派发一次原生滚动：`actionRequestsIssued=1`、`controllerActionsUsed=1`、`accepted=true`、`screenChanged=true`。
- 结果为 `ACCEPTED_SCREEN_CHANGED`、`engineeringScrollPassed=true`。目标为 `RecyclerView`，资源 ID `com.jd.lib.search.feature:id/ub`。
- 前后窗口相同、revision 从 7 变为 18；前后均为 `scrollOnly=true`、`truncated=true`，只提供一个滚动节点，未取得完整商品正文。
- `useRoot=false`、`allowScreenshots=false`；测试自己的会话随后停止清理。
- `businessTaskAccepted=false`。这是测试驱动的一次实际滚动成功，不是模型自主多页筛选完成。

### 后续独立只读结构采样完整

随后在普通结果页执行 `INCLUDE_UNIMPORTANT` 结构采样，证据为 `jd-structure-201-a-result.json`、`jd-structure-201-a.json` 和 `.log`：

- `outcome=COMPLETE`，实际服务 flags 为 82，诊断树预算为 768 节点。
- 实际结构文件含 515 个节点、零 gap；`treeTruncated=false`、`inspectionIssues=[]`。
- `controllerActionsUsed=0`、`businessTaskAccepted=false`、`containsText=false`，结构内 `productScopeVerified=false`。
- 文件为 159,695 字节；SHA-256 为 `E431D86B0D1542AFEA9B6E4FBBC6079CB7C889F0315C870F0618D6011C9DB6C7`，与导出结果一致。

这里的 `COMPLETE` 只描述这一次独立诊断读取。该采样与此前滚动前的页面状态不同，不能据此认定单靠 flags 已修复全部京东页面；200 的 563 节点／一个缺口记录仍然有效。201 的生产默认读取仍为原配置及 512 节点预算，515 节点的诊断结果尚未变成生产模型可用的完整商品观察。

两个 runner 都为 `OK (1 test)`、无 assumption skip。滚动结果依据实际动作与前后观察确认；结构结果依据文件内容及哈希确认，不能只用 JUnit 汇总代替这两个结论。

## 构建与 JVM 验证

- 版本：`2.5.4-mobile-agent-v1-m2.10-structure-diagnostics`／versionCode 201；包名 `me.rerere.rikkahub.debug`，主 APK 为 arm64-v8a debug。
- 本轮定向 JVM XML：24 个套件、275 项，失败／错误／跳过均为零，包含 `PhoneDebugStructureCaptureTest` 新增 14 项。此测试集合与 200 的 362 项不同，不能相加或称为完整重跑 362 项。
- `unit-tests.log`：`BUILD SUCCESSFUL in 1m 1s`；`apk-build.log`：主包与测试包 `BUILD SUCCESSFUL in 43s`。
- 两份 APK 均通过 apksigner v2 签名验证。JDK 使用 Android Studio `jbr`。
- 主 APK SHA-256：`0A369A5754D7D288B67F324721D41FCC8C69FC9464BD337583B608A1FD10DAD5`。
- 测试 APK SHA-256：`F8B53AB879CB4FE0F3CC4BFB2DCE588B7578FCA6C31B66E37113E795B333A715`。

201 源码检查点为 `f321c103138dfb5e93ef400cf808bea2b5bf1dbf`，前一基线为 `e5306a766d6a16083dba2efaaa2dd285a117172e`。该源码提交不含后续补记文档；文档提交不改变上述 APK 的源码身份。标签与推送状态由主线完成后登记到本地 `BUILD_INFO.md`。APK、结构文件、日志及手机内容不随源码公开推送。

## 尚未完成与 202 计划

1. **截至 201 检查点，202 的生产读取调整尚未实施或验证。** 下一步拟审查 `includeUnimportant` 在生产读取中的配置及对应预算、完整性、停止与恢复边界；不能把 201 的临时诊断配置记为已上线。
2. 宿主还没有核证单张商品卡片、标题与价格所属、SKU／商家身份及跨页可靠去重。结构路径是调查材料，不是商品身份。
3. 未完成模型自主翻页、至少三个可比候选、费用核实和评价抽样的端到端流程；重复文案仍不能确认刷单或建立质量排名。
4. 最优券筛选、普通免费领券、应用后的金额核对，以及美团、淘宝、拼多多和非 Root 设备验收均未完成。

继续按测试、反馈、修改、再验证推进；一次工程滚动成功和一次结构完整采样都不代表 V1 整体验收通过。
