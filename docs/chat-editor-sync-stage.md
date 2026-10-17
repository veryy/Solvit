# 聊天编辑器：发送后的清空也要保持一致

本阶段继续打磨草稿切换与发送逻辑。保留整个 `agent-direction.md` 路线、原数据和固定签名；未开始新的插件功能。

## 发现的问题与原始证据

7c444196的普通完整Android39067827303成功，原始628单元、Lint、116常规设备与15独立恢复均核对。但是额外PDF触摸对照暴露原测试遗漏：实际HTTP500已结算后，草稿可能仍非空。不能用原绿色用例数量代替这一新状态要求。

独立分支a5b1ca32的39070457299（原点击时序）及39070457383（原生IME几何前置条件）各8轮原PDF场景通过，归档分别12114289004/12114288957；完整字节/服务器摘要/CRC与每个runner、64条实际日志已核对。16轮都只有原一次点击，down/up实际命中按钮并到达真实本地HTTP500；原时序第2、7轮结算后draftPresent=true，IME组为0/8。没有复现此前请求0的90秒等待，不能认定两者同因，也不能宣称IME根因已经证明。

新增永久回归在同一真实选页/扫描/原图/HTTP500结算后，要求实际输入框和当前会话草稿都为空；保留之后的重试、内容一致、重开与编辑分支。失败时保存可控PDF夹具截图。新pdf-chat选择只重复原完整用例，不添加等待、不调用VM发送、不重复点击；生产分支不带DEBUG触摸观察代码。先以原生产APK检查红色，生产改动尚未应用。

## 上游与Android适配依据

- Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb，`services/core/MessageProcessingDelegate.kt` 的setActiveDraftChat、setUserMessageDraft和clearUserMessageDraft：按聊天保存TextFieldValue、显式清空目标聊天；其`_userMessage`也是StateFlow，不能借它证明Solvit的Flow往返没有同步问题。`ui/features/chat/screens/AIChatScreen.kt` 实际以collectAsState取得输入。现有MessageProcessingDelegate测试不覆盖本次IME/清空交错。
- DSH d743267388641bc76f17c45ce8b4c231aed1d32c，`packages/client/ui-conversation/src/client/draft.ts` 与`tests/draft-hub.client.spec.ts`：草稿文本、编辑器状态与聊天所有权一致，只有明确clearPreviousDraft才清空；浏览器编辑器不是Android输入法，不能直接移植其测试作为IME证明。
- Google官方foundation1.8.3源码`commonMain/androidx/compose/foundation/text/BasicTextField.kt`及[BasicTextField契约](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/BasicTextField.composable)：新值须于下一帧前回传。官方[状态迁移说明](https://developer.android.com/develop/ui/compose/text/migrate-state-based)明确指出StateFlow往返可能存在文本输入同步风险。

三个待区分的判断：①生命周期收集Flow的额外调度使旧输入覆盖清空；②迟到IME事件即使同步状态仍可写回旧输入；③清空了错误会话。先保留失败断言和实际草稿/发送所有权，再验证同步快照状态这一单一变量；未运行前不写成修复成立。

九个参考checkout已再次verify-only核对，LGPL-3.0/MIT和Google Apache-2.0源码头已阅读。未复制上游实现、未新增依赖、未运行本地Android或TS构建/测试。真实模型、真机、ARM64/第三方及正式市场部署门禁边界继续保留。

## 同步编辑状态候选与验证边界

正确EditableText断言的原APK对照39071910702/f66726d8实际完整通过4轮（22.204、23.006、20.903、23.559秒），第5轮在附件选择后的15秒导入条件失败，尚未进入PDF预览或发送。artifact12115935209已核对779700字节、服务器SHA256与CRC；不把该失败记作草稿红测。先前39071428686的assertTextEquals误将占位文字计入内容，亦不作为产品红测。a5b1ca32已记录的2/8实际结算后草稿非空仍是状态异常证据；用户可见残留及先前请求0的根因尚未证明。

本候选只将用户编辑草稿从StateFlow＋生命周期收集改为ViewModel持有的Compose State，UI直接读取同一份状态，使文字变更和接受发送后的清空不需要额外收集调度。事件边界仍读取当前会话最新值，拒绝发送保留输入；按会话的文字、光标、Activity重建、附件与排队语义均保留。所有草稿写入仍在UI/Main调度执行，没有后台快照写入或第二份编辑器数据。此举符合BasicTextField同步回传契约，是待验证的同步风险修正，不能据此宣布晚到IME事件或偶发点击问题已根治。

使用新versionCode99区分既有98候选字节；versionName0.43.10、数据库迁移、签名与插件包均不改。生产无临时DEBUG探针，新永久断言覆盖原真实单次点击、HTTP500及重试完整场景。待普通完整Android及无探针pdf-chat定向回归后再交付APK；不执行本地Android或TS构建/测试。

## build99实际结果

最终源码ae9dd436的[Android39072422869](https://github.com/veryy/Solvit/actions/runs/39072422869)以none模式成功，628单元、Lint、116常规设备、15独立恢复、覆盖升级、150%聊天与stdio实际宿主强杀均通过。新APK版本99、固定签名、内置14插件、语言资源、ZIP摘要/CRC及真实草稿/终端截图已逐项核对。同APK无探针[PDF原场景8轮](https://github.com/veryy/Solvit/actions/runs/39072935471)全部通过，包含实际EditableText和VM草稿均空；旧APK未稳定复现的边界仍保留，不倒写为确定红绿根因。候选APK、Linux源码与验收JSON独立保存，build96文件和历史失败记录不改。正式发布/真实模型/真机限制见版本说明。

## 本轮截图复核后的界面待办

用户要求继续优化UI及交互逻辑。已实际查看本版草稿/终端与PDF选页截图，以及此前已提交的Skills/MCP和记忆图谱拼图。下一轮先处理长附件文件名撑高标题区、密集图谱总览标签偏小，以及工作区/插件页面的操作层级与留白一致性；不把这些列为build99已完成。每项以浅色/深色、长标题、150%字体、原生键盘和实际任务截图检查；加载、空态、失败、重试和返回也属于验收，而非只有静态外观。完整Agent/真实模型任务路线继续保留。
