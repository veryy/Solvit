# Solvit 后续方向：先打通执行链，再扩展能力

研究日期：2026-10-12。初审基线为 `2d40d9c` / 0.15.1；本次复核基线为 `ae49c7e` / 0.15.3 发布提交。本文是源码审查与实施决策，不能当作真实模型、真机验收结果。

## 结论

继续保留 Kotlin / Compose / SQLite / QuickJS。产品定位为能聊天、理解工作区并实际完成任务的 Android Agent，主要扩展由 JS/TS 插件提供。

前面的交付偏重功能入口、插件数量和编译验证。下一阶段的重点是让角色、上下文、模型响应、工具结果、记忆和展示形成可检查的执行链。先完成普通聊天、联网问答两个可靠场景，再完成工作区任务；随后推进手机操作。更多模型协议不作为当前优先事项。

## 上游实际提供了什么

| 来源 | 实际审查范围 | Solvit 采用的方向 | 移植边界 |
| --- | --- | --- | --- |
| DSH | `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 Prompt 注册、Agent 循环、持久化与压缩契约 | 有名称/顺序/作用域的提示段；每个模型步骤重新组装；可替换服务；步骤日志；工具结果裁剪与摘要 | MIT；可优先抽取独立 TS 类型、算法及相关测试。Cordis、Node 服务和桌面入口需要适配，不能把完整项目直接放进 QuickJS |
| Operit | `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 ConversationService、EnhancedAIService、流式渲染、记忆调度和搜索脚本 | Android 角色和工具范围一起组装；资料进入上下文；流解析与刷新分离；自动记忆候选；网页访问和正文提取 | 仓库 LGPL-3.0，直接复用前核对具体文件及依赖许可、署名与分发要求。已有成熟部件应评估抽取复用，不能把每个功能都重新简写一次 |
| Claude Code | 官方执行、记忆和插件文档及仓库许可证 | 获取上下文→执行→验证；项目规则、自动记忆、按需加载 Skills；上下文预算；中断和恢复体验 | 完整核心不是该仓库公开提供的开源实现；按官方文档设计兼容行为，不使用泄露实现 |
| 智谱 Open-AutoGLM | `86f55382982fb054e8fc98ca80609dff8a2cdc3c` 的 phone_agent、模型请求和动作处理 | 每一步先观察手机，再生成动作、执行，下步重新观察；明确完成、接管及步骤上限 | Apache-2.0；Python 外部设备控制不是 Android 应用内运行时，需要设备能力适配和能看图的模型 |
| 智谱 ZCode 插件仓库 | 当日官方 README、插件分类和发布入口 | 插件包组合 Skills、Hooks 和 MCP；复用领域知识与工作流，减少孤立的工具菜单 | 仓库 Apache-2.0，具体包仍需检查许可。它是插件市场源码，不能据此宣称完整 ZCode Agent 内核开源；部分能力依赖宿主账号、订阅和 CLI |

“智谱的”在此分别按手机 Agent 与编码插件生态研究。模型权重仓库不等同于一个可直接复用的 Agent 产品。

## 已确认的 Solvit 差距

以下事实来自本仓库代码；体验影响属于据此作出的工程判断，不能替代实际网络请求诊断。

