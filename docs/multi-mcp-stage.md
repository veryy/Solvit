# 多MCP服务器与插件配置页

用户明确要求多个MCP，且插件业务页面用HTML/JS。此前单module-level session、单URL配置不能满足。保留聊天/工作区等整个路线，当前不能把界面和mock通过当成真实模型的工具选择验收。

已读实际锁定Operit `data/mcp/McpConfigImportParser.kt`及`McpConfigImportParserTest.kt`：具名mcpServers、分离传输、headers/disabled、拒绝缺少类型和混合stdio+remote。已读锁定DSH `mcp-client/src/connection.ts`、`server-context.ts`及`tests/server-context.spec.ts`、`reconnect.spec.ts`：每个server自己的连接代际、作用域、工具清理、有限重连预算。官方[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)和[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)重新核对会话ID/版本头、404失效和DELETE清理。

采用具名连接、独立配置/会话/错误、同名工具按server明确路由、默认启停、标准mcpServers导入和插件HTML配置页。凭据存入所属插件现有Keystore AES-GCM，不进入普通storage或模型的目录描述。API10页面可原子更新已声明配置字段；普通模型工具/Hook不能修改配置，关闭和撤权后不能写入。pageManaged字段由HTML页面编辑，宿主不再另画同一配置表单。现有单URL/token可迁移，旧包保留。

首次检查点先实现宿主页面配置契约并添加真实QuickJS/加密/并发/权限回归；后续候选实现及验证记录如下。只支持已实现的HTTPS Streamable HTTP；stdio、旧HTTP+SSE、OAuth、resources/prompts及完整DSH动态工具注册/重连机制不能声称完成。会话过期可以在下一次明确调用重新初始化，有副作用的工具调用不能自动重试。

多连接候选已写入：MCP1.1.0/API10，具名Map会话及逐连接失效/清理，模型先mcp_servers再带server发现和调用；同名echo按server路由，缺少server的多连接调用拒绝，不猜第一个。配置页由插件HTML/JS管理添加、编辑、测试、启停、删除和标准mcpServers导入，令牌/请求头不回显旧值，空输入保留已有认证，可明确确认清除。导入stdio/旧SSE明确拒绝，不把JSON导入当成Android进程支持。

页面测试连接在finally立即关闭探测会话，界面文案是“连接测试成功”，不把页面独立引擎的探测冒称为模型实例常驻连接。模型工具实例独立维护各会话，配置改变后下一次MCP操作撤销不再匹配的会话；副作用调用不自动重试。凭据在所属插件Keystore加密字段，HTML敏感页面使用FLAG_SECURE；普通工具不可修改配置。

新增SDK多服务器同名工具/单会话过期/禁用/导入冲突回归，以及真实双TLS MockWebServer→Android QuickJS→ToolAgentRuntime→后续请求的回归。Provider在该回归中是确定性fixture，只验证实际执行及回传路由，不称为真实模型选择或第三方MCP验收。候选包尚未远程生成，以上新回归尚未执行。

候选2ea2a48运行[38286061164](https://github.com/veryy/Solvit/actions/runs/38286061164)的build已通过：实际下载XML核对282项单元测试，无失败、错误或跳过。双HTTPS同名工具/后续模型请求、原单服务器取消与撤权、加密CAS配置回归均通过；Lint、APK和固定证书门禁通过。模拟器仍在运行，尚未发布。新增a1bb906只升级SHA256精确匹配的旧内置MCP，并保留已有加密配置、授权和启用状态；修改包不覆盖、卸载不恢复的回归待下一运行。

9821a6c运行38288170527已实际核对284个单元回归全部通过，包含原MCP精确包升级、加密旧连接保留、用户修改包保护和卸载不恢复。旧记忆页升级及事实/授权保留回归随后加入，尚待执行。0.22.0仍未发布；完整UI仅图谱点击失败的原因见HTML阶段，不能把当前候选或确定性Provider算成真实模型质量验收。
