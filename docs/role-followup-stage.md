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

此前 GitHub HTTPS 网页、API 与 `git push` 在 TLS 握手时失败（`SSL_ERROR_SYSCALL`），修复及回归仅保留在本地。连接恢复后已实际推送 `main` 至 `0ffe8c5597aa3f9d52a1a50424d36472b856513e`，并通过 `git ls-remote` 核对远程分支。

[修复前远程运行 38238110267](https://github.com/veryy/Solvit/actions/runs/38238110267)在 `c7bdcdf` 复现 HTTP 边界问题。实际下载 XML 核对270个单元测试仅1个失败、无错误/跳过，唯一失败是 `ModelTaskRoutingTest.persistedRoleReachesActualHttpRequestOnEveryTurnIncludingDefaultIdentity`，断言消息为 `A selected character must replace the built-in Solvit introduction at the HTTP boundary`。诊断分支不发布 APK。

[修复候选远程运行 38237983149](https://github.com/veryy/Solvit/actions/runs/38237983149)全部成功：测试、Lint、APK、固定签名、模拟器和覆盖升级。实际下载 XML 核对271个单元和42个模拟器测试，均无失败、错误或跳过。上述 HTTP 检查、全局规则保留检查及 `roleAppliedThroughSettingsReachesProviderAndNewChatRequest`、`applyingRoleInSettingsPersistsAndNewChatInheritsItsPluginWhitelist` 均通过；UI报告包含 `role-settings-to-provider-new-chat.png`。取得的是默认身份冲突及实际界面到请求链路的验证证据，尚非用户真实模型回答的失败→成功验收。未运行本地 Android 构建或测试。正式标签发布继续通过完整发布门禁。

## 正式发布与范围

[0.21.1 Release](https://github.com/veryy/Solvit/releases/tag/v0.21.1)已公开发布，源提交 `fc220939e250a2ca9d981465d1ba83b4235c65eb`。[正式标签运行38239629561](https://github.com/veryy/Solvit/actions/runs/38239629561)第二次尝试全部成功：构建、固定签名、插件、公开搜索、模拟器及发布。

第一次正式尝试保留两项失败证据：公开搜索第三个查询的 Bing 和 DuckDuckGo 页面都要求人机验证；已有 `nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations` 用例在 `performScrollToNode` 时触发 Compose `SnapshotStateObserver` 跨线程读取异常。角色界面到请求的用例在该次尝试仍通过。原条件重跑失败任务后，搜索三项查询的相关官方来源及正文验证通过；42个模拟器测试无失败、错误或跳过，包括首次失败的模型设置用例。未确定或修复该测试框架异常根因，不把重跑成功称为根因修复。

实际下载正式单元XML核对271个测试均无失败、错误或跳过；正式UI成功报告来自产物11743755455，公开搜索成功证据来自11742894488。Release为非草稿、非预发布，最新版本API返回 `v0.21.1`。实际下载公开APK与正式构建产物11743116102内的APK逐字节一致，23,067,851字节，SHA-256 `a1340c497c42b6a8f4d2c8d97d8554776bc60bc699e2647d7020cd06c007e47d`。

仍需使用用户实际模型复核选择角色后、新对话及重开对话的身份回答。现有源码发现不能断言为用户症状的唯一原因，未进行真机或真实模型身份回答验收。
