# 角色默认与对话内授权、消息队列

用户报告新建窗口角色与选择不一致，并要求聊天标题/输入区显示角色名、工具权限在对话里批准、生成期间继续发送入队、逐条直接发送打断。完整要求保留，不能以入口或单元数量替代实际交互。

读取锁定DSH d743267388641bc76f17c45ce8b4c231aed1d32c 的 client/ui-conversation/src/client/queue/QueueDock.tsx、tests/queue-dock.client.spec.tsx、api/session-controller/src/commands.ts、tests/control-queue.host.spec.ts 与 apps/web/tests/queue-actions.e2e.ts。采用会话归属、唯一队列项、按项编辑/移除/发送、Stop保留待发消息、取消清理完成后继续的约束。MIT只读，不移植React/Cordis。Operit固定dbf71916fae9750cfdc9f9a774f5a0fee56633fb ConversationService.kt以会话角色卡组装请求；继续核对实际角色新会话默认逻辑及内联授权组件。

现有ChatViewModel.newChat确实复制当前会话角色，既有机制回归通过；不能据此确定用户旧版本的问题已修好。当前代码没有持久的新对话默认，重开无会话或切换旧对话后默认与用户上次明确选择未统一。新增原生原子保存显式选择与新对话默认，保留旧会话角色；旧安装没有默认字段时继续从源会话继承。已删角色继续报错阻断，不能自动放宽工具范围。

先提交可编译的契约和真实存储/发送状态回归，使用远程复现；不在本地构建。FIFO测试在等待首轮时提交两条消息，要求接收成功且只在首轮结束后依序发送、最大并发1。后续还须验证中断结算、重复文本按ID编辑/删除、队列归属及停止保留、真实Compose权限/队列/角色交互和APK。

两个原生默认角色回归已在38442536815及38442609560复现失败（RoleServiceTest line38/51；75项中2失败），与既有每会话角色继承回归不是同一问题。原子持久默认选择已加入，旧安装无默认时继承源会话，删除默认继续阻断。FIFO测试在这些执行中尚未运行，不能声称已复现所有队列断言。VM队列原型新增唯一ID/会话归属、编辑删除、正常完成后FIFO、Stop保留并暂停、cancelAndJoin后优先发送；当前队列在VM生命周期内，进程死亡后的持久恢复仍须补齐/明确，不能称DSH完整durable Inbox。追加中断非取消清理等待及重复文本/跨会话回归。

进一步读取Operit services/core/ChatHistoryDelegate.kt createNewChat（775起）：取全局ActivePrompt角色并在创建时绑定名称，采用其明确默认与会话绑定一致的语义；DSH ui-approval/ApprovalPanel.tsx及tests/ui-approval.client.spec.tsx采用输入区内联等待卡、详细参数及一次性应答/失败可重试，不复制原生React实现。
