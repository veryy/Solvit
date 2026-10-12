# 角色故障复核（0.21.1 候选）

用户仍反馈模型不知道当前角色。此前的提示段、绑定与 HTTP 测试不能证明用户网关上的实际回答正确，本轮不把这些测试当作真实模型验收。

## 上游行为

实际读取锁定版本 Operit 的 `api/chat/enhance/ConversationService.kt`、`data/preferences/CharacterCardManager.kt` 和 `core/config/SystemPromptConfig.kt`。对话传入 `roleCardId`，读取当前卡片，把 `combinePrompts` 的角色设定作为 `customIntroPrompt`；`applyCustomPrompts` 替换 `BEGIN_SELF_INTRODUCTION_SECTION`，而不是将默认介绍和角色介绍并列发送。工具范围由同一角色决定。另读 DSH `packages/core/system-prompt/tests/system-prompt.spec.ts` 的身份、Persona 和有名称提示段契约。未复制上游实现代码。

## 本轮发现与改动

Solvit 的 `ChatContextComposer` 原来把模型设置的内置“你是 Solvit，一个认真、可靠的 AI 助手。”保留在 `user-rules` 中，自定义角色同时贡献另一身份。原有 HTTP 测试主动使用了不含身份的“通用助手风格”，没有覆盖实际默认配置。

回归提交 `c7bdcdf` 改用实际默认配置，在真实 OpenAI Provider 的 HTTP 请求边界断言角色替换内置介绍。修复提交 `bb65e7d` 将内置默认介绍定义为统一常量，自定义角色生效时移除这段内置介绍；默认角色仍使用它。明确填写的其他全局规则保持原文。角色绑定、工具授权、聊天历史、数据迁移和签名机制没有改动。

后续 HTTP 回归还覆盖角色设定编辑、重新创建角色服务和聊天服务、显式全局规则保留，以及切回默认角色。模型响应是明确标注的固定测试响应，仅验证请求链路，不能证明真实模型遵循角色。

新增模拟器回归 `roleAppliedThroughSettingsReachesProviderAndNewChatRequest`：通过真实设置页面点击角色应用，返回聊天并点击发送，再通过侧栏新建对话后发送。实际 `AgentLibraryViewModel`、`AndroidRoleService`、`ChatViewModel`、`DefaultChatService` 和 `ToolAgentRuntime` 连通到 Provider 捕获边界，断言两个不同会话中的角色名称/设定、默认介绍替换。聊天存储通过接口替换、模型返回固定响应；不冒充真实模型回答正确，也不代替已有 HTTP 边界回归。

## 验证状态

本轮本地提交完成，但 GitHub HTTPS 网页、API 与 `git push` 均在 TLS 握手时失败（`SSL_ERROR_SYSCALL`）；服务器 SSH 也未成功。未运行本地 Android 构建或测试。新增回归尚未在远程执行，不能声称已经取得失败→成功证据。0.21.1 未打标签、未发布、没有新 APK。

仍需恢复连接后运行完整 Actions、检查实际测试结果和签名、发布新 APK；并使用用户实际模型复核选择角色后、新对话及重开对话的身份回答。现有源码发现不能断言为用户症状的唯一原因。