1. **角色需要实际模型验收。** 初审发现重复身份注入；0.15.2 已改为带名称和来源的提示段，身份只贡献一次。0.15.3 记录实际 Provider 请求的角色与上下文来源元数据。配置进入请求的回归已通过，但尚不能证明用户网关及真实模型遵守角色；默认、自定义、切换和重开对话仍需同一模型验收。
2. **流式体验需要区分接收与显示。** 初审发现 `collect` 内逐字符等待阻塞接收；0.15.2 已改成独立有界队列与按帧显示，0.15.3 分开记录收到解析事件和显示完成的耗时。回归已通过，网关是否缓冲仍待实际请求检查；解析事件时间也不是网络首字节时间。
3. **上下文管理缺位。** `DefaultChatService` 汇总完整有效历史；0.17.0 源码已将 `ToolAgentRuntime` 改为每步刷新工具和 Hook。没有明确 Token 预算、自动摘要和工具结果压缩。单个工具结果的 256 KiB 限制不是上下文预算。
4. **插件能力还不等于 DSH 的可替换内核。** TS SDK 提供工具、启停和只读 `beforeModel`。第三方 JS 还不能替换 Agent、模型 Provider、上下文策略或聊天存储。原生 Kotlin 服务接口可替换是另一层机制。
5. **记忆有存储工具，缺少自动使用链。** 0.17.0 源码已为记忆 1.1.0 接入自动检索 Hook 和原生开关；旧 1.0.0 需用户更新并重新启用。回复后的自动提取仍未实现，自动检索不等于后台自动学习。
6. **搜索验收只证明返回来源。** 默认 Bing RSS 免密钥，已有可用性和格式检查；没有搜索相关性、时效性、页面正文和最终回答引用的完整验收。Operit 的脚本调用网页访问能力，支持多个引擎及进一步读取页面，能力链更完整。
7. **执行失败信息太粗。** Agent 将一般工具异常统一转换为一句失败提示。需要脱敏后的错误类型、步骤、可恢复建议和 UI 详情，才能让模型及用户正确处理失败。
8. **执行记录到回合结算才持久化。** 当前结束、异常或取消会统一保存；进程被杀不是正常取消，不能保证中间工具执行事实已保存。需要步骤日志，并区分可恢复状态与不能自动重放的外部操作。

单元和模拟器测试通过证明了受测行为，不能据此承诺真实模型稳定遵守角色、正确选择工具或搜索回答准确。

## 本次方向复核：按可完成的任务交付

主参考分工保持明确：Operit 对照 Android 产品体验及宿主能力；DSH 对照可组合的提示、服务、生命周期和每步执行；Claude Code 对照“获取上下文→行动→验证”的任务流程；Open-AutoGLM 对照手机观察与动作闭环；智谱编码插件仓库对照 Skills/Hooks/MCP 组合包。Claude Code 的公开仓库不包含完整核心，ZCode 插件仓库也不等于其宿主内核。

主分支已合入 0.17.0 的每步工具/Hook 刷新和已保存记忆自动检索，163 个单元与 29 个模拟器测试通过；回合结束自动抽取仍未实现，`DefaultChatService` 仍加载完整有效历史。下一项明确落点是全请求预算与可查看摘要。搜索浏览器桥及 TS 策略已合入主分支并发布 0.16.0，公网来源/正文及官方版本时效检查通过；真实模型最终引用、真机网络和更广泛问题集仍待验收。

每阶段至少用一个真实任务验收：

- 聊天：选择一个明确角色，切换并新建/重开对话，检查实际请求来源和回答；连续流式与整块回复分别计时，停止后及时结束。
- 搜索：中文、英文及近期事实问题，检查搜索结果相关性，读取正文，再核对回答中的引用是否支持结论；引擎拒绝或失败必须明确呈现。
- 记忆：明确保存偏好，另一会话自动检索并使用，展示来源；删除或停用后不再注入。长历史压缩后保留身份、任务状态及工具调用配对。
- 工作区：创建或修改一个小项目，运行命令，检查退出码和差异，失败后修复再验证；不能把写入文件或命令启动当作任务完成。
- 手机：先观察当前界面再操作，操作后重新观察；受控真机任务验证完成和接管，不以模拟器工具入口代替。

先保留 Kotlin 的授权、平台能力和执行状态机，通过 JS/TS 提供搜索、Skills、记忆与上下文策略。把整个 Agent 搬到 JS 需要模型流、事件持久化、取消和资源预算协议；应在上述任务稳定后单独实施。当前停止增加模型协议和孤立插件入口，按聊天→搜索→记忆/预算→工作区→手机的顺序推进。

## 本次源码复核后的具体决策

