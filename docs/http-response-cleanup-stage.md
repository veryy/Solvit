# HTTP 响应取消与资源归属

## 实际故障

0.29.0 标签源 d041950 的正式运行 [38482438134](https://github.com/veryy/Solvit/actions/runs/38482438134)
构建成功，但完整模拟器运行在插件市场请求取消时崩溃。堆栈为 MarketplacePlugin.kt:612
的 continuation.resume(response) 取消回调关闭 TLS 响应，触发 NetworkOnMainThreadException，
外层为 CompletionHandlerException。原 Git QuickJS 用例仍保留；不能删除用例或关闭 StrictMode。
0.29.0 未正式发布，其标签保持原源。后续修复使用 0.29.1/build59。

## 参考与取舍

- Operit 固定 dbf71916fae9750cfdc9f9a774f5a0fee56633fb 的
  app/src/main/java/com/ai/assistance/operit/api/voice/HttpVoiceProvider.kt：
  executeBinaryRequest 在 IO 内读取和 use 关闭响应。采用 IO 资源处理原则，不复制其
  suspendCoroutine；它未覆盖本次可取消协程的资源交付竞争。
- [Kotlin 官方可取消挂起契约](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html)：
  callback resume 后、实际恢复前仍可能取消，返回的资源必须有明确清理责任；取消回调
  不能假设运行于 IO。本文查阅版本 1.11.0。
- 项目已有 provider-http 与插件市场两个同型实现；统一使用共享 readResponse，
  不新增插件 UI 或更改市场包、授权及 API19 协议。未引入上游代码或新依赖许可证。

## 修复设计与回归

响应由同步资源容器持有，回调只恢复 Unit，取消不能丢失 Response。
读取、正常关闭和取消请求都在 IO；finally 使用 NonCancellable 完成清理。
若取消清理已结束，迟到的响应由 OkHttp 回调线程关闭，不能重新交给调用方。
市场的取消会话清理继续保留原有一秒上限。

三项回归实际控制回调与恢复顺序，断言关闭次数、关闭线程、取消线程及正文：
响应交付后恢复前取消、取消完成后迟到响应、正常读取。
最初诊断运行 38485262179 在 SDK setup 请求旧 tools 包时失败，未运行测试，
不能算红阶段。工作流改为正式构建相同的 platform-tools 后，旧处理代码运行
38485703688；实际下载报告的三项测试均失败，交付后取消为同型 CompletionHandlerException，
正常读取为 TLS response closed on Main，迟到响应为主线程取消断言失败。
报告产物11860006036已下载并检查 ZIP CRC 和 XML。修复与完整原模拟器场景的结果
待报告核对，当前不能声称通过或已发布。

修复源2104f4a的[相同回归38485901760](https://github.com/veryy/Solvit/actions/runs/38485901760)
已成功；实际下载产物11860111180、检查CRC/XML，三项测试均通过，无失败、错误或跳过。
进一步复查发现 OpenAI 聊天和模型列表也有相同的私有 await/关闭回调，已统一接到
readResponse；原有 SSE、模型列表、取消与错误诊断测试保留。全套 Android 构建与
原正式模拟器场景仍待验证，不能以三项测试代替正式发布门禁。

## Provider 接入回归

完整运行38486119881在 OpenAI 流式用例出现6项失败，源11f3a4a的普通flow在
readResponse新增的IO协程上下文内发送。改为channelFlow/send并使用零缓冲，允许
资源读取上下文逐段发送，同时保留消费者背压、原解析与错误详情。
Anthropic同样采用该发送方式，NativeBlocks的原生块解析不变。

源8613671的完整运行38486618500仍失败：插件市场MCP取消通知的一个旧await调用
遗漏迁移，编译在MarketplacePlugin.kt:528失败。其前面已完成的实际报告产物
11859074960经ZIP CRC/XML核对：HTTP5、OpenAI19、Anthropic15，共39项测试均通过，
无失败、错误或跳过。随后源ffee1dd把取消通知也迁移到readResponse，保留原清理时限。
完整Android、Lint/APK与原模拟器结果仍待检查，未正式发布。

真实模型自主任务、真机以及 GitHub 远程操作仍未完成，不以 HTTP 回归替代。
