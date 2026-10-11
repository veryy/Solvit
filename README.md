# Solvit

轻量 Android AI 聊天 App，也是可组合 Agent 平台的第一步。Kotlin + Jetpack Compose + Material 3，Android 8.0+；Android 12+ 使用 Material You 动态配色，可选择跟随系统、浅色或深色主题。

## 第一版功能

- OpenAI 兼容 Chat Completions：可配置基础地址、模型、API Key、系统提示词。
- SSE 流式回复，停止生成，网络与认证错误提示。
- 本机多会话历史、新建与删除、文字选择复制；SQLite 持久化。
- API Key 使用 Android Keystore AES-GCM 加密保存，禁用应用备份。
- 简单插件生命周期、类型化服务注册、声明式依赖、失败回滚、Coroutine 资源回收。
- 可替换 Provider、存储、Agent 与 ChatService；JS 插件工具发现、逐次确认、执行进度、结果回传和有界连续调用。

## 开始使用

[直接下载最新 APK](https://github.com/veryy/Solvit/releases/latest/download/solvit-debug.apk)，在 Android 手机上安装。版本说明见 [最新 Release](https://github.com/veryy/Solvit/releases/latest)。

完整版本列表：[所有 Releases](https://github.com/veryy/Solvit/releases)。当前发布的是 debug APK；真机安装、真实模型连接和交互体验尚未验证。

[0.8.1 已发布](https://github.com/veryy/Solvit/releases/tag/v0.8.1)，[88 个单元测试、5 个模拟器测试、Lint、APK、固定签名和插件验证通过](https://github.com/veryy/Solvit/actions/runs/38118726783)，[APK 构建产物](https://github.com/veryy/Solvit/actions/runs/38118726783/artifacts/11694248847)。本版包含原创放射线条图标、自动弹出键盘的悬浮输入框、模型错误详情、半屏侧栏、工作区、多 Shell 与 AI 插件草稿审阅安装。Android 35 模拟器已验证从公开 0.7.0 APK 覆盖更新后 API Key 可解密，模型设置与聊天记录保留。设置分为模型配置、角色与 Skills、插件、外观、配置备份、应用更新和关于。启动时顶部显示更新检查进度，只有发现新版才显示卡片；手动检查在“设置 → 应用更新”。版本说明在应用内查看，APK 在应用内后台下载并显示进度，校验完成后打开 Android 系统安装程序。

**签名修正：**实际检查发现 0.1.1 与 0.6.0 发布 APK 的证书不同，之前恢复 CI 密钥却没有明确指定 AGP 签名路径，旧版“固定签名”的说明不准确。0.7.0 起明确使用固定密钥，CI 强制校验 SHA-256 证书指纹 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。旧版签名私钥未保存，无法生成能覆盖它的包；迁移前请记录旧 API 配置并导出需要的 Skills。以后同签名覆盖更新保留应用数据。

“设置 → 配置备份与恢复”可使用密码加密导出模型/API Key、提示词、角色和外观；在重装或换机后导入，密钥会重新加密到本机 Keystore。备份文件应保存在应用之外。聊天记录、插件包、插件密钥和记忆暂不包含；卸载会删除数据，无法恢复此前未备份的配置。应用仍禁用自动云备份。

安装 debug APK 后，打开侧栏底部“设置 → 模型配置 → 模型与连接”：

| 服务 | API 基础地址 | 模型示例 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 本机兼容服务 | `http://127.0.0.1:8080/v1` | 服务提供的模型名 |

不要在基础地址末尾填写 `/chat/completions`。HTTPS 远程服务可使用自定义路径；HTTP 仅允许 localhost、127.0.0.1、::1、模拟器宿主地址 10.0.2.2。无需认证的本地模型可以留空 API Key。模型名称和账户额度以实际服务为准。

点击“获取模型”可从上述地址的 `/models` 拉取名称，搜索并选择后保存；供应商提供单独 JSON 订阅链接时，可填写“模型列表 / 订阅地址”。不提供列表接口的服务仍可手动填写模型名。支持格式、凭据规则及验证边界见 [获取模型列表](docs/model-discovery.md)。

## 在聊天中使用插件工具

安装插件后，在“已安装”页面启用并授予声明权限。默认角色可以使用全部已启用插件；自定义角色需在“设置 → 角色管理与 Skills”绑定相应插件。页面显示 AI 可发现的工具数量。配置的模型需支持 Chat Completions 工具调用，工具执行仍需确认。

时间插件 1.1.0 会在每轮聊天读取当前时间并加入模型上下文，因此模型不主动调用工具时也能回答时间。Solvit 0.7.0 提供 Android 真实时区；较旧 API 3 宿主回退到 JS UTC 时间。仅升级字节完全匹配原始离线包的时间插件，保留启用状态；修改过的插件不会被覆盖。禁用插件或角色未绑定时，不提供它的时间上下文。

模型提出工具调用时，Solvit 显示工具描述和完整参数，点击“允许这一次”后执行。拒绝会回传拒绝结果；“停止生成”会取消等待和执行。工具状态与结果可以展开查看。默认整次回复最多 8 轮、16 次工具执行，每次执行有 60 秒上限，JS 引擎期限为 30 秒。

成功回复的工具调用、结果和最终文本一起保存；失败或中断记录保留并从后续上下文排除。数据库升级保留已有聊天。尚未验证真实模型和真机上的完整调用体验。

## 工作区、Shell 与 AI 插件开发

当前源码已接入每个对话一个工作区、多 Shell 命令会话、工作区文本文件编辑，以及 API 4 的两个独立 JS/TS 插件。使用方式见 [工作区与 Shell](docs/workspaces.md)。

启用“插件开发”后，AI 可生成普通 JS 插件草稿，做清单及启动校验。你在“工作区 → 插件草稿”查看代码和权限后安装，安装后保持停用，需单独启用。应用内尚无 TS 编译器，完整流程与边界见 [AI 插件开发](docs/plugin-authoring.md)。

## 本地记忆与 TS 插件

当前源码提供可离线安装的本地记忆插件：事实合并、用户画像、记忆关系、文本分块、关键词搜索和导出。启用后，可以通过聊天请求保存或查找记忆，每次执行都需确认。使用方式与限制见 [记忆插件](docs/memory.md)。Android 宿主集成测试已通过远程验证；[0.4.0 已发布 APK](https://github.com/veryy/Solvit/releases/tag/v0.4.0)。

插件用 TS 编写后打包成 JS，开发接口与示例见 [TS SDK](plugin-sdk/README.md)。市场中的时间与记忆插件都可以独立更新。

## MCP 连接

当前源码已内置 TS MCP 插件和声明式配置页。进入“插件 → 已安装 → MCP 连接”，先配置 HTTPS 端点及可选 Bearer 令牌，再启用 network 权限。聊天可发现并调用服务器工具，每次执行需确认。

实际可测试的只读演示地址：`https://solvit.207.57.126.73.sslip.io/mcp-demo`，令牌留空，提供回显和服务器时间。协议支持与边界见 [MCP 插件说明](docs/mcp.md)。[0.5.1 APK 已发布](https://github.com/veryy/Solvit/releases/tag/v0.5.1)，[45 个 Android 测试、Lint、APK、TS、市场和实际 HTTPS MCP 验证通过](https://github.com/veryy/Solvit/actions/runs/38107628413)。角色绑定、OAuth、stdio 和完整服务接入继续实现。

## Skills 与角色

[0.6.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.6.0)，[完整远程验证与发布通过](https://github.com/veryy/Solvit/actions/runs/38109369009)。纯 TS Skills 技能库支持：SKILL.md、文本资源 ZIP 和 JSON 包导入，原生查看、删除、导出；每轮自动提供角色允许的元数据，正文及引用文件按需加载。使用入口是聊天顶部“角色”按钮；先在插件页启用 Skills，再创建角色绑定插件和技能。详细格式、权限和当前边界见 [Skills 与角色](docs/skills.md)。

角色提示词和插件绑定按对话保存，新对话继承当前角色。MCP、记忆和其他工具按插件绑定；模型工具仍需逐次确认。API 3 的 beforeModel Hook 只允许读插件存储和当前时间。

## 构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0。Android Studio 可自动安装 SDK；命令行设置 `ANDROID_HOME` 或在不提交的 `local.properties` 中填写 `sdk.dir`。

```sh
./gradlew :core:test :system-runtime:test :workspace-android:testDebugUnitTest :provider-openai:test :chat-runtime:test :release-updates:test :plugin-runtime:testDebugUnitTest :storage-sqlite:testDebugUnitTest :plugin-market:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 使用相同命令并上传 APK 和测试报告。Gradle Wrapper 固定为 9.3.1，AGP 9.1.1 使用内置 Kotlin，项目 Kotlin 版本 2.4.10，以接入具有超时与取消能力的 QuickJS 插件引擎。

## 架构

| 模块 | 职责 |
| --- | --- |
| `core` | 服务契约、插件管理、类型化事件、工具发现和授权接口，无 Android 依赖 |
| `provider-openai` | OkHttp 网络与 SSE，独立注册 ModelProvider |
| `storage-sqlite` | ChatStore 的 Android SQLite 实现 |
| `chat-runtime` | 有界工具 Agent、消息结算、上下文和 ChatService 插件 |
| `system-runtime` | Shell 作业、流式输出与取消引擎 |
| `workspace-android` | 持久工作区绑定、文件操作、多 Shell 会话与原生进程执行 |
| `release-updates` | GitHub Release 更新检查 |
| `plugin-runtime` | QuickJS 引擎、JS 工具适配、依赖、权限与执行限制 |
| `plugin-market` | HTTPS 目录、包校验、安装启停、独立存储和权限配置 |
| `app` | 启动组合、加密设置、ViewModel、原生 UI |

详细设计、参考源码、生命周期规则、扩展方式见 [架构说明](docs/architecture.md)。测试中的 Greeting 插件与 MemoryStore 演示服务扩展与存储替换。

后续完整产品目标与验证状态见 [产品进度](docs/product-roadmap.md)。更新检查是独立的 `release-updates` 内置插件。

## 当前边界

当前 0.8.1 提供流式聊天、多会话、JS/TS 插件、[插件市场](https://solvit.207.57.126.73.sslip.io/)、有界工具循环、MCP/Skills、角色、模型列表获取、配置备份和应用内更新。测试、Lint 与 APK 构建已通过 GitHub Actions；版本发布由标签构建完成。插件开发方式见 [JS/TS 插件 API](docs/plugins.md)。

当前 MCP 支持 HTTPS 工具发现、调用和按插件绑定角色；多服务器、每工具绑定、OAuth、stdio 及完整资源/提示词能力仍待实现。工作区与 Shell 已接入 Android 宿主和终端 UI，当前为应用 UID 的非交互式命令；PTY、持久环境、Root、Shizuku、Linux 发行版和 SSH/SFTP 尚未实现；手机控制、向量记忆、附件、Markdown 排版和后台 Agent 持续执行尚未实现。JS 插件有独立引擎和宿主权限检查，目前没有进程级隔离。聊天记录在应用私有 SQLite 中，未做数据库加密。

停止或失败后的助手回复保留并标记，但不进入后续模型上下文；用户输入仍保留。进程被系统杀死时，未结算的流式片段可能丢失，已写入的用户消息和完整回复保留。完整上下文发送给配置的模型服务，较长对话目前需要新建对话。
