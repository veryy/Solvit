# 模型协议扩展记录

按当前用户优先级，暂停 Gemini 接入，先修复角色、逐字显示和设置/插件体验，再继续上下文、附件与记忆能力。本地推理仍在完整产品清单中。

多配置与任务选择使用 `ModelProfilesService`。OpenAI 兼容协议已经发布；Provider 注册/路由、原生转录与 Claude Messages 插件已提交实现，[远程验证通过](https://github.com/veryy/Solvit/actions/runs/38144065045)，详见 [Claude 插件](anthropic-provider.md)。Gemini 与本地推理仍待实现，不因原生基础接口已有而视为完成。

## 服务组合

已增加 Provider 注册服务与协议路由插件。连接配置保存协议，旧配置默认 OpenAI 兼容；每个 Provider 独立注册并负责请求、流解析、诊断与取消。聊天 UI 与 Agent 只消费统一接口。模型目录查询也随协议选择；遇到未加载协议时明确失败。

## 必须保留的数据

- [Claude Messages 流文档](https://platform.claude.com/docs/en/build-with-claude/streaming)区分消息开始、内容块、文本/工具输入/思考增量和消息停止，也允许流内错误。工具参数须累积后解析；HTTP 200 不能代替流正常结束。思考块还有签名增量，继续工具轮次时不能只保存展示文字。
- [Claude Messages 请求格式](https://platform.claude.com/docs/en/api/messages/create)需由独立适配器将系统指令、工具声明和调用/结果映射为原生格式，避免把 OpenAI JSON 直接发送到原生端点。
- [Gemini 思考签名文档](https://ai.google.dev/gemini-api/docs/thought-signatures.md)要求保留返回内容中的思考签名，继续模型或工具轮次时按原协议回传。具体接口选择需结合当前官方 API 文档继续核对。

现有 `reasoning: String` 用于界面展示，不能代替原生签名和内容块。接入前应增加带来源协议的受限原生转录数据，贯穿 Provider、Agent、SQLite、历史上下文和聊天分支。切换协议时只映射可通用的文本与工具语义，不把另一协议的签名当作有效原生数据。

## 验证门槛

每个协议必须验证真实 HTTP 请求结构、流式文本、工具参数增量、执行结果回传、连续工具调用、流内错误、截断与取消；同时检查原生转录的保存、分支复制与回传。最后运行既有聊天、权限、迁移和界面回归，远程构建并发布 APK。真实账户与真机的验证状态单独记录。
