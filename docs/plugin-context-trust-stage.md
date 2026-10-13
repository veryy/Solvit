# 插件资料与角色规则分层

当前阶段从0.22.3候选继续，目标是实际请求中的上下文边界，不把结构回归当作真实模型角色回答验收。

## 上游实际阅读

- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：`packages/core/system-prompt/src/index.ts` 的 PromptSection/PromptContext 分层；`packages/core/agent-loop/src/runtime-context.ts` 的 RuntimeContextProjection.project 将动态资料投影为 user 数据、清空旧快照；`tests/runtime-context.spec.ts` 的最新可见快照/会话隔离。MIT，未复制源码。
- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：`app/src/main/java/com/ai/assistance/operit/api/chat/enhance/ConversationService.kt` 解析当前角色卡、组合角色与用户规则，角色名称占位符替换和工具范围选择。LGPL-3.0，未复制源码。

## 复现与修复

`PluginContextHttpTest` 通过真实 ToolAgentRuntime → OpenAiProvider → MockWebServer 收集 HTTP JSON，使用显式角色和两轮工具调用/回传，Hook 从旧资料更新到新资料。不是假模型回答质量测试。

[红色运行38339666340](https://github.com/veryy/Solvit/actions/runs/38339666340)中，50个chat-runtime测试实际执行，唯一失败为新HTTP用例，XML明确报告 `Plugin data was elevated into system instructions`。这证明资料被提升到system层，不能据此声称用户全部角色/流式问题已定位。

可证伪预测：Agent提升则修改组装后HTTP system不再含资料；Provider提升则组装修改后HTTP仍失败；上下文累积则第二轮同时含旧/新资料。实际源码将Hook拼进system，首先修改此路径，HTTP用例覆盖Provider边界和两轮刷新。

非原生签名路径每步从原始消息重新组装，在最新真实user消息中附带明确的参考资料边界，再保留原用户请求。system保留角色/用户规则/宿主工具策略；资料不产生额外聊天回合，不持久化，不拆开assistant tool_calls与tool结果。使用当前用户消息的sourceMessageId和既有预算机制保护当前资料，不让它混入可摘要的旧回合。Hook为空时不加资料；原生签名Provider继续使用其独立runtimeContext适配。

额外回归覆盖整个当前用户资料的预算与压缩边界、无用户的请求仍采用user数据层并保留system首部、原生签名适配收到独立的每步资料而不修改当前用户前缀。包版本0.22.4/build40，host API13不变。

## 验证状态

首轮修复 `1caa64f` 的HTTP边界、预算和原生适配回归通过；[运行38340226854](https://github.com/veryy/Solvit/actions/runs/38340226854)在plugin-market的两个旧集成断言失败，它们仍要求资料位于system。已将记忆重启/删除/停用/角色范围与Skills元数据检查改为user资料层，并新增system不含资料、SQLite用户消息保持原文的断言。真实Android记忆用例也同步到新边界。最终源码 `7f2623c` 已推送，[完整远程运行38340728439](https://github.com/veryy/Solvit/actions/runs/38340728439)成功。实际XML核对310单元和48模拟器，0失败/错误/跳过；Lint、固定签名、APK成功。HTTP边界用例0.276秒通过；原始用户原文、记忆重启/删除/停用及Skills按需加载均保留。未进行本地Android/TS构建或测试。已核对XML、APK、签名和既有页面/升级回归；[候选下载与校验](release-0.22.4.md)包含实际文件SHA/长度和完整证据。

## 范围

将资料降为user数据和加边界提示不能保证模型免受提示注入，也不能保证模型遵守角色。没有调用用户历史中暴露的API Key。真实网关角色切换、新建/重开对话、可见首字延迟、工具选择和回答质量仍需有效凭据及实际运行证据；真机仍未验收。完整JS Agent/Provider/存储替换、手机操作与正式搜索发布门禁保持未完成，不能将此修复当作全项目完成。
