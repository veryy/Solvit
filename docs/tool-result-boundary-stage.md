# 接受工具结果后再释放失败清理（0.22.8 候选）

## 原故障与远程复现

原 `ToolRegistry.executeCall` 在工具返回时立即释放调用级取消回调；随后 Agent 才检查返回字符串是否超过256 KiB。超限虽被 Agent 记为 `RESOURCE_LIMIT` 并回传，但已登记资源的取消动作丢失，模型后续步骤开始时仍未清理。

先提交真实注册器与 Agent 的回归 `d16b9e3b3aa6ba40b80309ece06dc58fcd369c7d`。新增手动 `Agent core regression` 工作流，在注册器测试失败后仍执行完整 Agent 测试、保留两个层次的实际报告，整体失败仍失败。此诊断入口不替代完整 Android 发布门禁。

源码 `f3c283fb959bc2f1ae474361be0c0c17d041966a` 的 [远程红色复现38353211619](https://github.com/veryy/Solvit/actions/runs/38353211619) 实际 XML：注册器2项因为直接放行超限结果而失败；Agent的 `oversizedToolResultCancelsOwnedResourcesAndReturnsOnlySafeFailure` 失败在“模型后续步骤开始前资源应已取消”。同一用例在这个断言前已确认正确调用ID、FAILED状态和RESOURCE_LIMIT，以及请求中没有原始内容标记。因此定位为验证太晚，而不是Agent没有记录失败或工具未登记资源。测试响应只含构造的标记/重复字符，不是真实用户数据或模型回答。

## 修复与适用边界

保留原256 KiB UTF-8上限，移到注册器自己的成功返回与释放回调之前。超限抛出宿主 `ToolExecutionException(RESOURCE_LIMIT)`，沿原异常路径取消登记资源、逆序清理，再释放回调；Agent沿用安全失败回传和下一步建议。原始超限结果不进入模型消息、聊天转录或错误详情。Agent重复的晚检查移除，所有调用同一注册器的入口遵循一致边界。

先判断字符长度，超过上限立即拒绝；字符长度未超限才计算UTF-8字节，避免明显过长返回值的额外整份字节分配。没有缩小既定限制；精确256 KiB可成功，增加一个字节失败。成功调用继续释放取消回调而不取消其资源；工具超时、外层期限和用户停止的现有语义保留。

这只清理工具按调用契约登记的资源，不能回滚已发生的文件写入、网络副作用或任意未登记进程。RESOURCE_LIMIT建议先核对实际状态，不自动重复有副作用的操作。取消清理仍为协作式，不能承诺任意原生/NonCancellable回调的绝对墙钟上限。

## 上游依据与Android适配

再次读取锁定 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`（MIT）的 `agent-loop/src/tool-calls.ts`：finalize/finish接受结果后再记录tool/result与附加上下文，失败/取消排空已开始调用；`tests/tool-calls.spec.ts` 的failure-quiescence用例观察prepare/finalize失败后的已执行事实和恢复结果。采用结算完成前保留失败处理能力的原则，没有复制调度器或声称实现并行DSH内核。

读取锁定 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（LGPL-3.0）`StandardTerminalCommandExecutor.kt` 的流结束输出、退出码/超时结算；对应Solvit的Shell/PTY登记同时受调用与整轮所有权管理。注册器结果检查在Android无关的core实现，平台任务关闭沿既有授权服务执行。未复制上游代码或引入许可证依赖。

## 验证状态

修复 `7122fec3bd6ad2648f0d775792b1f1ab109e2941`，build44/API13，[聚焦回归38353622842](https://github.com/veryy/Solvit/actions/runs/38353622842) 实际2个注册器/11个Agent测试均通过；[完整Android门禁38353550176](https://github.com/veryy/Solvit/actions/runs/38353550176) 成功，实际XML324单元、48模拟器，0失败/错误/跳过。Lint、固定签名和APK通过；上一版工具超时、外层期限、用户停止，以及角色设置、HTML/PTY、受控可见流式和覆盖升级保留配置/聊天继续通过。

[APK产物](https://github.com/veryy/Solvit/actions/runs/38353550176/artifacts/11791399713) 23,521,972 bytes，SHA-256 `1db1fd2d776e25d576832c71bc4572a10c203ae3073d052a794ddd12a84a44ab`。ZIP/APK CRC通过，8个内置插件资产与源码一致。签名证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`，`gradlew`可执行权限保留；本阶段未修改服务器和插件协议。

真实模型、真机、任务完成与完整JS内核替换均未被这项机制回归替代；公开搜索仍未通过发布门禁，本版为候选，正式Release仍为0.21.3。完整范围见 [产品路线](product-roadmap.md)。没有本地Android或TS构建/测试，也没有保留临时调试日志。下一机制阶段应让JS实际组织Agent模型步骤/工具回传并保留这些宿主边界，不能仅增加一个调用原生Agent的代理接口就宣称JS内核完成。
