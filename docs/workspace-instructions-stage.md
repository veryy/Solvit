# 工作区指令阶段（源码复核，尚未实现）

0.24.1正式APK已发布；下一项改造针对实际工作区任务的上下文，保留完整修改审阅、任务完成验证和真实模型验收，不把读取规则作为整个任务完成。

## 实际上游与测试

锁定DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`，通过 `scripts/fetch-upstreams.py --verify-only` 核验。实际指令实现位于 `packages/context/agent-instructions`，不是workspace注册器：

- `src/config.ts`：AGENTS.md/CLAUDE.md候选、local覆盖、单源和渲染字节预算。
- `src/files.ts`：项目根、顺序发现、有界读取、取消与内容更新。
- `src/index.ts`：初次请求前的上下文基线；成功文件工具触达后的子目录、变化和删除更新；生命周期取消与执行标记。
- `src/render.ts`：具体作用域优先，来源/省略/截断标记，文件内容不能提升为系统或用户指令。
- `tests/agent-instructions.spec.ts` 实际阅读393–551与907–1040：root-to-cwd顺序、local覆盖、.git文件边界、相同大小/mtime变化后重新读取、关闭分隔符转义、更具体来源优先和UTF-8字节预算。
- `tests/agent-instructions.e2e.ts` 实际阅读首次、子目录和修改后的模型握手：使用真实DeepSeek凭据，无凭据时跳过。Solvit不能把其机制用例视为真实回答通过。
- `core/system-prompt/src/index.ts` 与 `tests/scoped.spec.ts`：会话作用域覆盖、释放与全局资料隔离。
- `workspace/workspace/src/paths.ts` 与 `tests/workspace.spec.ts`：规范目录身份、注册与会话绑定；它们不是规则加载实现。

DSH为MIT，当前仅阅读，没有复制实现。Operit锁定版本终端的授权、会话复用、取消原则仍沿用已完成阶段记录。

## Solvit当前落点

`ContextInput`仅含role/userText；`ToolAgentRuntime`每次模型前刷新Hook，但Hook没有绑定工作区的只读读取契约。`ScriptExecutor`的beforeModel目前只允许storage.get/clock.now。`MarketplacePlugin.hostCall`的普通workspace操作要求实际ToolInvocationContext、批准的完全相同参数及ConversationContext；因此不能直接放宽白名单，让规则Hook复用任意workspace.read。

现有完整请求预算、角色过滤、插件启停与取消保留。应提供单独的受限只读契约，宿主捕获当前对话/工作区并复核实时授权与绑定；脚本不得传入其他对话、目录或任意绝对路径。JS/TS负责规则选择、去重、来源和渲染；宿主负责文件边界和有界UTF-8读取，不将整个DSH/Cordis/Node搬入APK。

先交付绑定目录根的AGENTS.md/CLAUDE.md及local来源，之后通过已成功的文件工具触达处理子目录规则。每步读取必须处理更新、删除和撤销，不能让老规则静默留在上下文。输出进入现有全请求预算，不单独增加无上限系统提示。当前`attachPluginReference`每步从规范消息重建参考资料，不把旧Hook帧写入聊天历史；新规则投影应保留这项更新/删除语义，并为Claude原生签名前缀单独做协议回归，不能直接照搬DSH持久消息投影。没有工作区时普通聊天继续工作，不能自动创建工作目录。

现有`WorkspaceSnapshots`保存每文件512-byte片段，`FileObservations`写入证据最多6KiB；`WorkspaceChangesReview`显示前后片段而非完整diff。完整diff需要另行保存不可变内容与明确大小/二进制限制，不能用打开当前文件来冒充原始历史。

## 后续验收

先远程复现缺失的规则投影，再实现并保留断言：真实QuickJS→当前聊天绑定→本地规则→捕获模型请求；更新/删除、角色隔离、实时撤权、换工作区、符号链接越界、二进制/大小限制、取消与UTF-8。随后核验真实APK操作和新凭据模型握手。源码复核不算功能完成；尚无本阶段实现提交、测试通过或APK。
