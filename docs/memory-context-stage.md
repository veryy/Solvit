# 上下文与自动记忆阶段

本阶段参考固定版本的 Operit `api/chat/enhance/ConversationService.kt`：按当前记忆空间读取用户资料，再作为有来源的用户资料贡献到聊天上下文；其 `MemoryAutoSaveScheduler.kt` 则分开处理后台候选、批次及资源上限。参考 DSH `packages/core/agent-loop/src/agent.ts` 的每步 `preStep` 重新组装，`packages/compaction/compaction-basic/src/config.ts` 的输入/输出预算分离，以及 `region.ts` 与 `tests/compaction-basic.spec.ts` 的工具配对和摘要事务。采用行为和职责划分，未直接复制上游实现代码。

先交付 TS 自动检索与每步刷新，随后实现全请求预算和可查看摘要，保留后台记忆候选这一完整目标。自动检索只读取已经保存的记录，不代表后台自动抽取已完成；对不同模型的 Token 估计不能冒充 Provider 实测 usage。

API 7 提供原生布尔配置开关和 `beforeModel` 输入中的非密钥配置快照。仅声明为非 `secret` 的字段由宿主读取；Hook 仍不能访问密钥、执行写入、联网或启动工具。旧 API 的输入契约保持兼容。客户端、打包和市场按 API 版本过滤，新增包上线前验证旧客户端下载及字节校验。

所有构建和测试通过 GitHub Actions；源码阶段尚未验证通过，不宣称真实模型或真机已完成验收。
