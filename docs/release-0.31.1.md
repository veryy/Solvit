# Solvit 0.31.1：手机上的 Linux 开发环境

这版给工作区加上无需 root 的 Ubuntu 24.04。它按需下载，不把整个发行版塞进 APK。

- 环境管理是独立的 JS/HTML 插件：查看下载进度和安装日志、停止安装、失败后重试。
- 基础环境包含 Python/venv、Node.js 24 LTS/npm；可以在插件里安装 Python/pip 或 Java 17。
- 工作区 → Linux 中安装环境，完成后点「新建 Ubuntu Shell」。AI 命令、交互终端和文件管理共用这个工作目录。
- Android Shell 继续保留。环境选择按 Shell 保存，不会把原有会话悄悄换到 Ubuntu。
- 下载来源和 SHA256 由宿主固定；插件不能指定宿主下载地址或进程参数。AI 执行仍遵守现有对话内授权、角色、工作区绑定、超时和取消。
- 安装失败或取消保留旧环境，未完成的首次安装不会显示已安装。正常覆盖更新继续使用固定签名并保留应用数据。

使用方法：在聊天中创建/绑定工作区，打开「工作区 → Linux」，下载 Ubuntu；安装结束后创建 Ubuntu Shell，再打开终端，或让 AI 在这个 Shell 执行命令。首次下载安装需要联网及至少 768 MiB 可用空间，安装更多开发包需要额外空间。

这提供 Linux 用户空间，共用 Android 内核和应用身份，不额外授予 Android 权限。ARM64 与 x86_64 提供原生程序；其他架构仍可使用普通聊天及 Android Shell。它不是完整虚拟机，不承诺 Docker、内核模块或系统服务可用。Node24 官方 Linux 支持基线为内核4.18以上；Android8可安装应用不等于所有旧手机都能运行这套开发环境。

发布门禁包含单元、Lint、API35 Android 环境安装/实际执行、JS 插件检查、完整应用界面、固定签名和覆盖升级。设备测试覆盖生产 HTTPS 下载、DNS、Python/Node/npm、Java17、交互 PTY、授权后的 AI 工具执行路径和工作区运行环境恢复。原生程序来自锁定源码，随版本提供 `solvit-linux-sources.zip`（源码、补丁、编译输入及许可）。

尚未验收：ARM64 真机、OEM 后台限制、16 KiB 页设备执行，以及真实模型自行选择 Ubuntu Shell 并完成完整开发任务。Node 原生扩展和大型工程也需要单独验证；本次不把运行几条脚本当作所有工具兼容。QuickJS 插件不会自动变成 Node.js；本地 stdio MCP 进程接入是后续工作。

## 实际发布验证

源码 [60114d2](https://github.com/veryy/Solvit/commit/60114d2219a016d07b7a42182210c03fd95dc226)，[正式 Actions38555594514](https://github.com/veryy/Solvit/actions/runs/38555594514) 的 build、verify-ui、verify-plugins、verify-search、verify-linux、release 六项全部成功。实际下载报告并核对完整ZIP CRC/XML：447个单元、59个应用/Web/PTY设备测试零失败/错误/跳过；113个SDK、5个市场测试零失败/跳过。另Linux设备门禁实际执行生产HTTPS安装、Python3.12.3/Node24.21.0/npm11.19.0/Java17、工作区及交互PTY。稳定签名和旧版覆盖升级通过。

正式页面实际截图核对说明、安装按钮和刷新完整可见；真实触摸安装确认及拒绝不写入通过。环境包1.0.2线上下载哈希 e0e2c433cde60f34147178c50e9c51748a6ff445c067dcd1753e861621e43783，旧1.0.0/1.0.1与旧宿主目录保留。

公开APK与Actions artifact11889917692下载后逐字节一致，25,285,128字节，SHA256 `2e4650f07ca547d5bc4829861c4c624b207b2326d646353715ee70943f79950a`。证书SHA256 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。APK内ARM64/x86_64的PRoot/loader所有PT_LOAD为16KiB对齐静态检查通过，不冒充16KiB设备运行验证。

公开源码附件与正式artifact逐字节一致，1,072,439字节，SHA256 `9ecd313ce0525ac1808a38e33a6a43e49a006ecff5324c19d069b599b13f3121`，完整ZIP CRC核对，含对应源码、补丁、编译输入和许可。

[下载APK](https://github.com/veryy/Solvit/releases/download/v0.31.1/solvit-debug.apk) · [对应源码](https://github.com/veryy/Solvit/releases/download/v0.31.1/solvit-linux-sources.zip)。原0.31.0标签因实际截图发现页面空白主动取消，未公开发布；不移动该历史标签。
