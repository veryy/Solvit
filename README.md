# Solvit

把 AI 聊天、插件和项目工作区放进手机里。接上你自己的模型，聊一聊、查资料，也可以在同一个目录里看文件、改代码、开终端和查看 Git 修改。

AI chat, plugins, and a project workspace on your Android phone. Connect your own model to chat, search the web, browse and edit files, use a terminal, and review Git changes.

**中文** · [English](README.en.md) · [下载 APK](https://github.com/veryy/Solvit/releases/latest/download/solvit-debug.apk) · [版本说明](https://github.com/veryy/Solvit/releases/latest)

## 先用起来

下载 APK 后，在「设置 → 模型配置」填写服务地址、API Key 和模型名。可以点「获取模型」拉取列表，也可以自己填名字。支持 OpenAI 兼容接口和 Claude 原生接口；需要你自己的模型服务和额度。

Android 8.0 及以上可用。界面默认中文，也支持英文、日文、韩文、西班牙文和法文；有浅色、深色和跟随系统主题，Android 12 及以上支持动态配色。

当前稳定版是 [0.41.0](https://github.com/veryy/Solvit/releases/tag/v0.41.0)。0.42.1 的原始 UI 报告有一项失败，已标为预发布；门禁已修正，0.42.2因附件用例超时未发布，正在验证0.42.3。同签名覆盖更新会保留数据；卸载会删除本地数据，重装前请在设置里导出配置备份。

## 现在能做什么

- **聊天**：流式文字、折叠思考、Markdown、多会话历史、停止生成、等待队列和打断；最后一条失败或停止的回复可在原气泡里重试，保留原附件与此前尝试。工具调用放在对话里，点开看详情，授权也在对话里处理。
- **图片和文件**：聊天里选择图片、UTF-8 文本或代码文件，发送前可预览和移除，发送后随消息保存；编辑旧问题时附件一起回到新分支，移除副本保留原文件。支持只发附件、排队和打断；看图需要模型支持视觉。每条最多 8 个附件、合计 20MiB，文本单个最多 256KiB；PDF 支持文字提取、本机中日韩/拉丁字母 OCR、选择最多40页及原页预览；读取文字最多256KiB。[PDF 用法](docs/release-0.33.1.md)
- **角色和设置**：新对话继承当前角色，可绑定插件和 Skills；字体大小、文字显示速度、聊天样式、语言和主题都能调整。支持多组模型连接、模型列表获取、错误详情和加密配置备份。
- **插件**：安装、启用、停用 JS/TS 插件。插件可以带自己的离线 HTML/JS 页面；权限、系统能力和资源清理由 Android 宿主管理。[开发说明](docs/plugins.md)
- **联网搜索**：启用搜索插件即可使用默认公开搜索，也可以选配其他引擎。搜索和网页正文带来源链接；公网搜索仍可能受网络和人机验证影响。[使用说明](docs/web-search.md)
- **记忆**：本地记录、检索、关系图谱、编辑和删除；可以开启自动检索和后台保存。真实模型的记忆质量仍需验证。[记忆说明](docs/memory.md)
- **MCP 和 Skills**：多个具名 HTTPS 或 Ubuntu 本地 stdio MCP 连接，Python/Node 服务可供 AI 调用；连接按聊天隔离，认证与环境加密保存。导入 SKILL.md 及文本资源，让角色按需使用。[MCP](docs/mcp.md) · [Skills](docs/skills.md)
- **工作区**：一个对话可绑定一个目录，多个 Shell 共用该目录。文件树、新建目录、重命名、删除、CodeMirror 文本/代码预览编辑，以及 xterm.js + Android PTY 交互终端。可以从系统选择器或当前对话已保存的附件导入最多20MiB的完整原文件，先看来源、信息、文本、图片或 PDF 原页预览再确认；同名文件先审阅大小与摘要，确认替换时检查文件版本并保留旧文件备份。导入前检查容量，已知空间不足时保留原文件和附件选择，释放空间后可重试；导入时显示实际写入进度，可以取消、等待清理后重试；应用被系统结束后可恢复未完成替换，插件页显示结果，保留备份和并发文件；文件树、Git和Shell共用。[工作区说明](docs/workspaces.md)
- **导入备份**：查看来源、当前占用与状态；审阅后复制恢复或确认清理。清理中断时手动退回，同名文件保留双方，结果未确认时不自动重试删除。[使用说明](docs/workspaces.md#导入备份管理0400)
- **Linux 开发环境**：无需 root，按需安装 Ubuntu 24.04、Python/venv、Node24/npm，可追加 Python/pip 或 Java17。在「工作区 → Linux」安装后新建 Ubuntu Shell，AI 命令、终端和文件管理使用同一目录。[上手指南](docs/linux-environment.md) · [验证记录](docs/linux-environment-stage.md)
- **Git 工作台**：Changes、差异、暂存、提交和历史，与文件管理和终端使用同一目录。支持 HTTPS 克隆、fetch、拉取/推送审阅、合并冲突、进度和取消；凭据由原生安全表单加密保存。克隆被系统结束后，可审阅记录再继续或重新下载；遇到冲突保留文件，放弃只清私有暂存。AI Git 工具目前只读。[Git 阶段记录](docs/workspace-github-stage.md)
- **更新**：应用内查看版本说明、下载进度和安装入口。配置中的 API Key 使用 Android Keystore 加密保存。

## 接下来先补齐日常功能

图片、文本和代码附件已经能用，接下来继续补齐：

1. 真实 GitHub 账号读写和完整项目任务验收；Git 克隆中断恢复、导入容量、备份管理已交付。
2. ARM64 真机与旧内核兼容、第三方 MCP 服务和 Skills 运行环境。
3. 用真实模型验证角色、流式、工具选择和完整项目任务，继续改善插件管理与记忆体验。

**屏幕操控暂缓。** 大文件编辑、AI Git 写操作和自主项目任务仍在计划里。具体状态见[产品进度](docs/product-roadmap.md)。

## 几个使用边界

文件编辑目前最多 256KiB UTF-8，保存时检查外部修改。MCP 支持 HTTPS Streamable HTTP 与本地 stdio 工具协议；OAuth 和完整资源/提示词能力尚未完成。JS 插件没有进程级隔离。

Linux 环境仍使用 Android 内核和应用身份，不额外授予系统权限。当前支持 ARM64/x86_64；Node24 官方支持内核4.18以上，旧手机和 ARM64 真机仍需验证。QuickJS 负责插件逻辑，Ubuntu Node 是独立进程。本地 MCP 需安装新版插件、审阅新增权限并绑定工作区；它仍受 Android 权限限制。

未发送输入框和等待队列尚不支持重启恢复，启动时会清理其遗留附件；已发送文件保留。[附件编辑与验证](docs/release-0.32.0.md)

图片会真正发送到模型接口；模型能否看懂取决于所选模型及接口能力。聊天和相关上下文会发送到你配置的模型服务；API Key 不提供给业务插件。

配置备份包含模型连接、API Key、角色、提示词、语言和外观等；不包含聊天、插件包、插件密钥和记忆。备份文件请保存在应用外。

当前发布的是 debug APK。[0.42.1 远程验证](https://github.com/veryy/Solvit/actions/runs/38746331673)的绿色状态有误，600单元、Lint和Linux通过，但原始UI有1项失败；真实模型完整任务与真机体验尚未验收。构建证据和 APK 摘要见[版本记录](docs/release-0.42.1.md)。

## 构建

GitHub Actions 从锁定源码编译 PRoot/loader，再构建 Android 应用；源码、补丁和许可随 Release 提供 `solvit-linux-sources.zip`。需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358。Android Studio 可自动安装 SDK；命令行设置 `ANDROID_HOME` 或在不提交的 `local.properties` 中填写 `sdk.dir`。

```sh
./gradlew :core:test :linux-runtime:test :system-runtime:test :workspace-android:testDebugUnitTest :git-android:testDebugUnitTest :web-android:testDebugUnitTest :provider-http:test :provider-openai:test :provider-anthropic:test :chat-runtime:test :release-updates:test :plugin-runtime:testDebugUnitTest :storage-sqlite:testDebugUnitTest :plugin-market:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :terminal-android:assembleDebugAndroidTest :web-android:assembleDebugAndroidTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 使用相同命令并上传 APK 和测试报告。Gradle Wrapper 固定为 9.3.1，AGP 9.1.1 使用内置 Kotlin，项目 Kotlin 版本 2.4.10，以接入具有超时与取消能力的 QuickJS 插件引擎。

## 架构

| 模块 | 职责 |
| --- | --- |
| `core` | 服务契约、插件管理、类型化事件、工具发现和授权接口，无 Android 依赖 |
| `provider-http` | 通用受限 SSE、HTTP 取消与脱敏诊断 |
| `provider-openai` | OpenAI 兼容 Chat Completions，独立注册 Provider 和模型目录 |
| `provider-anthropic` | Claude 原生 Messages、签名转录、工具回传和模型目录 |
| `storage-sqlite` | ChatStore 的 Android SQLite 实现 |
| `chat-runtime` | 有界工具 Agent、消息结算、上下文和 ChatService 插件 |
| `linux-runtime` / `linux-android` | 可信归档安装、共享 PRoot 启动、按需软件安装和原生环境服务 |
| `system-runtime` | Shell 作业、流式输出与取消引擎 |
| `workspace-android` | 持久工作区绑定、文件操作、多 Shell 会话与原生进程执行 |
| `git-android` | 绑定工作区的 Git、HTTPS 传输、审阅确认与克隆中断恢复 |
| `release-updates` | GitHub Release 更新检查 |
| `plugin-runtime` | QuickJS 引擎、JS 工具适配、依赖、权限与执行限制 |
| `plugin-market` | HTTPS 目录、包校验、安装启停、独立存储和权限配置 |
| `app` | 启动组合、加密设置、ViewModel、原生 UI |

详细设计、参考源码、生命周期规则、扩展方式见 [架构说明](docs/architecture.md)。测试中的 Greeting 插件与 MemoryStore 演示服务扩展与存储替换。

后续完整产品目标与验证状态见 [产品进度](docs/product-roadmap.md)。更新检查是独立的 `release-updates` 内置插件。


更多设计和开发资料：[架构](docs/architecture.md) · [TS SDK](plugin-sdk/README.md) · [参考源码](docs/upstream-reference.md) · [完整路线](docs/product-roadmap.md)。
