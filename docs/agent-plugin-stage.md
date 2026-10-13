# 可安装的 JS/TS Agent 驱动（0.23.1 候选）

API14 新增 `agent` 权限、默认关闭的 `agentEnabledSetting` 声明，以及独立 `solvitPlugin.agent({maxRounds}, context)` 入口。普通插件包可提供步骤驱动，注册到 `agent.drivers` 服务；Android 的既有 Agent 使用该服务选择策略。安装/授权不自动接管聊天，需明确打开宿主配置中的接管开关，插件页面不能自行写入这个开关；当前是全会话策略，非按角色或会话选择。没有选中时使用既有包内JS，多个策略同时选中时明确报错，不按注册顺序悄悄覆盖。

TS `AgentContext` 只提供单个 `model(round)`、`tool(index)`、`settle(round)`、`finish(round)` 命令。宿主仍核验顺序、工具权限、执行事实与预算；脚本不能更改协议转录或绕过工具确认。每轮独立实例，8 MiB 内存、256 KiB 栈，第三方脚本评估期限10分钟；正常工具实例的activate/deactivate与Agent实例不共享状态。Agent实例没有工具/网络/存储的通用桥接或API Key、提示词、工具原始结果；这些宿主能力尚未开放为JS消息投影/Provider/存储替换协议。

服务注册拥有运行中的完整轮次作用域，包括驱动返回后宿主仍需完成的资源清理；等待边界不是仅等待QuickJS结束。停用、替换、卸载撤销入口并取消、等待其轮次清理；失效注册的旧清理函数不影响新版本。已执行文件修改等副作用不能自动回滚。按现有配置管理规则，修改配置前须停用插件；停用会取消它正在执行的轮次。聊天停止也会取消当前轮次。

示例 `plugin-examples/agent` 提供真实TS步骤循环，构建为 `example.agent.solvit.json`，作为可安装示例而非自动内置。市场仅向hostApi14以上列出它。通过“插件 → 安装示例 → 配置并打开接管所有会话 → 授权并启用”使用；恢复默认驱动请关闭该配置或停用示例。第三方脚本仍须经过普通包校验、权限和启停管理。

## 上游依据与边界

读取锁定 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`（MIT）`agent-loop/src/index.ts` FactoryOwnership 的登记、拒绝新工作、撤销/等待链，以及 `tests/scope-lifecycle.spec.ts` `handle.dispose() during owner unload still awaits true quiescence`；采用拥有作用域和等待清理的原则，Android用Coroutine Job而非Cordis Fiber实现。另读 `tests/loop.spec.ts` 的实时chunk/持久结果与更新参考契约，保留原宿主转录和上下文刷新。

读取锁定 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（LGPL-3.0）`ConversationService.kt` 的beforeContext及角色系统消息组装，继续保留宿主负责角色和协议投影。没有复制上述源码，也没有引入TS/Cordis运行时。QuickJS版本仍1.0.15，使用已有评估期限接口及父协程取消；参考[官方取消说明](https://github.com/dokar3/quickjs-kt#async)。

此阶段开放有界步骤策略，不等于完整DSH、JS消息投影/模型流RPC、Provider/存储插件替换、复杂Agent循环或真实模型任务验收完成。[完整路线](product-roadmap.md)继续保留。

## 验证

源码已新增注册选择/冲突、取消与逆序清理等待、旧注销句柄、清单门禁、真实QuickJS隔离实例、启用/停用和TS包构建回归。远程Android/TS验证与产物生成待实际报告；不运行本地构建或测试。
