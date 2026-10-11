# Solvit

轻量 Android AI 聊天 App，也是可组合 Agent 平台的第一步。Kotlin + Jetpack Compose + Material 3，Android 8.0+；Android 12+ 使用 Material You 动态配色，支持系统深色模式。

## 第一版功能

- OpenAI 兼容 Chat Completions：可配置基础地址、模型、API Key、系统提示词。
- SSE 流式回复，停止生成，网络与认证错误提示。
- 本机多会话历史、新建与删除、文字选择复制；SQLite 持久化。
- API Key 使用 Android Keystore AES-GCM 加密保存，禁用应用备份。
- 简单插件生命周期、类型化服务注册、声明式依赖、失败回滚、Coroutine 资源回收。
- 可替换 Provider、存储、Agent 与 ChatService；JS 插件工具发现、逐次确认、执行进度、结果回传和有界连续调用。

## 开始使用

从 [GitHub Releases 下载最新 APK](https://github.com/veryy/Solvit/releases/latest)。展开发布页的 **Assets**，下载 `solvit-debug.apk` 并在 Android 手机上安装。

完整版本列表：[所有 Releases](https://github.com/veryy/Solvit/releases)。当前发布的是 debug APK；真机安装、真实模型连接和交互体验尚未验证。

从 0.1.1 起，启动时检查 GitHub 最新正式版本；有更新会在聊天顶部显示下载卡片，也可以点击顶部“检查更新”按钮。下载更新会打开浏览器，无需向应用授予自动安装权限。

0.1.1 起使用固定 APK 签名，后续版本可保持相同签名。最初的 0.1.0 使用临时构建签名，无法覆盖安装为新签名版本；请勿直接卸载有重要记录的旧版，当前尚无聊天导出功能。

安装 debug APK 后，点击右上角模型设置：

| 服务 | API 基础地址 | 模型示例 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 本机兼容服务 | `http://127.0.0.1:8080/v1` | 服务提供的模型名 |

不要在基础地址末尾填写 `/chat/completions`。HTTPS 远程服务可使用自定义路径；HTTP 仅允许 localhost、127.0.0.1、::1、模拟器宿主地址 10.0.2.2。无需认证的本地模型可以留空 API Key。模型名称和账户额度以实际服务为准。

## 在聊天中使用插件工具

安装插件后，在“已安装”页面启用并授予声明权限。例如启用“当前时间”，然后在聊天中询问当前时间。配置的模型需要支持 Chat Completions 工具调用。

模型提出工具调用时，Solvit 显示工具描述和完整参数，点击“允许这一次”后执行。拒绝会回传拒绝结果；“停止生成”会取消等待和执行。工具状态与结果可以展开查看。默认整次回复最多 8 轮、16 次工具执行，每次执行有 60 秒上限，JS 引擎期限为 30 秒。

成功回复的工具调用、结果和最终文本一起保存；失败或中断记录保留并从后续上下文排除。数据库升级保留已有聊天。尚未验证真实模型和真机上的完整调用体验。

## 构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0。Android Studio 可自动安装 SDK；命令行设置 `ANDROID_HOME` 或在不提交的 `local.properties` 中填写 `sdk.dir`。

```sh
./gradlew :core:test :provider-openai:test :chat-runtime:test :release-updates:test :plugin-runtime:testDebugUnitTest :storage-sqlite:testDebugUnitTest :plugin-market:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 使用相同命令并上传 APK 和测试报告。Gradle Wrapper 固定为 9.3.1，AGP 9.1.1 使用内置 Kotlin，项目 Kotlin 版本 2.4.10，以接入具有超时与取消能力的 QuickJS 插件引擎。

## 架构

| 模块 | 职责 |
| --- | --- |
| `core` | 服务契约、插件管理、类型化事件、工具发现和授权接口，无 Android 依赖 |
| `provider-openai` | OkHttp 网络与 SSE，独立注册 ModelProvider |
| `storage-sqlite` | ChatStore 的 Android SQLite 实现 |
| `chat-runtime` | 有界工具 Agent、消息结算、上下文和 ChatService 插件 |
| `release-updates` | GitHub Release 更新检查 |
| `plugin-runtime` | QuickJS 引擎、JS 工具适配、依赖、权限与执行限制 |
| `plugin-market` | HTTPS 目录、包校验、安装启停、独立存储和权限配置 |
| `app` | 启动组合、加密设置、ViewModel、原生 UI |

详细设计、参考源码、生命周期规则、扩展方式见 [架构说明](docs/architecture.md)。测试中的 Greeting 插件与 MemoryStore 演示服务扩展与存储替换。

后续完整产品目标与验证状态见 [产品进度](docs/product-roadmap.md)。更新检查是独立的 `release-updates` 内置插件。

## 当前边界

当前已发布版本提供基础聊天和更新检查。0.2.0 已接入 [插件市场](https://solvit.207.57.126.73.sslip.io/)、JS 包下载校验、安装启停、权限授予和手动工具试运行；测试、Lint 与 APK 构建已通过 GitHub Actions；版本发布由标签构建完成。插件开发方式见 [JS/TS 插件 API](docs/plugins.md)。

Shell、手机控制、MCP、向量记忆、附件、Markdown 排版和后台持续执行尚未实现。JS 插件有独立引擎和宿主权限检查，目前没有进程级隔离。聊天记录在应用私有 SQLite 中，未做数据库加密。

停止或失败后的助手回复保留并标记，但不进入后续模型上下文；用户输入仍保留。进程被系统杀死时，未结算的流式片段可能丢失，已写入的用户消息和完整回复保留。完整上下文发送给配置的模型服务，较长对话目前需要新建对话。
