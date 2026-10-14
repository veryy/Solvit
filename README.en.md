# Solvit

AI chat, plugins, and a project workspace on your Android phone. Bring your own model, chat, search the web, and browse, edit, run, and review code in one directory.

把 AI 聊天、插件和项目工作区放进手机里，接上自己的模型就能开始用。

[中文](README.md) · **English** · [Download APK](https://github.com/veryy/Solvit/releases/latest/download/solvit-debug.apk) · [Release notes](https://github.com/veryy/Solvit/releases/latest)

## Get started

Install the APK, then open Settings → Model configuration and enter your service URL, API key, and model name. Fetch the model list or enter a name yourself. OpenAI-compatible and native Claude interfaces are supported; you need your own model service and quota.

Requires Android 8.0+. The app defaults to Chinese and also supports English, Japanese, Korean, Spanish, and French. Choose a light, dark, or system theme, with dynamic colors on Android 12+.

The latest public release is [0.30.0](https://github.com/veryy/Solvit/releases/tag/v0.30.0). Updates using the same signing certificate retain data. Uninstalling deletes local data, so export your configuration before reinstalling.

## What you can use today

- **Chat:** streamed text, collapsible reasoning, Markdown, conversation history, stop, queue, and interrupt. Tool details and permission requests appear inside the conversation.
- **Attachments:** pick images, UTF-8 text, or code files; preview or remove them before sending, and reopen saved attachments with messages. Attachment-only sends, queueing, and interruption are supported. Vision requires a capable model. Up to 8 attachments and 20MiB per message; text files up to 256KiB each. PDF and other documents are not supported yet.
- **Personalization:** roles inherited by new conversations, plugin and Skills bindings, font size, text display speed, chat style, language, and theme. Multiple model connections, model discovery, error details, and encrypted configuration backup.
- **Plugins:** install, enable, and disable JS/TS plugins. Plugins can ship offline HTML/JS pages; Android manages permissions, platform services, and resource cleanup. [Plugin API](docs/plugins.md)
- **Search and memory:** default public web search with source links, local memory retrieval and relationship graphs, editing, deletion, and optional background saving. Public search can encounter network restrictions or challenges; real-model memory quality still needs validation.
- **MCP and Skills:** multiple named HTTPS MCP connections with independent sessions and encrypted credentials; import SKILL.md and text resources. [MCP](docs/mcp.md) · [Skills](docs/skills.md)
- **Workspace:** one directory per conversation and multiple Shell sessions. File tree, directory creation, rename and delete, CodeMirror text/code preview and editing, and an xterm.js / Android PTY terminal share the same directory.
- **Git workspace:** Changes, diffs, staging, commits, and history, plus HTTPS clone, fetch, pull/push reviews, merge conflicts, progress, and cancellation. Credentials are entered through a native secure form and stored encrypted. AI Git tools are currently read-only.
- **Updates:** release notes, APK downloads with progress, and an installation entry inside the app. API keys are encrypted with Android Keystore.

## What comes next

Image, text, and code attachments are now available. Next priorities:

1. Retry failed sends, carry attachments when editing older messages, and clean up unsent files.
2. Read PDFs and other documents; connect workspace imports with chat and file previews.
3. Recover interrupted Git clone installation and verify plugin operations with a real GitHub account.
4. Validate roles, visible streaming, tool selection, and complete tasks with real models; improve plugins and memory.

**Screen control is deferred.** Larger-file editing, AI Git writes, and autonomous project tasks remain on the [roadmap](docs/product-roadmap.md).

## Current limits

Text editing is limited to 256KiB UTF-8 and checks for external changes before saving. MCP currently supports HTTPS Streamable HTTP; OAuth, stdio, and full resource/prompt support are unfinished. JS plugins do not have process isolation.

Configuration backups include model connections, API keys, roles, prompts, language, and appearance, but exclude chats, plugin packages, plugin credentials, and memories. Keep backups outside the app. Conversation context is sent to your configured model service.

The published APK is a debug build. [Release validation](https://github.com/veryy/Solvit/actions/runs/38514560022) passed remote build, tests, lint, signing, and upgrade gates; full real-model tasks and physical-device experience have not been verified. [Release evidence](docs/release-0.30.0.md)

## Development

Kotlin, Jetpack Compose, Material 3, and a QuickJS plugin host. Builds and tests run through GitHub Actions. See [build instructions](README.md#构建), [architecture](docs/architecture.md), and the [TS SDK](plugin-sdk/README.md).
