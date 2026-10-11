# Claude 原生协议插件

在“设置 → 模型与连接”添加或编辑配置，选择“Claude / Anthropic”。默认基础地址为 `https://api.anthropic.com/v1`，使用服务提供的密钥和模型 ID。可以先获取模型列表，也可填写独立模型列表地址。切换协议会重置未保存的地址、模型、密钥和协议参数，系统提示词保留；保存后才修改实际连接。

## 请求与扩展

`ModelProvidersPlugin` 提供注册表，`ModelRoutingPlugin` 提供统一模型与目录服务。`AnthropicPlugin` 和 `OpenAiPlugin` 各自注册实现，停用时回收注册和 HTTP 资源。设置参数由 Provider 声明，聊天和 Agent 不导入具体适配器。原生协议代码位于 `provider-anthropic`，通用诊断和受限 SSE 读取位于 `provider-http`。

Claude 请求将系统指令放到顶层，把助手调用映射成 `tool_use`，把连续工具结果放入同一用户消息的 `tool_result`，拒绝和失败结果标记 `is_error`。API Key 使用 `x-api-key`，请求带 `anthropic-version: 2023-06-01`。独立模型列表按来源检查凭据转发，原生目录支持 `after_id` 分页并限制总量。

## 思考与转录

思考模式“模型默认”不发送 `thinking` 参数，其行为由模型决定，不能视为关闭思考。“自适应思考”“手动思考预算”和“明确关闭思考”分别发送原生参数；支持情况因模型而异。手动预算至少 1024，且必须小于最大输出令牌；服务端的进一步限制通过详细错误展示。

展示用的思考文字与原始转录分开。签名、隐去思考数据和内容块顺序保持不变，只有完整回复进入后续上下文。转录最多 2 MiB，工具参数最多 65536 字符，单轮至多 16 个调用。取消会关闭 HTTP 请求；缺少结束事件、流内错误、未完成 JSON 或输出截断均不会标为完成。

签名绑定的上下文使用规范化 JSON 的 SHA-256 检查。工具正在执行时保留原始思考参数，若系统指令、工具或前缀改变则明确失败。新用户轮次允许更换角色、提示词或工具：受影响的已完成历史思考块不再回传，文本和调用/结果仍保留。跨协议使用可通用的文本与工具语义。不同模型或账户间是否接受签名由服务端校验，客户端不会伪造、重签或强制假定可互换。

## 资料与验证边界

协议依据官方 [Messages 请求文档](https://platform.claude.com/docs/en/api/messages/create)、[流式事件文档](https://platform.claude.com/docs/en/build-with-claude/streaming)、[思考文档](https://platform.claude.com/docs/en/build-with-claude/thinking) 和 [模型目录 API](https://platform.claude.com/docs/en/api/models/list)。

远程 HTTP 测试覆盖请求、流式思考和签名、授权工具回传、拒绝回传、流错误、截断、取消、JSON 回退、分页和独立订阅凭据；存储与界面测试覆盖转录重开/分支、配置与协议参数持久化。真实账户、真实模型能力和真机联动需另行验证。当前没有请求服务端搜索、代码执行等工具类型；模型返回这些尚未支持的原生内容时会展示明确错误，应用内 JS 工具仍走既有权限门禁。
