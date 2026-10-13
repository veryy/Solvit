# 角色默认与对话内授权、消息队列

用户报告新建窗口角色与选择不一致，并要求聊天标题/输入区显示角色名、工具权限在对话里批准、生成期间继续发送入队、逐条直接发送打断。完整要求保留，不能以入口或单元数量替代实际交互。

读取锁定DSH d743267388641bc76f17c45ce8b4c231aed1d32c 的 client/ui-conversation/src/client/queue/QueueDock.tsx、tests/queue-dock.client.spec.tsx、api/session-controller/src/commands.ts、tests/control-queue.host.spec.ts 与 apps/web/tests/queue-actions.e2e.ts。采用会话归属、唯一队列项、按项编辑/移除/发送、Stop保留待发消息、取消清理完成后继续的约束。MIT只读，不移植React/Cordis。Operit固定dbf71916fae9750cfdc9f9a774f5a0fee56633fb ConversationService.kt以会话角色卡组装请求；继续核对实际角色新会话默认逻辑及内联授权组件。

现有ChatViewModel.newChat确实复制当前会话角色，既有机制回归通过；不能据此确定用户旧版本的问题已修好。当前代码没有持久的新对话默认，重开无会话或切换旧对话后默认与用户上次明确选择未统一。新增原生原子保存显式选择与新对话默认，保留旧会话角色；旧安装没有默认字段时继续从源会话继承。已删角色继续报错阻断，不能自动放宽工具范围。

先提交可编译的契约和真实存储/发送状态回归，使用远程复现；不在本地构建。FIFO测试在等待首轮时提交两条消息，要求接收成功且只在首轮结束后依序发送、最大并发1。后续还须验证中断结算、重复文本按ID编辑/删除、队列归属及停止保留、真实Compose权限/队列/角色交互和APK。

两个原生默认角色回归已在38442536815及38442609560复现失败（RoleServiceTest line38/51；75项中2失败），与既有每会话角色继承回归不是同一问题。原子持久默认选择已加入，旧安装无默认时继承源会话，删除默认继续阻断。FIFO测试在这些执行中尚未运行，不能声称已复现所有队列断言。VM队列原型新增唯一ID/会话归属、编辑删除、正常完成后FIFO、Stop保留并暂停、cancelAndJoin后优先发送；当前队列在VM生命周期内，进程死亡后的持久恢复仍须补齐/明确，不能称DSH完整durable Inbox。追加中断非取消清理等待及重复文本/跨会话回归。

进一步读取Operit services/core/ChatHistoryDelegate.kt createNewChat（775起）：取全局ActivePrompt角色并在创建时绑定名称，采用其明确默认与会话绑定一致的语义；DSH ui-approval/ApprovalPanel.tsx及tests/ui-approval.client.spec.tsx采用输入区内联等待卡、详细参数及一次性应答/失败可重试，不复制原生React实现。

终端用户报告进一步定位：WorkspaceDialog.interactive默认false，首屏显示LazyColumn命令任务+TextField，并非真正终端；现默认true进入已有插件xterm.js+addon-fit+Android PTY。保留明确“命令任务”切换，已有真实Android页面回归改为直接验证默认终端入口，其DOM input→xterm.onData→PTY、环境持久、输出、Ctrl-C等原断言不删。官方https://github.com/xtermjs/xterm.js/blob/master/README.md说明其输入输出需接真实PTY；https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java提供原生备选。本次不再引入第二套同用途组件，仍须实际远程默认入口验证。

追加用户要求：设置加入“聊天外观”界面配置，持久字体大小、回复显示速度与聊天样式，参考Operit设置组件。显示速度只调呈现队列，接收/解析继续独立；保留停止及生成期间继续输入。尚未实现与发布，不以默认角色/队列的修改代替此要求。

聊天外观实现读取Operit settings/sections/ThemeSettingsFontAvatarSections.kt与components/ChatStyleOption.kt（LGPL仅阅读，未复制），采用独立配置页、滑块、选中样式与实际消息预览。新增12–24sp字体、气泡/紧凑/简洁、自动/自定义10–400字每秒/即时显示，SharedPreferences持久及加密配置备份兼容；生产VM显示队列读取实时速度，接收与工具事件入队不等待显示，Unicode按codepoint处理。默认自动保留原追赶算法，慢速可停止；新增源已接收长回复且显示速度受控/即时切换和取消回归。仍须远程编译及实际设置/重开/字体/气泡/键盘布局验收。

工作区范围补充：本地Git之外需文件树、文件管理、文本/代码预览、编辑与Changes在同一个工作台；不把当前历史执行diff冒称完整Git/file explorer。路线先稳定聊天交互→完整工作区→执行验证闭环→插件生命周期一致性→记忆与手机真实任务。尚未完成的真实模型验收保持。

删除默认角色的新会话恢复边界：如果先解析默认后创建空会话，初始无历史时已删角色会让VM没有session、无法进入角色管理修正。新会话现在先建记录再原子绑定默认身份；原生bindNewChat保留已删roleId，selected仍明确报错并由UI missingRole阻断发送，用户可在该会话重新选择，避免静默扩大工具权限或陷入无会话死路。失败落盘仍回滚新会话。原已删默认阻断回归增加绑定后selected失败与显式重选恢复断言。
