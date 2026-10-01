# M2.18：209 推荐词搜索入口重验证（拒绝原因待细分）

更新：2026-09-28。范围仍为 V1。版本 `2.5.4-mobile-agent-v1-m2.18-search-entry`／209 已通过最终独立审查、533 项定向 JVM 回归、APK 构建及 v2 验签，并保留数据覆盖安装；源码与标签已推送并远程核对。普通首页恢复后已进行真实模型测试，明确搜索按钮及推荐词入口两次点击均因 revision 变化被控制器拒绝，未进入搜索页；重验证资格的具体拒绝原因仍待细分。

## 依据与范围

208 第二轮实际生产观察含 85 个节点，页面完整且非 `scrollOnly`。模型选中的是推荐词入口，没有选中 207 支持的明确“搜索”按钮；两次已核对点击均因 `REVISION_CHANGED` 被拒绝。原始证据留在项目根 `artifacts/v1-m2.17-root-scroll/model-run-2/`，208 的失败结论不改写，见 [M2_17_ROOT_SCROLL.md](M2_17_ROOT_SCROLL.md)。

这个入口有可核对的固定结构：祖父为京东资源 `id/b2q`、描述“搜索栏”的容器，父级为空 `ViewGroup`，入口只有一个 text 与 description 相等的推荐文字 `TextView`；同级另有固定“拍照购”和“搜索”按钮。本页不公开节点／会话随机标识、具体坐标、推荐词或设备正文。这些特征只用于识别这一搜索入口，不代表一般空白商品容器可以重新绑定。

## 209 已实现的限制

- **独立范围。** 新增宿主识别的 `JD_SEARCH_ENTRY`，与 `JD_SEARCH_NAVIGATION` 分开。完整树按各自 scope 检查唯一性，预览外或不安全的重复候选同样造成歧义；模型不能自报 scope 或略过重复节点。
- **先查原始安全信息，再允许有限变化。** 原始签名的敏感、完整性、交易限制、字段／元数据裁切和结构条件先核对；不满足条件时不产生可执行证明。之后只在比较中归一化推荐文字的 text／description／contentFingerprint，以及入口和该文字节点的右边界。
- **其余结构保持一致。** 原路径、左／上／下边界、祖父与父容器几何、节点类型和行为属性，以及固定“拍照购”／“搜索”按钮的完整子树仍参与一致性比较。推荐节点必须仍是唯一指定子节点，且保持有效包含关系和按钮间几何顺序；不能因文字变化而忽略新增敏感信息、交易属性或额外子节点。
- **使用生产完整树构建证明。** `buildPhoneClickRevalidationProofs` 从实际检查的签名与路径生成证明，并接入 `AccessibilityTreeReader`。后端从要求整份证明列表唯一，改为检查是否存在按 scope 合格的证明，使两个可信 scope 可共存；每个 scope 的完整树唯一性仍保留。
- **仅一次重新核实与原生点击。** 沿用原 snapshot、token、窗口身份和 10 秒时限；仅进行一次新的完整安全读取，以原路径的新 handle 严格解析节点，通过后只做一次原生点击，结果来源为 `executor=accessibility`。不延长旧快照、不偷偷改点其他控件、不增加 Root 搜索后备，也不重放未知动作；208 的受限 Root 翻页实现不因本次而扩大范围。

## 验证与版本状态

本轮最终证据位于项目根 `artifacts/v1-m2.18-search-entry/`：

- `production-compile.log`：生产代码编译成功，用时 49 秒。
- `unit-tests.log`／`unit-summary.json`：最终 **533 项／45 套件，失败、错误、跳过均为 0**，用时 41 秒。新增 18 项真实 proof builder 回归已纳入，最终独立审查未发现阻塞问题；这仍是本地工程验证。
- `apk-build.log`：主包和测试包构建成功，用时 35 秒；`signatures.log`：两包 v2 验签通过。两包 `install -r` 均成功，`installed-version.txt` 确认版本 209 及对应版本名；无障碍服务自动连接。

源码检查点 `988e58bdda1672df9f8ca5bc24f534d3c944f013`，标签 `mobile-agent-v1-m2.18-search-entry-internal`，已推送；首次远程核对时分支和标签解引用均指向该提交。后补文档不改变 APK 源码身份。归档仅留本地：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `RikkaHub-Mobile-Agent-v1-m2.18-209-arm64-debug.apk` | 84,218,633 | `388FC32B7D2CE779002836D8C4EEECD194F0E522D8451525BF526D734345CECB` |
| `Mobile-Agent-v1-m2.18-209-device-tests.apk` | 1,443,051 | `A2594E426B0E95BF5B7CC0808AFFC898FA7F79BC03044BAA1E3880486D141A56` |

回滚点为 208 源码 `7028ff2f434bc557f0fff40dabecd49e95edfb56`／标签 `mobile-agent-v1-m2.17-root-scroll-internal`。208 最终 APK 在 `artifacts/v1-m2.17-root-scroll/review-fixed/`，其上一级首轮 208 包仅为历史产物。

## 验证页历史与本轮开始

安装后曾请求打开本应用，但 UI 检查返回前台不匹配；22:09 的只读画面及前台活动核对确认现场为京东快速验证页。当时尚未提交新模型测试，也没有操作验证，该准备阶段不算 209 模型执行失败或通过。约 22:19 再次只读核对时，京东已恢复普通首页；验证完成者及方式未知，主线没有进行验证点击，不能归因为 APP 自动完成验证或用户采用了某种确定方式。

随后用新聊天提交同样的只读三商品普通语句，确认实际提案后执行。模型期间主线没有在京东点击或滑动，浮窗消失后才返回本应用；任务卡明确显示本轮模型正常结束、手机控制授权撤销，不是主线 STOP 或干预造成的结束。

## 209 首轮真实工具结果

本地证据在 `artifacts/v1-m2.18-search-entry/model-run-1/`。原始截图和工具全文仅留本地，本页不嵌入验证画面，也不复制推荐词、随机调用／快照标识或坐标。

- `click-1-detail.txt`：选择明确搜索按钮，真实返回 `accepted=false`、`code=STALE_SNAPSHOT`、`reason=REVISION_CHANGED`，快照年龄 1,919 ms、`window_changed=false`。
- `click-2-detail.txt`：选择推荐词入口，真实返回同样的 `accepted=false`／`STALE_SNAPSHOT`／`REVISION_CHANGED`，快照年龄 1,905 ms、`window_changed=false`。
- `observe-1-full.txt`／`observe-2-full.txt` 已直接解析并分别匹配对应点击的快照。两次均为 84 节点的完整观察，`sensitive=false`、`truncated=false`、`previewTruncated=false`、`scrollOnly=false`、`inspectionIssues=[]`；完整观察不等于点击时仍满足重验证资格。
- 两次拒绝均来自控制器的通用快照校验，现有工具结果没有证明已触发或完成后端 fresh 页面重验证。已确认 revision 变化，但尚不能确定具体哪个重验证门槛未满足，也不能把推荐词轮播当作唯一已证实原因。

本轮没有进入搜索页。下一步补充重验证门槛的诊断元数据，定位拒绝分支；当前证据不支持继续扩大 scope 或放宽更多安全条件。工程测试和源码／APK 身份保持不变，先前版本的失败记录不覆盖。

搜索成功、三候选比较、商品／SKU 归属、评价抽样、最优优惠券、美团及非 Root 业务仍未验收。真实 Root 翻页也没有业务通过证据；209 的代码不能追溯改变 208 的拒绝结果、205 Back 或 203 点击的未知状态。
