# Solvit

AI chat, plugins, and a project workspace on your Android phone. Bring your own model, chat, search the web, and browse, edit, run, and review code in one directory.

把 AI 聊天、插件和项目工作区放进手机里，接上自己的模型就能开始用。

[中文](README.md) · **English** · [Download APK](https://github.com/veryy/Solvit/releases/latest/download/solvit-debug.apk) · [Release notes](https://github.com/veryy/Solvit/releases/latest)

## Get started

Install the APK, then open Settings → Model configuration and enter your service URL, API key, and model name. Fetch the model list or enter a name yourself. OpenAI-compatible and native Claude interfaces are supported; you need your own model service and quota.

Requires Android 8.0+. The app defaults to Chinese and also supports English, Japanese, Korean, Spanish, and French. Choose a light, dark, or system theme, with dynamic colors on Android 12+.

The current release is [0.43.6](https://github.com/veryy/Solvit/releases/tag/v0.43.6). Updates using the same signing certificate retain data. Uninstalling deletes local data, so export your configuration before reinstalling.

## What you can use today

- **Chat:** streamed text, collapsible reasoning, Markdown, conversation history, stop, queue, and interrupt. Tool details and permission requests appear inside the conversation. Retry the latest failed or stopped reply in the original bubble with its attachments preserved; earlier attempts stay collapsed.
- **Attachments:** pick images, UTF-8 text, or code files; preview or remove them before sending, and reopen saved attachments with messages. Editing an older question carries independent attachment copies into the new branch; removing a copy preserves the original. Attachment-only sends, queueing, and interruption are supported. Vision requires a capable model. Up to 8 attachments and 20MiB per message; text files up to 256KiB each. PDFs support text extraction, offline Chinese/Japanese/Korean/Latin OCR, selection of up to 40 pages, and original-page previews, with extracted text capped at 256KiB. [PDF guide](docs/release-0.33.1.md)
- **Personalization:** roles inherited by new conversations, plugin and Skills bindings, font size, text display speed, chat style, language, and theme. Multiple model connections, model discovery, error details, and encrypted configuration backup.
- **Plugins:** install, enable, and disable JS/TS plugins. Plugins can ship offline HTML/JS pages; Android manages permissions, platform services, and resource cleanup. [Plugin API](docs/plugins.md)
- **Search and memory:** default public web search with source links, local memory retrieval and relationship graphs, editing, deletion, and optional background saving. Public search can encounter network restrictions or challenges; real-model memory quality still needs validation.
- **MCP and Skills:** named HTTPS or Ubuntu stdio MCP connections; AI can call Python/Node servers, with chat isolation and encrypted credentials/environment; import SKILL.md and text resources. Skills1.2.0 can prepare bundled Python/Node scripts in the workspace for separately authorized Ubuntu Shell execution. Existing users must explicitly update and review the new workspace permission. [MCP](docs/mcp.md) · [Skills](docs/skills.md)
- **Workspace:** one directory per conversation and multiple Shell sessions. File tree, directory creation, rename and delete, CodeMirror text/code preview and editing, and an xterm.js / Android PTY terminal share the same directory. Import complete original files up to 20MiB from the system picker or saved attachments in the current chat after reviewing provenance, metadata, and text/image/PDF page previews; existing files can be reviewed and replaced with a version check and a retained original backup. Import checks available storage before mutation; known low-space failures preserve the original file and selected attachment for retry after freeing space. Import shows actual write progress and supports cancellation, cleanup, and retry. Interrupted replacements recover after process death; plugin pages show the result and retain backups or concurrent changes. Git and Shell see the same imported file.
- **Import backups:** view provenance, current logical sizes, and status; review a copy for restoration or confirm cleanup. Interrupted cleanup supports explicit recovery without replacing concurrent files; an unconfirmed outcome never retries deletion automatically. [Guide (中文)](docs/workspaces.md#导入备份管理0400)
- **Linux environment:** install Ubuntu 24.04 without root, with Python/venv and Node24/npm; add Python/pip or Java17. Open Workspace → Linux, install it, then create an Ubuntu Shell. AI commands, the terminal, and file management share the same directory. [Getting started (中文)](docs/linux-environment.md) · [Verification](docs/linux-environment-stage.md)
- **Git workspace:** Changes, diffs, staging, commits, and history, plus HTTPS clone, fetch, pull/push reviews, merge conflicts, progress, and cancellation. Credentials are entered through a native secure form and stored encrypted. Review interrupted clones before resuming or downloading again; conflicts preserve files, and discarding removes private staging only. AI Git tools are currently read-only.
- **Updates:** release notes, APK downloads with progress, and an installation entry inside the app. API keys are encrypted with Android Keystore.

## Interface polish in 0.43.6

Settings now use grouped navigation and a separate appearance page. Plugin details prioritize state and pages; failed pages can reopen in place. Tool approval stays in chat, with collapsible arguments and permission scope. Queued messages start collapsed, and workspace submissions show progress and retain failed drafts. Memory graphs improve long-label spacing; updates preserve existing data, and an older catalog cannot overwrite a newer installed plugin.

## What comes next

Image, text, and code attachments are now available. Next priorities:

1. Refine workspace path navigation, file rows, and grouped New / Import / More actions.
2. Verify plugin operations with a real GitHub account and complete project tasks. Interrupted clone recovery, import capacity checks, and backup management are available.
3. Validate ARM64 physical devices, older kernels, third-party MCP servers, and Skills dependencies.
4. Validate roles, visible streaming, tool selection, and complete tasks with real models; improve plugins and memory.

**Screen control is deferred.** Larger-file editing, AI Git writes, and autonomous project tasks remain on the [roadmap](docs/product-roadmap.md).

## Current limits

Text editing is limited to 256KiB UTF-8 and checks for external changes before saving. MCP supports HTTPS Streamable HTTP and local stdio tool protocols; OAuth and full resource/prompt support are unfinished. JS plugins do not have process isolation.

The Linux environment shares the Android kernel and app identity and grants no extra system permissions. ARM64/x86_64 binaries are included; Node24 officially targets kernel 4.18+. Older phones and ARM64 physical devices still need validation. QuickJS runs plugin logic; Ubuntu Node runs as a separate process. Local MCP requires an explicit plugin update, new permission review, and a bound workspace.

Unsent drafts and queued messages do not survive process death; startup cleans up their orphan attachment files. Saved attachments remain available. [Attachment release and verification](docs/release-0.32.0.md)

Configuration backups include model connections, API keys, roles, prompts, language, and appearance, but exclude chats, plugin packages, plugin credentials, and memories. Keep backups outside the app. Conversation context is sent to your configured model service.

The published APK is a debug build. [Formal 0.43.6 validation](https://github.com/veryy/Solvit/actions/runs/38989335525) passed 617 unit tests, lint, stable signing, 110 regular device cases, the full chat scenario at 1.5× text size, 15 process-death recovery cases, upgrade persistence, actual stdio host termination, and Ubuntu checks. Raw reports and actual screenshots were reviewed; public APK/source downloads match the build artifacts exactly. The WebView lifecycle change passed repeated checks in the specified emulator environment; real-model tasks and ARM64 physical devices remain unverified. [Release evidence](docs/release-0.43.6.md) · [Native lifecycle investigation](docs/webview-user-lifecycle-stage.md).

## Development

Kotlin, Jetpack Compose, Material 3, and a QuickJS plugin host. Builds and tests run through GitHub Actions. See [build instructions](README.md#构建), [architecture](docs/architecture.md), and the [TS SDK](plugin-sdk/README.md).
