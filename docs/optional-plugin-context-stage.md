# 可选插件资料的失败隔离

0.22.5候选/build41，host API13不变。从0.22.4分层修复继续；本阶段针对实际宿主反馈，不把它当作真实网关慢或角色失效的全部根因。

## 上游与适配

实际核对 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `packages/core/system-prompt/src/index.ts` assemble：必需提示、作用域、资料与工具分层，配置错误沿异步错误路径传播。读取 `packages/core/agent-loop/src/runtime-context.ts` 与 `tests/runtime-context.spec.ts` 的动态快照/会话所有权。DSH没有直接提供本阶段的Android超时隔离实现；不能声称本改动照搬它的失败策略。

Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `api/chat/enhance/ConversationService.kt` 角色解析与规则组装仍需有效来源，总结出错会重新抛出。Solvit只读 beforeModel 是可选资料，不承担角色权限或工具授权，故此阶段隔离这类资料失败，必需角色/系统规则与授权失败仍然中止。DSH MIT、Operit LGPL-3.0；未复制上游源码。

## 实际复现

[红色运行38342323389](https://github.com/veryy/Solvit/actions/runs/38342323389)中ContextHooksTest实际5个用例，新增3个全失败：抛错Hook导致整个prepare抛出；慢Hook10秒虚拟时间超时直接抛给调用方；卸载Hook后等待返回仍携带旧资料。不是模拟器/真实模型耗时结果。

预测与对应改动：捕获可选异常后健康资料仍应返回；并发与整批期限将等待约束在一个资料等待窗口；返回前核对同一注册对象可丢弃已卸载/替换资料。用例不改变用户停止语义。

## 协议与边界

每批最多64个注册Hook、四个并发读取任务，整批10秒协作式等待窗口（包括排队）。未开始/未完成任务会取消并报告超时；不能保证排在慢任务之后的每个健康Hook都能在窗口内完成。取消的原生操作清理仍须等待，不将协作式deadline当作不可中断代码的绝对墙钟保证。每个返回正文最多32KiB，汇总参考资料包括来源头部最多64KiB；按原注册顺序保留完整贡献，不从正文中间切断。超出汇总预算的插件单独报告。

prepareReport提供FAILED/TIMED_OUT/RESOURCE_LIMIT与插件ID，不记录原始异常。模型收到资料缺失状态，不能假定记忆/Skills已读取；最近请求诊断显示本地化原因。问题报告不自动启停插件，也不代表其工具失效。只读Hook不能取代工具授权。

返回前检查当前注册对象，卸载/替换不会交付旧资料。用户取消上层聊天始终传播；插件取消自己的读取子任务仅记录可选失败。JVM致命错误不吞掉。prepare旧返回接口保留供只读取资料的调用者；Agent使用报告接口。

## 验证

新增核心回归覆盖错误/慢Hook、并发健康插件、卸载、用户取消、插件子任务取消、单项和汇总大小、角色范围与无异常泄漏。真实QuickJS → ToolAgentRuntime → OpenAiProvider → HTTP回归：JS Hook抛错后仍产生完整聊天请求/回复、保留健康资料、只报告安全元数据，工具仍可授权使用；停用后报告和资料移除。

既有真实模拟器流式用例注入失败Hook：首段可见、长回复跟随及停止/完成检查保留，诊断里显示插件/原因并不显示异常原文，替换脆弱的固定索引滚动为按实际文本滚动。

最终源码 `1b385f2`，[远程完整门禁38343216346](https://github.com/veryy/Solvit/actions/runs/38343216346)构建阶段成功；实际XML317单元，0失败/错误/跳过。ContextHooksTest的8个核心回归通过，真实QuickJS失败隔离用例0.029秒通过；Lint、APK、固定签名成功。完整模拟器XML48个测试，0失败/错误/跳过。实际流式首段/长回复跟随/失败诊断可见、角色继承、HTML/PTY、覆盖升级保留加密配置和聊天全部通过。已查看实际诊断截图：失败插件、原因、计时和关闭按钮可见；图片使用受控模型响应，不代表真实模型延迟。APK/ZIP CRC、8个内置插件资产与源码一致；[候选下载及校验](release-0.22.5.md)。不运行本地Android或TS构建/测试。真实模型/网关、真机、完整JS内核替换、搜索正式发布门禁和终端收整仍未完成，完整路线保持。

Required role/system rule and tool-gate failures are not routed through optional context isolation. Fatal JVM errors remain fatal. Original red cases now pass; no temporary debug logging was introduced. Size and user-cancel enforcement remains; oversized-Hook expectations intentionally change from aborting prepare to a RESOURCE_LIMIT report with no data admitted. No tests skipped. Remaining acceptance stays in the whole roadmap.
