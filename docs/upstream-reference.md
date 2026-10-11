# 已拉取的源码参考

六个仓库已在 2026-10-12 实际克隆到 `../Solvit-reference/`，并核对 Git HEAD、工作树及许可证文件。完整 URL 与版本见 [版本锁](upstream-sources.json)。参考源码保存在项目外，避免将数万上游文件混进 Solvit 历史和 APK。

```sh
python3 scripts/fetch-upstreams.py
python3 scripts/fetch-upstreams.py --verify-only
```

| 目录 | 参考内容 |
| --- | --- |
| `dsh` | Prompt 注册、每步组装、Agent、事件、工具和上下文压缩 |
| `operit` | Android 聊天、角色、记忆、搜索、权限和终端 |
| `open-autoglm` | 手机观察与动作闭环 |
| `zcode-plugins` | Skills/Hooks/MCP 组合及市场包 |
| `zai-coding-plugins` | 官方编码插件示例 |
| `claude-code` | 公开插件与示例；仓库不包含完整 Agent 核心 |

每个阶段按照 [AGENTS.md](../AGENTS.md) 先阅读对应实现，再记录采用部分、平台差异与验证结果。上游版本升级应作为明确的参考版本更新提交，不悄悄改变基线。

## 当前阶段：聊天执行链

已阅读 DSH `packages/core/system-prompt/src/index.ts`、`packages/core/agent-loop/src/agent.ts` 的 Prompt Section 与 `preStep`；Operit `api/chat/enhance/ConversationService.kt` 的角色/全局配置组装，以及 `ui/common/markdown/RenderBatchCoordinator.kt`、`StreamMarkdownRenderer.kt` 的独立刷新策略。

Solvit 的适配：提示段有名称和来源，身份贡献一次；原生 Flow 接收与平滑显示分离，有界队列按帧刷新，并保持停止与工具回合边界。此阶段采用结构和行为参考，没有直接复制上游实现代码；完整上下文预算、每步动态注册和自动记忆仍按方向文档推进。

回归先于修复提交。身份重复已在 [远程运行 38157737692](https://github.com/veryy/Solvit/actions/runs/38157737692) 复现：18 个 chat-runtime 测试中身份唯一性检查失败。Gradle 因此提前停止，尚不能据该次结果宣称流式阻塞测试已执行。
