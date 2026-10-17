# PDF发送：先确定真实点击是否到达

普通候选 `7c444196` 的 Android `39067827303` 完整成功：628单元/Lint、116常规设备、15独立恢复、大字体和stdio实际强杀；APK固定签名、14内置包及原始报告/截图已核对。额外完整诊断 `39067425677` attempt 2 在PDF发送等待失败，不能因普通绿色忽略它，也不能称整个诊断成功。诊断之前的备份恢复销毁越界这次未重现，根因仍未知。

失败归档 `12114618342`（12840393字节）按服务器摘要/ZIP CRC核对：App87中仅 `PdfChatDeviceTest.pickerSelectedScanPreviewHttpRetryReopenAndEditPreserveTheSameSelection` 失败，Web3/terminal26无失败。原始行165的90秒等待超时；每用例logcat明确为ready=true、busy=false、error=false、requests=0、messages=[]、attachments=1。实际失败截图中选页附件和合成输入仍在。OCR、页码选择、原图预览已经完成，这不是OCR超时或网关返回错误。此前0.43.3阶段也出现过同一发送等待，单独四项后来通过并不能证明根因解决。

排序假设：①原图返回后输入唤起原生IME，按钮仍在移动，真实单次触摸落空；②发送入口读到会话/草稿状态不一致，未提交；③发送前配置或附件检查失败。仅凭失败后的稳定截图不能证明当时触摸命中哪一处。

## 对照和边界

独立测试分支仅添加可选 `[DEBUG-solvit-pdf-touch]`：通过既有 `ChatInteractionDeviceTest` 使用的父级初始pointer事件观察模式记录原一次物理点击的down/up与按钮实时边界、IME高度/焦点、会话匹配和状态计数；不消费事件、不调用VM发送、不重试点击、不记录文本/请求/凭据。普通模式的页面组成、操作与90秒结算断言不变。

`pdf-send` 重放原流程；`pdf-send-ime` 只在原物理点击前等待原生IME可见且输入区确实位于其上，采用既有聊天交互测试的几何前置条件，无固定sleep。两者都保留文件选择、OCR、HTTP失败/重试、重开及编辑分支原断言；成功还要求实际down/up都落入发送按钮。它们复用同一已验证生产APK；探针未进入APK。本阶段尚未远程编译或运行，未修生产代码、未发布新包。

## 实际上游阅读

参考仓库九个固定版本已再次通过 `fetch-upstreams.py --verify-only`。Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `ui/features/chat/components/style/input/agent/AgentChatInputSection.kt` 中 `handleEnterSendAction` 和KeyboardActions共用发送/排队逻辑；`ChatMessageTest.kt` 验证消息角色及内容数据，不覆盖原生IME触摸，不能冒充此问题的上游回归。DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `ui-conversation/src/client/input/submission-policy.ts` 及 `tests/submission-policy.client.spec.ts` 验证忙碌时的实际提交选择，不证明Android触摸已经送达。本阶段只借鉴区分提交动作与宿主权威状态的边界，Android诊断采用本项目已有真实pointer观察及IME几何检查。已阅读LGPL-3.0/MIT许可，无代码复制、无新增依赖。

## 第一轮对照：通过流程不等于编辑器状态正确

`39070457299`（pdf-send）和`39070457383`（pdf-send-ime）各8个原PDF流程通过；64条实际pointer/发送状态日志已读取，每次down/up都在发送按钮内、会话匹配且最终HTTP请求1。它们没有复现原来请求0的失败，不能认定IME是假设中的根因。观察代码在点击前获取语义节点并切换主线程，也可能改变时序，单独通过不替代失败原顺序。

检查日志却发现pdf-send第2和第7轮在真实HTTP500结算后`draftPresent=true`，其余6轮以及IME组8轮为空。已发送后输入复现是另一个实际编辑器状态缺陷，原断言未覆盖。新增永久断言：原HTTP失败结算后，当前会话VM草稿及实际TextField都为空；仍只进行原一次点击，仍保留扫描/选页/重试/重开/分支。先用原7c生产APK复测红色，再改生产状态，不将8个绿色runner当成问题已解决。

此项排序：①StateFlow经生命周期收集回传TextField存在额外调度，使清空被旧编辑值覆盖；②迟到IME编辑事件独立于Flow回传仍覆盖新值；③清空目标会话错误。已读Google官方BasicTextField契约和本地官方foundation1.8.3 `commonMain/androidx/compose/foundation/text/BasicTextField.kt`，要求onValueChange值在下一帧前回传；官方迁移文档也说明StateFlow与文本输入同步的风险。它们支持优先验证①，不替代实际修复后的同一路径回归。参考 https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/BasicTextField.composable 和 https://developer.android.com/develop/ui/compose/text/migrate-state-based 。本阶段尚未修改生产代码。

