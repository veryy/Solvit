# MCP 连接插件

`dev.solvit.mcp` 用 TypeScript 实现，当前发布包1.1.1使用 Solvit 插件宿主 API10，已包含在0.41.0。Kotlin 提供私有配置、加密存储、HTTPS 请求与取消；MCP 协商、工具发现和调用逻辑位于 [插件源码](../builtin-plugins/mcp/index.ts)。

## 配置与使用

支持最多8个具名 Streamable HTTP HTTPS 连接。授权network并启用后，从插件详情打开HTML管理页添加、修改、禁用、删除和连接测试；也可导入明确HTTP类型的`mcpServers`配置。配置及令牌使用Android Keystore AES-GCM加密，页面保存检查旧值以拒绝并发覆盖。敏感页面启用FLAG_SECURE，编辑时凭据留空保留原值，清除须单独确认。旧单地址配置作为default连接兼容。

授权 network 后，在聊天中请求“连接 MCP 并列出工具”。模型先调用 `mcp_connect` 或 `mcp_tools`，得到服务器的工具名和参数 schema，再通过 `mcp_call` 调用已发现工具。多个启用连接时必须传server名称，另包括远程工具名与arguments。`mcp_servers`只返回名称、状态和数量。远程同名工具靠server明确路由。页面连接测试使用独立页面实例并结束会话，不声称模型会话已连接。

MCP 配置不进入模型的工具参数和描述，但启用的插件可读取自己的配置；允许的远程工具结果会进入模型上下文。插件没有访问其他插件配置或模型 API Key 的接口。卸载移除配置及凭据，停用尝试关闭远程会话。

## 实际 HTTPS 演示

可填写 `https://solvit.207.57.126.73.sslip.io/mcp-demo`，令牌留空。服务器只提供 echo 与 server_time 两个只读演示工具，不访问文件或执行 Shell。该端点已实际通过插件的 HTTPS 初始化、工具发现和回显调用验证；它是本项目的演示服务器，第三方 MCP 服务仍待验证。

## 当前协议支持

按 [MCP Streamable HTTP 规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) 与 [生命周期规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) 实现：

- initialize → notifications/initialized → tools/list → tools/call。
- 协商 2025-11-25、2025-06-18 或 2025-03-26，携带会话 ID 与协商后的协议版本头。
- JSON 或有限 SSE POST 响应，忽略进度通知，回应 ping，拒绝未声明的客户端能力请求。
- 工具列表最多 8 页、200 个工具，拒绝重复工具和重复 cursor；结果分页提供给模型。
- 未发现的远程工具不执行；响应 ID 和 JSON-RPC 格式需匹配，错误诊断不输出远程消息中的凭据。
- HTTP 404 会清除过期会话，下次调用重新初始化；有副作用的工具调用不会自动重试。
- 停止工具请求时中断本地 HTTP，并在权限仍有效时最多用 1 秒尝试向同一端点发送 notifications/cancelled。远程已发生的操作能否撤销由服务器决定。
- 停用/断开时尝试 DELETE 会话；服务器不支持 DELETE 可返回 405。网络和引擎执行都有期限。

## 仍待完善

当前通过 `mcp_tools`/`mcp_call` 桥接远程工具，还未把每个远程工具动态注册成独立模型函数。每个远程工具的独立角色绑定、OAuth 登录、MCP resources/prompts、持续 GET 事件流、SSE 断线恢复，以及老式 HTTP+SSE 和 stdio/Linux 进程传输尚未实现。当前不声称完整 MCP 协议兼容。

Ubuntu环境已经可以在Shell运行Node/Python；这不代表MCP插件已能启动stdio服务。本地进程接入在独立分支开发，原生管道和协议探针的[阶段记录](local-stdio-mcp-stage.md)不等同于已发布功能。

## 验证

TS 测试覆盖会话协商、工具分页、JSON/SSE、未知工具、过期会话、不重试、错误 ID、版本拒绝与诊断脱敏。Android 宿主测试使用实际 HTTPS 测试服务器和真实 QuickJS，检查加密配置、请求头、工具结果、取消通知、权限撤销与会话清理。它使用测试证书和注入的测试密钥；生产环境仍使用平台 TLS 与 Android Keystore。

真机、第三方真实 MCP 服务、OAuth 和真实模型的 MCP 工具选择尚未验证。

## 早期多连接候选验证记录（0.22.0阶段）

以下按时间保留当时的候选状态，不代表当前仍未发布。0.41.0正式版本已再次通过双HTTPS/插件页面回归及在线MCP初始化、工具发现、回显调用与断开；见[正式发布证据](release-0.41.0.md)。这些结果仍不能替代真机、第三方服务或真实模型验收。

远程TypeScript生成运行[38285153870](https://github.com/veryy/Solvit/actions/runs/38285153870)已通过。新增回归覆盖同名工具的逐服务器路由、一方会话过期不影响另一方、禁用清理、配置导入和并发保护。新Android回归用两个实际HTTPS测试服务器加确定性Provider验证工具回传，并用真实WebView和Keystore验证多连接保存重开、禁用及删除确认；这些Android回归尚待通过，确定性Provider也不等于真实模型效果验收。

干净候选包已由远程生成38294468996取回并提交，37,381字节、SHA256 `111d17a33de9272b9b645e54ddc599aaed909e45960231b3254ba4dcb05f0f2c`。完整候选38295011493运行中，市场未部署、0.22.0未发布。

干净候选38295011493的实际285个单元及44个模拟器测试全部通过，包含双HTTPS同名工具正确路由、两连接加密保存重开、禁用和取消/确认删除。72 SDK/2市场及提交包一致性通过。0.22.0正式发布仍待完成。
