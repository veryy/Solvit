# 角色默认与对话内授权、消息队列

用户报告新建窗口角色与选择不一致，并要求聊天标题/输入区显示角色名、工具权限在对话里批准、生成期间继续发送入队、逐条直接发送打断。完整要求保留，不能以入口或单元数量替代实际交互。

读取锁定DSH d743267388641bc76f17c45ce8b4c231aed1d32c 的 client/ui-conversation/src/client/queue/QueueDock.tsx、tests/queue-dock.client.spec.tsx、api/session-controller/src/commands.ts、tests/control-queue.host.spec.ts 与 apps/web/tests/queue-actions.e2e.ts。采用会话归属、唯一队列项、按项编辑/移除/发送、Stop保留待发消息、取消清理完成后继续的约束。MIT只读，不移植React/Cordis。Operit固定dbf71916fae9750cfdc9f9a774f5a0fee56633fb ConversationService.kt以会话角色卡组装请求；继续核对实际角色新会话默认逻辑及内联授权组件。

现有ChatViewModel.newChat确实复制当前会话角色，既有机制回归通过；不能据此确定用户旧版本的问题已修好。当前代码没有持久的新对话默认，重开无会话或切换旧对话后默认与用户上次明确选择未统一。新增原生原子保存显式选择与新对话默认，保留旧会话角色；旧安装没有默认字段时继续从源会话继承。已删角色继续报错阻断，不能自动放宽工具范围。

先提交可编译的契约和真实存储/发送状态回归，使用远程复现；不在本地构建。FIFO测试在等待首轮时提交两条消息，要求接收成功且只在首轮结束后依序发送、最大并发1。后续还须验证中断结算、重复文本按ID编辑/删除、队列归属及停止保留、真实Compose权限/队列/角色交互和APK。
