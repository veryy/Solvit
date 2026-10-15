# Solvit 0.42.0（未发布）

此候选的正式流程因 Samba 源码下载超时而失败，没有发布 APK。修复见 [0.42.1](release-0.42.1.md)；保留本页记录当时的功能范围。

MCP 插件新增本地 stdio 连接。手机上的 Ubuntu 可以运行 Python/Node 服务，AI 在同一个聊天里列出服务器、发现工具并调用；HTTPS 连接继续支持。

先在「工作区 → Linux」安装 Ubuntu、所需软件和服务脚本，再从绑定该工作区的聊天打开 MCP 页面，选择本地 stdio，填写命令、参数数组、环境和相对目录。保存不会启动，测试连接需要确认，只协商协议和发现工具，完成后关闭。[配置示例](mcp.md)

新版 MCP1.2.0/API30 需要明确授予 network、workspace、shell。原有 HTTPS 用户继续使用原来的兼容包和授权，不自动增加权限；需要本地服务时再安装新版。API1–29 的旧市场目录和历史下载包保留。

本地连接按聊天、角色、工作区和服务器配置隔离；撤权、停用、配置或绑定变化会关闭进程。等待中停止会取消连接，服务崩溃不会自动重放工具写入。命令仅取自保存配置，模型不能临时替换启动命令或环境；stderr 不进入模型诊断。

Ubuntu/PRoot 以普通应用 UID 运行，不能绕过 Android 权限，也不是恶意代码沙箱。QuickJS 继续运行插件逻辑，Ubuntu Node 是独立进程环境。当前只实现 MCP 工具协议子集，尚未支持 OAuth、resources/prompts、sampling 或动态注册每个远程工具。[阶段记录](local-stdio-mcp-stage.md)

0.42.0/build79。实际 Ubuntu Python/Node 的 QuickJS/宿主/Agent 链路、页面确认、取消和进程退出已通过独立远程设备检查。完整发布检查和公开下载核对记录见阶段文档；Release 页提供正式 APK 与 Linux 源码。ARM64真机、第三方真实MCP服务、真实模型自主工具选择仍未验收。
