# 会话输入草稿：先验证切换行为（2026-10-18）

承接侧栏标题搜索。现有MainActivity的文字输入由rememberSaveable(selected)持有，切换key会重建初值；附件则已按sessionId保存在ViewModel中。当前只是源码提出的输入丢失假设，尚未远程复现，也未实现生产修正。聊天助手的draft是流式回复，不能与用户输入草稿混用。README所述未发送草稿/队列不保留到进程死亡后的边界仍保留；不新增数据库迁移或秘密持久化。

已实际阅读固定Operit dbf71916 app/src/main/java/com/ai/assistance/operit/services/core/MessageProcessingDelegate.kt:583–644：setActiveDraftChat先保存旧chat再恢复新chat，空内容删除对应草稿，只有显式clear清除。其48行MessageProcessingDelegateTest.kt验证中断回复快照，没有草稿切换测试，不能据此宣称上游已验证该路径。

DSH d7432673 packages/client/ui-conversation/src/client/draft.ts使用有所有权的语义草稿快照，tests/draft-hub.client.spec.ts:193–212检查视图创建前恢复、复用现有草稿及显式清空。采纳按会话归属和明确清空行为，不移植浏览器存储或引用chip运行时。两库LICENSE已实际阅读，LGPL/MIT；无复制代码或新依赖。参考checkout前轮已verify-only核对，本轮尚无生产代码变动。

新增真实App回归：在实际侧栏新建两个独立会话，分别输入中英文文字，通过实际列表点击来回切换，断言各自内容、新会话为空、手动清空后保持空；不发送模型请求。每次等待真实侧栏进入屏幕并结束关闭，按真实ID滚动点击，保留一次点击；失败截图取自实际系统。先复用build96成功构建生成的APK远程运行新增测试，不把源码推送当作复现结果。完整门禁最低数量暂不改变，生产修正后再增加必需项并完整构建；不执行本地Android/TS构建或测试。
