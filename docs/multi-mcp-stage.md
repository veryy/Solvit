# 多MCP服务器与插件配置页

用户明确要求多个MCP，且插件业务页面用HTML/JS。此前单module-level session、单URL配置不能满足。保留聊天/工作区等整个路线，当前不能把界面和mock通过当成真实模型的工具选择验收。

已读实际锁定Operit `data/mcp/McpConfigImportParser.kt`及`McpConfigImportParserTest.kt`：具名mcpServers、分离传输、headers/disabled、拒绝缺少类型和混合stdio+remote。已读锁定DSH `mcp-client/src/connection.ts`、`server-context.ts`及`tests/server-context.spec.ts`、`reconnect.spec.ts`：每个server自己的连接代际、作用域、工具清理、有限重连预算。官方[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)和[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)重新核对会话ID/版本头、404失效和DELETE清理。

采用具名连接、独立配置/会话/错误、同名工具按server明确路由、默认启停、标准mcpServers导入和插件HTML配置页。凭据存入所属插件现有Keystore AES-GCM，不进入普通storage或模型的目录描述。API10页面可原子更新已声明配置字段；普通模型工具/Hook不能修改配置，关闭和撤权后不能写入。pageManaged字段由HTML页面编辑，宿主不再另画同一配置表单。现有单URL/token可迁移，旧包保留。

当前先实现宿主页面配置契约并添加真实QuickJS/加密/并发/权限回归，尚未远程运行。多个服务器JS、HTML配置页、真实双HTTPS端点与模型请求回传验收仍待实现。只支持已实现的HTTPS Streamable HTTP；stdio、旧HTTP+SSE、OAuth、resources/prompts及完整DSH动态工具注册/重连机制不能声称完成。会话过期可以在下一次明确调用重新初始化，有副作用的工具调用不能自动重试。
