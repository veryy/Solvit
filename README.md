# Solvit

把 AI 聊天、插件和项目工作区放进手机里。接上你自己的模型，聊一聊、查资料，也可以在同一个目录里看文件、改代码、开终端和查看 Git 修改。

AI chat, plugins, and a project workspace on your Android phone. Connect your own model to chat, search the web, browse and edit files, use a terminal, and review Git changes.

**中文** · [English](README.en.md) · [下载 APK](https://github.com/veryy/Solvit/releases/latest/download/solvit-debug.apk) · [版本说明](https://github.com/veryy/Solvit/releases/latest)

## 先用起来

下载 APK 后，在「设置 → 模型配置」填写服务地址、API Key 和模型名。可以点「获取模型」拉取列表，也可以自己填名字。支持 OpenAI 兼容接口和 Claude 原生接口；需要你自己的模型服务和额度。

Android 8.0 及以上可用。界面默认中文，也支持英文、日文、韩文、西班牙文和法文；有浅色、深色和跟随系统主题，Android 12 及以上支持动态配色。

当前公开版是 [0.29.1](https://github.com/veryy/Solvit/releases/tag/v0.29.1)。同签名覆盖更新会保留数据；卸载会删除本地数据，重装前请在设置里导出配置备份。

## 现在能做什么

- **聊天**：流式文字、折叠思考、Markdown、多会话历史、停止生成、等待队列和打断。工具调用放在对话里，点开看详情，授权也在对话里处理。
- **角色和设置**：新对话继承当前角色，可绑定插件和 Skills；字体大小、文字显示速度、聊天样式、语言和主题都能调整。支持多组模型连接、模型列表获取、错误详情和加密配置备份。
- **插件**：安装、启用、停用 JS/TS 插件。插件可以带自己的离线 HTML/JS 页面；权限、系统能力和资源清理由 Android 宿主管理。[开发说明](docs/plugins.md)
- **联网搜索**：启用搜索插件即可使用默认公开搜索，也可以选配其他引擎。搜索和网页正文带来源链接；公网搜索仍可能受网络和人机验证影响。[使用说明](docs/web-search.md)
- **记忆**：本地记录、检索、关系图谱、编辑和删除；可以开启自动检索和后台保存。真实模型的记忆质量仍需验证。[记忆说明](docs/memory.md)
- **MCP 和 Skills**：多个具名 HTTPS MCP 连接、独立会话和加密凭据；导入 SKILL.md 及文本资源，让角色按需使用。[MCP](docs/mcp.md) · [Skills](docs/skills.md)
- **工作区**：一个对话可绑定一个目录，多个 Shell 共用该目录。文件树、新建目录、重命名、删除、CodeMirror 文本/代码预览编辑，以及 xterm.js + Android PTY 交互终端。[工作区说明](docs/workspaces.md)
- **本地 Git**：Changes、工作树和暂存区差异、暂存、提交、历史，与文件管理和终端使用同一目录。AI 已有授权后的只读 Git 工具。[Git 阶段记录](docs/workspace-git-stage.md)
- **更新**：应用内查看版本说明、下载进度和安装入口。配置中的 API Key 使用 Android Keystore 加密保存。

## 接下来先补齐日常功能

当前还不能在聊天里上传图片和文件。下一阶段优先补齐这些基础体验，再继续扩展 Agent：

1. 图片选择、聊天内缩略图和大图预览；把图片真正发送给支持视觉的模型。
2. 文件选择、附件卡片、文本/代码内容读取及文档处理；支持发送前移除，显示不支持的格式和大小限制。
3. 附件随消息保存，重开聊天仍可查看；发送失败、重试和排队时保留附件。
4. 工作区文件导入与预览联动；完成 GitHub 克隆、拉取、推送、冲突处理和加密认证的整体验收。
5. 用实际聊天和项目任务验证角色、流式、工具选择和结果，继续改善插件管理与记忆体验。

**屏幕操控暂缓。** 图片/PDF 预览、超限文件编辑、AI Git 写操作和真实模型自主项目任务尚未交付，不能把开发分支或测试通过当成公开版已支持。具体状态见[产品进度](docs/product-roadmap.md)。

## 几个使用边界

文件编辑目前最多 256KiB UTF-8，保存时检查外部修改。MCP 当前支持 HTTPS Streamable HTTP；OAuth、stdio 和完整资源/提示词能力尚未完成。JS 插件没有进程级隔离。

模型能否看图取决于所选模型及接口能力，图片上传会在功能实现后明确显示这一条件。聊天和相关上下文会发送到你配置的模型服务；API Key 不提供给业务插件。

配置备份包含模型连接、API Key、角色、提示词、语言和外观等；不包含聊天、插件包、插件密钥和记忆。备份文件请保存在应用外。

当前发布的是 debug APK。[0.29.1 远程验证](https://github.com/veryy/Solvit/actions/runs/38488640678)通过了测试、Lint、APK、签名与覆盖升级等门禁；真实模型完整任务与真机体验尚未验收。构建证据和 APK 摘要见[版本记录](docs/release-0.29.1.md)。

## 构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0。Android Studio 可自动安装 SDK；命令行设置 `ANDROID_HOME` 或在不提交的 `local.properties` 中填写 `sdk.dir`。

```sh
./gradlew :core:test :system-runtime:test :workspace-android:testDebugUnitTest :provider-http:test :provider-openai:test :provider-anthropic:test :chat-runtime:test :release-updates:test :plugin-runtime:testDebugUnitTest :storage-sqlite:testDebugUnitTest :plugin-market:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :terminal-android:assembleDebugAndroidTest
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
| `system-runtime` | Shell 作业、流式输出与取消引擎 |
| `workspace-android` | 持久工作区绑定、文件操作、多 Shell 会话与原生进程执行 |
| `release-updates` | GitHub Release 更新检查 |
| `plugin-runtime` | QuickJS 引擎、JS 工具适配、依赖、权限与执行限制 |
| `plugin-market` | HTTPS 目录、包校验、安装启停、独立存储和权限配置 |
| `app` | 启动组合、加密设置、ViewModel、原生 UI |

详细设计、参考源码、生命周期规则、扩展方式见 [架构说明](docs/architecture.md)。测试中的 Greeting 插件与 MemoryStore 演示服务扩展与存储替换。

后续完整产品目标与验证状态见 [产品进度](docs/product-roadmap.md)。更新检查是独立的 `release-updates` 内置插件。


更多设计和开发资料：[架构](docs/architecture.md) · [TS SDK](plugin-sdk/README.md) · [参考源码](docs/upstream-reference.md) · [完整路线](docs/product-roadmap.md)。
