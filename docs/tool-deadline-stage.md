# 工具自身期限与用户取消分开结算（0.22.7 候选）

## 具体故障与复现

`ToolRegistry` 将工具执行包在 `withTimeout(60_000)` 内，超时抛出 `TimeoutCancellationException`。`ToolAgentRuntime` 的取消分支直接传播该异常，于是本应回传给模型的单次工具失败成为整轮取消，下一步不会执行。这是调用注册器边界的确定性故障，不是用户真实网关延迟或角色问题的完整根因。

先提交回归 `ed9886187a34d986cf6519b40fa1a65cb91a2e80`，[远程红色复现 38350931804](https://github.com/veryy/Solvit/actions/runs/38350931804) 实际 XML 显示：`ownDeadlineReturnsSafeFailureAndLeavesCallerUsable` 因收到 `TimeoutCancellationException` 而失败；`enclosingDeadlineRemainsCancellationAndCleansTool` 通过。虚拟时钟推进工具实际60秒协程期限，测试不真实等待60秒。常规构建在 `:core:test` 失败后停止，因此这个红色运行没有执行随后 `chat-runtime` 的新增回归，不能称两个层次都已观察到红色。

最小复现直接调用真实 ToolRegistry；异常由它自己的期限产生，已足以定位这个明确的取消边界。本次据此直接修复，不把独立的模型/网络症状归因于它，也不追加无关的猜测或日志。

## 修复及边界

使用 `withTimeoutOrNull` 识别注册器这一层自己的期限；只有它返回空结果时抛出宿主 `ToolExecutionException(TIMEOUT)`。Agent 沿用结构化失败结算：保留工具调用 ID，状态 `FAILED`，回传安全的错误代码和下一步建议，再交给模型决定。建议明确已有副作用可能未知，先核对实际任务/文件/服务状态，不自动重复执行。原始插件异常和密钥不会进入错误详情。

用户停止和外层期限继续传播取消，禁止后续模型步骤。工具异常结算仍走现有调用资源取消/逆序清理；成功工具和已持久化资源的原语义保留。60秒是协作式协程期限；不承诺无法响应取消的原生操作或 `NonCancellable` 清理能在绝对60秒内返回，也不将插件内所有嵌套超时一并改写。

回归检查注册器超时后的调用者/其他工具可用、工具实际 finally 和已登记资源清理、外层期限仍是取消；Agent 完整两步链检查同一调用 ID 的失败回传和最终回复，用户在工具执行中停止检查只调用一次 Provider 且没有失败伪结算。Provider 是受控fixture，不能冒充真实模型成功。

## 对应上游与适配

本阶段读取锁定 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`（MIT）的 `agent-loop/src/tool-calls.ts` 中工具报告与 owning-step 取消/调度失败边界、`src/agent.ts` 的请求错误/结算和 `tests/cancel.spec.ts` 中用户取消丢弃半截调用、保留完整内容的用例。读取锁定 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（LGPL-3.0）的 `StandardTerminalCommandExecutor.kt` 执行流：命令期限触发后停止命令、保留已收到输出、使用超时状态和退出码结算。

采用单次执行失败与整轮取消分别处理的原则。Kotlin 用期限所有者判定保留外层取消，不直接照搬捕获所有超时的分支；工作区退出码/文件差异仍由既有执行记录管理。未复制上游实现或引入新的上游依赖。既有工作流与边界以 [架构](architecture.md)、[完整产品路线](product-roadmap.md) 为准。

## 验证状态

修复源码 `6507d9089a721d768a3b1e426c20572b0cf50803`，build43/API13，[完整 Android 门禁 38351266635](https://github.com/veryy/Solvit/actions/runs/38351266635) 成功。实际 XML 321 单元、48 模拟器测试，均为0失败/错误/跳过；上述4个新增回归全部通过，含真实注册器/资源清理和受控 Provider 的完整 Agent 后续步骤。Lint、固定签名、APK、角色设置、HTML/PTY、可见流式与覆盖升级配置/聊天保留通过。

[APK产物](https://github.com/veryy/Solvit/actions/runs/38351266635/artifacts/11790894418) 23,521,972 bytes，SHA-256 `621ddc4bb31c6bf0132dfbe93c95075b46a27f9d0d7a2c0d18d187e5487c53bb`。ZIP/APK CRC通过，8个内置插件资产与当前源码一致；固定证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`，`gradlew`可执行权限保留。APK内实际DEX有新增宿主超时诊断字符串，旧0.22.6包没有，进一步核对变更进入了产物；这项静态检查不能代替已运行的行为回归。

真实模型与真机仍未验收；公开搜索门禁仍失败，详见 [当前公网复核](browser-search-stage.md)。本版为候选，未改插件包/服务器或绕过正式发布门禁。完整 JS Agent/Provider/存储替换、任务完成验证、搜索引用质量和手机闭环继续保留；工具自身期限修复不代表这些问题均已解决。未运行本地 Android/TS 构建或测试。