本次核对发布主分支 `61ad091`，并确认六个参考 checkout 与锁定版本一致。每步刷新 Hook、已保存记忆的自动检索与公开布尔设置已合入主分支；[远程运行 38167002200](https://github.com/veryy/Solvit/actions/runs/38167002200) 通过 163 个单元及 29 个模拟器测试。标签发布结果另行记录，自动检索不能称为后台自动学习。

1. **以 Operit 为 Android 主参考，以 DSH 为执行架构主参考。** 保留原生聊天、SQLite、PTY 与 QuickJS；JS/TS 承载搜索、记忆、Skills、工具和上下文策略。权限、凭据、取消和资源限制继续由宿主强制执行。第三方 JS 可替换完整 Agent/Provider 是后续独立协议工作。
2. **先验收用户已经报错的场景。** 角色应用后、新建和重开聊天必须检查实际请求与真实回答；流式必须同时测接收和可见输出，不能用动画掩盖网关整块返回。搜索必须读正文并验证回答引用。当前远程回归通过的部分与真实模型尚未验收的部分分别标记。
3. **下一项机制交付是上下文与记忆。** 先完成候选分支的远程验收，再加入整个请求的预算：身份、规则、工具 schema、记忆、历史、工具结果以及输出预留一起计算。DSH `compaction-basic/src/config.ts` 区分输出预留和压缩余量；`region.ts` 保留工具调用/结果配对，并检查摘要替换边界。采用这些约束，不能用单段字符上限替代全局预算。
4. **随后交付一个完整工作区任务。** 工具返回退出码、输出、工作目录和变更证据；失败细节脱敏后同时供模型与用户查看。参考 DSH `agent-loop/src/agent.ts` 的步骤事件和异常恢复结果记录，执行过程中保存日志；恢复只展示已经执行的事实，不自动重放命令。以“建文件→运行→失败→修复→再运行”作为验收任务。
5. **后台记忆与手机操作分别交付。** Operit `MemoryAutoSaveScheduler.kt` 使用资料空间分组、候选状态、批次上限和单任务运行保护；Solvit 应有明确开关、来源、失败状态和删除入口，再接回复后的抽取。Open-AutoGLM `phone_agent/agent.py` 在每一步重新截图、执行动作并检查结束/步骤上限；手机能力必须采用这样的观察闭环。

Claude Code 的官方执行文档用于对照“获取上下文→行动→验证”；公开仓库不提供完整核心。智谱编码插件中的 Skills 是任务说明与脚本的组合，例如 `glm-plan-bug/skills/case-feedback-skill/SKILL.md` 依赖 Node 脚本。Android QuickJS 不提供 Node 的进程、文件与模块环境，导入文本不等于脚本可执行；适配应显式声明宿主能力与运行环境，禁止默默宣称兼容。

直接复用的选择顺序是：检查许可与运行环境 → 抽取可独立使用的组件及测试 → 用 Kotlin 宿主适配平台接口 → 用相同任务验证行为。复用完整 Operit 会扩大 Android 能力和产品范围；复用完整 DSH 则需要其 Cordis/Node 环境。当前选择渐进适配现有 Solvit，不重开一个功能更多但链路同样未验收的项目。

每个功能版本的验收都包含：实际任务结果、错误与取消、旧数据及签名兼容、远程构建和可下载 APK。测试数量是证据的一部分，不能代替任务结果。本次仅更新研究和实施方向，不新增 APK 版本。

## 最小架构调整

```mermaid
flowchart TD
    UI[Compose：显示、审批、操作] --> CHAT[ChatService]
    CHAT --> RUN[AgentRuntime：步骤状态机]
    RUN --> CTX[ContextComposer：身份、规则、资料与预算]
    CTX --> JS[JS/TS：角色策略、Skills、记忆、工具发现]
    RUN --> MODEL[Provider：响应事件流]
    RUN --> TOOL[ToolRegistry：执行与结构化结果]
    TOOL --> HOST[Kotlin：授权、网络、文件、PTY、设备能力]
    RUN --> LOG[SQLite：步骤日志与聊天投影]
    MODEL --> BUFFER[有界接收缓冲与展示调度]
    BUFFER --> UI
```

`ContextComposer` 提示段建议为 `identity`、`user-rules`、`workspace`、`skills-index`、`memory`、`tool-guidance`；段带来源、作用域和预算。规则和身份与外部资料分开，工具权限由宿主强制执行。执行中按步骤刷新允许变化的上下文，不把插件返回文本直接当成更高优先级规则。

逐字显示由独立展示调度控制。Provider 和 Agent 及时收集原始事件；Compose 按帧批量刷新，可保留平滑逐字观感。记录请求开始、首个网络内容、首个可见文字和完成时间，区分模型思考、网关缓冲、解析等待和渲染排队。缓冲必须有界，停止操作同时取消网络、工具和展示队列。

TS 插件接口按阶段增加提示段贡献、受限的 `afterTurn`、工具筛选和上下文压缩策略，保留旧插件兼容。自动写记忆采用明确开关、授权、来源和可删除记录。新 Hook 不得绕过宿主权限或在失败后循环重试。

第一阶段保留 Kotlin 步骤状态机，让 JS/TS 提供策略和能力。若后续需要 JS 替换整个 Agent，再增加受限的模型流 RPC、持久事件协议和资源预算；这一步复杂度高，应独立交付，不能仅在清单上新增一个“agent”字段就声称完成。

## 实施顺序与验收

| 阶段 | 具体交付 | 验收依据 |
| --- | --- | --- |
| P0 聊天稳定性 | 上下文组装与脱敏诊断；角色统一来源；接收/展示分离；保留错误细节；暂停更多协议 | 真实模型测试默认/自定义/切换角色、重开/新对话、长历史；覆盖连续 SSE、整块 JSON、长思考、工具和停止。诊断能显示最终角色与上下文来源，密钥不输出 |
| P1 搜索闭环 | 免配置引擎适配、失败回退、网页正文提取、来源展示 | 固定中文/英文与时效问题集，人工核验前列结果相关性、正文和回答引用；失败明确说明。返回三个 URL 不算搜索质量验收 |
| P2 上下文与记忆 | Token 估计和预算、工具结果裁剪、可查看摘要；自动检索注入、授权的后台记忆候选与删除 | 跨会话记住明确偏好，检索带来源；未启用不读取；删除后不再注入；摘要保留角色、未完成任务与工具调用配对。估计值与 Provider 实测 usage 分开标注 |
| P3 工作区任务 | 项目规则、可靠读写、差异审阅、Shell 输出、执行后验证、步骤日志 | 实际完成创建/修改/运行/修复一个小项目；确认退出码和文件差异；恢复后展示已执行事实，不自动重放有副作用的操作 |
| P4 手机操作 | 截图/界面观察、可授权动作、操作后重新观察、失败与接管 | 参考 AutoGLM 的闭环，在真机受控任务中逐步验证；模型能力和 Android 实际权限满足后交付 |

难度判断：复用当前服务做 P0/P1 属于局部到中等改造；P2/P3 涉及跨模块协议和数据迁移，难度较高；完整 DSH 式 JS 内核替换和手机控制为独立的大改造。此判断不是工期承诺。

每个阶段及时提交，功能修改通过 GitHub Actions 远程测试、Lint、稳定签名 APK，再发布。新增验收覆盖用户实际任务；不再以入口存在或测试数量代替可用性。文档研究本身不修改 APK 版本。

## 可核对来源

- DSH：[架构](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md)、[Prompt 契约](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/docs/subsystems/system-prompt.md)、[步骤实现](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/packages/core/agent-loop/src/agent.ts)、[持久化](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/persistence.md)、[压缩](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/compaction.md)、[MIT](https://github.com/deepseek-ai/deepseek-harness/blob/master/LICENSE)。
- Operit：[上下文与角色组装](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/api/chat/enhance/ConversationService.kt)、[记忆候选调度](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/api/chat/library/MemoryAutoSaveScheduler.kt)、[渲染刷新协调器](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/common/markdown/RenderBatchCoordinator.kt)、[搜索脚本](https://github.com/AAswordman/Operit/blob/main/examples/various_search.ts)、[许可证](https://github.com/AAswordman/Operit/blob/main/LICENSE)。
- Claude Code：[执行方式](https://code.claude.com/docs/en/how-claude-code-works)、[项目规则与自动记忆](https://code.claude.com/docs/en/memory)、[插件](https://code.claude.com/docs/en/plugins)、[仓库许可证](https://github.com/anthropics/claude-code/blob/main/LICENSE.md)。
- 智谱：[Open-AutoGLM 步骤实现](https://github.com/zai-org/Open-AutoGLM/blob/86f55382982fb054e8fc98ca80609dff8a2cdc3c/phone_agent/agent.py)、[ZCode 插件市场](https://github.com/zai-org/zcode-plugins)、[官方编码插件](https://github.com/zai-org/zai-coding-plugins)。
