# 会话输入草稿：先验证切换行为（2026-10-18）

承接侧栏标题搜索。现有MainActivity的文字输入由rememberSaveable(selected)持有，切换key会重建初值；附件则已按sessionId保存在ViewModel中。当前只是源码提出的输入丢失假设，尚未远程复现，也未实现生产修正。聊天助手的draft是流式回复，不能与用户输入草稿混用。README所述未发送草稿/队列不保留到进程死亡后的边界仍保留；不新增数据库迁移或秘密持久化。

已实际阅读固定Operit dbf71916 app/src/main/java/com/ai/assistance/operit/services/core/MessageProcessingDelegate.kt:583–644：setActiveDraftChat先保存旧chat再恢复新chat，空内容删除对应草稿，只有显式clear清除。其48行MessageProcessingDelegateTest.kt验证中断回复快照，没有草稿切换测试，不能据此宣称上游已验证该路径。

DSH d7432673 packages/client/ui-conversation/src/client/draft.ts使用有所有权的语义草稿快照，tests/draft-hub.client.spec.ts:193–212检查视图创建前恢复、复用现有草稿及显式清空。采纳按会话归属和明确清空行为，不移植浏览器存储或引用chip运行时。两库LICENSE已实际阅读，LGPL/MIT；无复制代码或新依赖。参考checkout前轮已verify-only核对，本轮尚无生产代码变动。

新增真实App回归：在实际侧栏新建两个独立会话，分别输入中英文文字，通过实际列表点击来回切换，断言各自内容、新会话为空、手动清空后保持空；不发送模型请求。每次等待真实侧栏进入屏幕并结束关闭，按真实ID滚动点击，保留一次点击；失败截图取自实际系统。先复用build96成功构建生成的APK远程运行新增测试，不把源码推送当作复现结果。完整门禁最低数量暂不改变，生产修正后再增加必需项并完整构建；不执行本地Android/TS构建或测试。

## 实际红测与修正候选

复用已通过完整检查的build96生产APK，远程39055662104（源码76435f3c）实际在10.792秒完成用例并失败：Returning to the first conversation must restore its unsent input，期望“草稿甲：准备检查项目配置 🙂”，实际空字符串；此前两个输入断言、新建空态、真实侧栏点击和会话ID断言均已通过。artifact12109303928（600290字节，sha256:52c0be515d30b1f5f8524b25141f0253d833abb573f9c13975eb33a79b4a661f）已核对实际大小、SHA256及CRC；失败PNG实际查看，输入框为空，未把编译错误或点击等待算作复现。

排序假设已在修改前展示：1）rememberSaveable(selected)因key变化重建；2）新建清空全局草稿；3）未实际切换或键盘提交。真实ID断言排除未切换。最小改变为将原生TextFieldValue由ChatViewModel按sessionId保存，包含文字、光标/选区；UI读取当前项，空文字移除当前项，删除成功只清该会话。原来的发送/排队/打断布尔返回值规则保留：被接受才清当前草稿；被拒绝仍保留。编辑分支仍只预填目标会话，不自动发送。无数据库/协议/签名或附件格式变化。

新增回归同时检查不同会话文字、新会话为空、手动清空不影响另一个会话、选区及Activity实际重建后的恢复。App最低86增至87并加入必需方法，其他角色/流式/附件/队列门禁保留。build97候选待GitHub Actions完整验证与新APK，不因修正源码声称已通过。生产无临时诊断日志，未执行本地Android/TS构建或测试。

第一轮98f14eee / Android39056255551在测试Kotlin编译阶段失败：ChatLayoutTest.kt:966 performTextInputSelection要求ExperimentalTestApi显式OptIn。生产assembleDebug已完成，设备尚未执行，不能将此记作草稿验证失败或成功。补充该方法的OptIn注解，保留原文字/选区/重建断言，生产逻辑不改；不交付该失败轮次产物。

在候选提交处理时，统一普通提交与立即打断的输入读取：事件边界核对绑定会话仍为当前会话，并读取ViewModel当前文字/附件，再使用原布尔接受规则清空对应ID；避免依赖上一帧用于绘制的输入快照。此为同一草稿所有权修正的一部分，未据此宣称另一个已远程复现的缺陷。ce187fca正在执行时被后续提交替代，最终检查必须针对新的实际源码完成。

## 选区检查与原生失焦规则

79e45161的构建、626单元、Lint及固定签名通过；定向39057876254在10.666秒执行后，文字恢复断言已通过，但原测试期待TextRange(2,7)，实际TextRange(7,7)。artifact12110291914已核对大小、摘要、CRC与原始runner；未将该失败忽略。

实际阅读项目既有Compose foundation 1.8.3源包：commonMain/androidx/compose/foundation/text/CoreTextField.kt:339在失焦时调用manager.deselect，selection/TextFieldSelectionManager.kt:572–584对非空选区用selection.max发布折叠光标，不改变已有光标位置。这正好对应2–7变成7–7；原断言把原生失焦时收起高亮当作恢复失败，预期不正确。现保留原生行为，先断言2–7选区确实建立，再断言切至乙时甲发布的光标为7，然后断言切回/Activity重建仍为7而不是文本末尾15；文字、新会话、手动清空隔离等原断言全部保留。生产编辑逻辑不改。先复用同一已验证构建APK定向检查，再执行修正测试的完整Android任务；79e旧完整任务不是最终验收。

## 完整顺序超时：build97尚未交付

完整Android39058592442/source98f7a085的build通过，626单元、Lint、APK和固定签名已逐项核对；独立定向39058554283通过不替代完整门禁。UI job117473448288最终exit124：常规App前12项结束，新草稿方法第13项在18:49:18Z开始后未结束；12分钟的既有总体限制生效。原始XML只留下该方法未完成的负时长，不能因failure字段为0算通过。Web3/terminal26已结束，15场独立恢复的实际verify与强杀前后PID核对通过；后续大字体和stdio强杀未执行。UI artifact12111192238，sha256:739c6162412c06b04f6aaac559b654d6fb7920412cb986b46f93aca22cca03f7，大小/CRC/原始日志已核对。15个prepare阶段的Process crashed是用例主动强杀，不能当成此次超时原因。

main仍保留build96；没有保存build97为已验证下载或生成成功说明。根因未明，不直接重跑整套或提高超时。排序假设：一是awaitClosed里的失败断言反复生成大型semantics诊断树；二是前序真实会话/服务状态干扰切换；三是原生输入法或选区等待。日志只见持续GC且没有新草稿截图，不能据此确定具体阻塞点。

增加仅测试参数draftTrace=true启用的[DEBUG-solvit-draft]步骤标记与15/30/45秒线程栈（只含线程/方法名，无配置或消息正文），采集main、当前测试和Compose线程。chat-drafts-sequence入口保留真实前12个方法与草稿方法，期望13项；诊断有独立300秒上限，原完整门禁12分钟不改，生产代码、签名和版本不改。下一步复用同生产源APK远程测量实际顺序，确定后删除临时探针。
