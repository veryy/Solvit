# Solvit 0.5.0

- 内置纯 TS MCP 插件：HTTPS Streamable HTTP 初始化、版本协商、工具分页发现、JSON/SSE 响应、远程工具调用和会话关闭。
- 新增声明式插件配置页。插件配置及 Bearer 令牌使用 Android Keystore AES-GCM 加密，只提供给对应插件；配置不进入模型工具描述或参数。
- API v2 增加私有配置和 HTTPS POST/DELETE；旧 App 市场仍只返回 API v1 插件。
- 停止工具调用时取消本地宿主任务，并最多用 1 秒尝试发送 MCP 取消通知；修复 JS 求值结束后异步宿主工作仍继续的问题。
- 标签发布检查包括 Android 测试、Lint、APK、TS 与市场测试，以及实际公网 HTTPS MCP 调用。

使用：插件 → 已安装 → MCP 连接 → 配置插件，填入 HTTPS 端点和可选 Bearer 令牌，再启用 network 权限。聊天中先发现 MCP 工具，再调用；每次执行都需确认。

只读演示地址：`https://solvit.207.57.126.73.sslip.io/mcp-demo`，令牌留空，提供 echo 和 server_time。演示服务已通过实际 HTTPS 初始化、发现与调用验证。

当前通过 mcp_tools/mcp_call 桥接远程工具，尚未逐个动态注册远程函数。多服务器、角色绑定、OAuth、stdio、老式 HTTP+SSE、持续事件监听、断线恢复和完整资源/提示词能力仍待实现。真机、第三方真实 MCP 服务与真实模型行为尚未验证；Skills、Root/Shell、附件等完整产品目标继续推进。

固定签名沿用 0.1.1 起的版本；早期 0.1.0 临时签名无法覆盖安装，请保留旧版重要数据。
