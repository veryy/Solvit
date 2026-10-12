# 工作区执行证据阶段

基线为已发布0.20.0；分支 `feat/workspace-evidence`。完整目标是项目规则、可靠读写、差异审阅、Shell退出码/输出、执行后验证与持久步骤日志。采用“创建文件→运行失败→修复→再运行验证”的实际任务，不把工具启动或测试数量当作整个任务验收。

## 上游实际源码与测试

六个锁定checkout已再次通过 `scripts/fetch-upstreams.py --verify-only`（源码核对，不运行上游应用）。读取DSH `packages/core/agent-loop/src/agent.ts`、`tool-calls.ts`、`tests/cancel.spec.ts`、`tests/resume.spec.ts`，以及 `packages/deliverables/workspace-changes/src/recorder.ts`、`tests/capture.spec.ts`、`tests/current-directory.spec.ts`。读取Operit `core/tools/defaultTool/standard/StandardTerminalCommandExecutor.kt` 的会话查找、结束输出、退出码和取消结算。采用每步日志、写前捕获、失败/取消记录与资源归属原则；没有复制上游实现，本阶段代码为Kotlin适配，不引入Cordis/Node。

## 第一检查点：即时持久步骤日志

新增可替换ChatStore的可选执行日志协议；不支持的存储明确不提供日志，原聊天接口仍可用。生产SQLite迁移7→8，独立的执行轮次与顺序事件表保留聊天、后台记忆任务及签名转录。轮次绑定实际用户消息、角色与工作区快照；只有当前用户消息能启动，日志不会复制到聊天分支。删除对话级联删除日志。

ToolAgentRuntime在每次步骤开始、工具批次提出、授权后执行前、结果返回后以及步骤结束同步写入。写入失败则终止后续副作用；日志按预期序号及运行状态比较，拒绝重复ID、陈旧写入、缺失开始或提前结束。记录保留工具身份、插件归属、参数和结果的有界脱敏片段；不保存模型配置、系统提示、思考/签名原文。片段不改写实际工具参数/模型收到的结果，也不作为可重放消息。每轮最多64步/64调用/512事件，参数16KiB、结果64KiB，明确截断标志；大JSON使用字节分块读取以避开CursorWindow。

应用初始化将遗留RUNNING轮次标为PROCESS_INTERRUPTED，已记录事件保持原样。不编造成功/失败结果，不自动重跑工具；已开始但未记录结果的调用需要后续界面显示“结果未知，先检查实际文件/外部状态”。打开一个只读观察实例不会触发恢复，避免误停活跃轮次。

新增远程回归：严格步骤/调用边界、真实SQLite跨实例即时可见与恢复、旧版迁移/级联/分支、UTF-8和大JSON读取、真实Runtime授权/副作用/后续模型之前的持久边界、存储失败零副作用、取消无假结果、密钥脱敏和UTF-8截断。尚未运行，不宣称通过。

后续必须接聊天中的折叠执行详情与中断恢复展示，安全的失败分类/建议、文件差异和实际退出码/验证证据；完成JS工作区插件→原生文件/Shell→SQLite的完整任务与模拟器回归后再发布新APK。当前0.20.0公开版本尚不包含此检查点；真机、真实模型执行和完整AndroidAgent验收仍未完成。

首轮Android `38203150234` 的实际失败是新增测试编译：`ExecutionJournalRuntimeTest.kt:109..110` 对跨模块可空 `ExecutionEvent.result` 的隐式smart cast不成立。改用局部非空片段，保留全部密钥/字节上限/UTF-8断言；尚未执行到该测试，不能称为业务运行失败或通过。

## 第二检查点：同一聊天内的折叠恢复详情

ChatService暴露读取接口，UI不依赖SQLite实现。按原用户消息绑定到同一个助手气泡；进程中断而尚无已结算回复时只补展示行，不创建模型消息或自动执行。默认折叠，展开才读取有界事实；已记录结果、未开始调用与已开始但结果未知的调用分别显示。详情带安全错误及截断提示、支持六种宿主语言，不把“工具已返回”写成任务已验证完成。运行状态COMPLETE仅表示回复已结束。

SQLite日志有独立失效通知，避免每次日志写入都重读完整聊天历史；元数据带事件计数，即使毫秒时间相同也可通知打开的详情。新原生模拟器回归使用真实AndroidSQLite关闭/重开、启动恢复与聊天折叠，检查只读查询、已完成事实、未知结果和单一气泡。尚待远程运行；文件差异、失败分类和完整工作区任务还须完成。

## 工作区完整链路回归（待远程执行）

新增实际打包JS工作区插件→QuickJS→AndroidWorkspaces文件读写与Linux原生子进程→DefaultChatService→SQLite步骤日志回归。fixture Provider仅决定调用顺序：写入有错脚本、创建Shell、运行并读取退出码7/输出、修改脚本、再次运行并读取退出码0/输出，最后检查实际文件；确认发生时先查询已有durable提议。重开SQLite核对两次真实退出码与35个执行事件。模型选择为受控fixture，文件/进程/JS/SQLite为真实实现，不能冒充真实模型或Android设备执行验收。

Shell宿主结果补充实际cwd、workspaceId和执行mode，原API4新增结果字段保持兼容；启动成功仍只表示已获得任务ID，必须用status的终态/退出码与文件内容验证结果。后续还需文件差异、失败分类和设备任务回归。

UI候选 `f9cf32e` 的Android38203674752实际失败于生产SQLite编译：`ExecutionJournalDatabase.kt:64` 的 `arrayOf(now,runId)` 被当前Kotlin推断成交叉类型。按SQL绑定参数的实际Object[]接口明确为 `arrayOf<Any>`；不改SQL、序号检查或事务语义。此前的测试编译问题已通过，此运行仍未完成单元或模拟器验收。

补齐展示边界：已停止/失败回复里缺失工具结果不再误显示“等待确认”，而显示“未记录结果”；详细日志再区分未开始与结果未知。脱敏识别完整JSON字符串（含转义引号/反斜线），新增实际Runtime回归确保不残留凭据后半段，同时保留工具真实参数/模型收到的结果。

`28dd8e8` 的Android38204230919已编译到App，但完整任务fixture初次启用工作区插件失败。下载实际XML（11727260145）：`Missing or cyclic dependencies: dev.solvit.workspace`，来自PluginManager.enableAll。优先核对fixture服务注册、再核对包依赖及启用状态；实际ScriptToolPlugin声明需要WorkspaceServiceKey，而fixture只构造AndroidWorkspaces没有注册。通过fixture原生服务插件注册同一真实AndroidWorkspaces及其执行服务；不更改生产依赖、不删启用或实际执行断言。该报告同时确认日志核心2个、Runtime6个、SQLite5个新增回归全部通过；整条构建仍失败，模拟器未执行。

即时持久检查点会在ToolRegistry.onAuthorized中挂起。原实现只在回调前复查工具实例/执行模式/拒绝策略，等待期间撤销可能过期。补充回调后、实际调用前的取消/实例/模式/策略复查；新增受控等待中卸载与取消的零副作用回归。未扩大权限或重放操作，等待远程验证。

`afaa77b` 的Android38204703757再次失败于完整任务：XML11726693119显示step2读取 `start-broken` 时不是JsonObject；第一步文件写入和Shell创建已通过。按优先级核对工具FAILED回传（而非直接容忍双重JSON）、执行模式、宿主绑定。源码确认fixture的ToolsPlugin默认enforceSystem=false，描述符mode=null，而AndroidExecution.authorized必须与实际APP模式一致；生产App使用enforceSystem=true。fixture改用同样模式服务门禁，并在授权时断言APP、解析前断言工具成功。保留退出码7/0、工作目录、文件内容和重开记录断言，等待远程证据验证该原因。

## 当前远程证据（ef0654d）

[Android38205216200](https://github.com/veryy/Solvit/actions/runs/38205216200) 的build114667086311已success，测试/Lint/APK及固定签名检查通过；模拟器114668047384仍在准备，尚未通过。下载实际报告11727337378，254个单元测试、失败/错误/跳过均零。日志Runtime6个、核心2个、SQLite5个、检查点撤销2个及聊天投影6个均实际执行；完整JS工作区任务1个通过，验证真实文件内容、Linux退出码7→修复→0及重开后35个事件。fixture服务/模式接线原因得到此回归支持。

这仍不是正式版本发布，也不是Android设备/真实模型验收。尚需读取模拟器实际结果、完成文件差异/安全失败详情和设备完整任务，然后以新版本通过标签完整门禁并发布APK；公开最新版仍0.20.0。

## 文件写入前后证据

再次读取DSH `workspace-changes/src/capture.ts` 与 `tests/capture.spec.ts`，采用有界读取、缺失/二进制/超限区分与不可变内容身份原则，独立Kotlin适配，没有复制Node实现。WorkspaceService增加可选writeObserved，替换存储不支持观测时明确UNAVAILABLE/verified=false；生产文件写前同锁捕获、AtomicFile写入、实际重读并校验目标内容SHA-256。最多读取256KiB+1，前后可查看片段各6KiB，正确UTF-8截断；超限旧文件不伪装缺失/未改变，changed为null。写后异常不能声称写成功已验证。

JS工作区write_file返回实际前后观察，完整任务回归检查首次缺失→错误脚本、错误脚本→修复脚本，模型和持久日志都可看证据。新增原生回归覆盖新建/替换/不变、片段不可变、规范路径、二进制/超限/UTF-8、目录与越界拒绝。当前是每次文件工具写入证据，不是整个项目快照或Shell所有变更；后者继续作为本阶段工作保留。远程验证待运行。

## 即时日志与聊天恢复完整远程门禁

前一提交ef0654d的[Android38205216200](https://github.com/veryy/Solvit/actions/runs/38205216200)已completed/success，不是被新提交取消。下载模拟器实际产物11727815159：40个测试（app31/web1/terminal8），零失败/错误/跳过；`ExecutionJournalUiTest.interruptedObservationsReopenInsideOneReplyAndNeverInventMissingResults`确实执行并通过。SQLite关闭重开、折叠读取、结果未知和同一气泡显示得到Android模拟器证据。此前254个单元、Lint/APK和固定签名成功结论仍适用于该提交。

新文件证据5a50eec的Android38205978852/build114669453926当前in_progress，不能沿用旧绿灯作为新行为验证。尚未发布新版本；完整工作区差异、错误详情、设备任务与真实模型验收继续保留。

## 工具失败的安全分类

对照DSH `agent-loop/tests/tool-calls.spec.ts` 的结构化失败/未知结果及Operit `StandardTerminalCommandExecutor.kt` 的退出码/超时/失败表示，新增宿主定义ToolFailureCode及固定下一步建议。原生文件能力在具体检查处区分不存在、非普通文件、越界、超限与写后结果未知；通用错误只按异常类型给安全分类，不回显任意异常文本。模型工具结果、折叠详情与持久日志得到同一份安全信息，用户拒绝与协程取消保留原语义。

QuickJS使用每次原生调用的宿主侧失败记录传递类型，不接受JS随意指定错误代码，不跨调用继承；业务可以捕获失败自行返回，成功仍按其返回处理。新增实际QuickJS回归核对原生类型、成功后清除、伪造code无效，Runtime回归核对模型和活动信息一致且不含原始异常秘密。改动待远程验证，不宣称已有APK包含。

## 工作区范围扫描与比较

再次阅读DSH `workspace-changes/src/types.ts`、`src/capture.ts` 与 `tests/compare.spec.ts`，采用前后快照、内容身份及不完整扫描不可推断缺失原则。新增原生可替换snapshot接口，默认不支持返回complete=false；生产扫描当前工作目录及子目录，最多64文件/512目录项/12层/2MiB读取，每文件上限256KiB、预览512字节。符号链接不跟随；超限、权限错误、扫描期间可检测的内容变化均明确未完整观察。它不是原子项目快照，外部并发Shell仍可能改文件，不声称具备Git树一致性。

比较记录新建、修改、删除或UNKNOWN；只有对应扫描完整才把缺失路径当缺失，未读超限内容即使大小相同也不能认为未变。内容证据使用真实SHA-256，不按模型描述生成。新增核心比较/边界回归与真实Shell写改删→扫描回归，保留前后片段不受后续编辑影响、链接拒绝和扫描上限检查。尚未接入步骤日志、聊天审阅或轮次开始/结束扫描，不能称完整工作区差异已交付。远程验证待运行。

## 文件证据与失败详情的完整远程结果

[Android38206664685](https://github.com/veryy/Solvit/actions/runs/38206664685) 实际completed/success，build114671557796与UI114672646052成功。下载实际单元产物11727063955：259个测试、失败/错误/跳过均零；FileWriteEvidence3个、完整JS工作区任务1个、失败详情1个及ScriptRuntime11个确实执行。下载实际模拟器产物11727393648：40个（app31/web1/terminal8），失败/错误/跳过均零。Lint、APK与固定签名/覆盖升级门禁通过；这不是正式Release。

该运行终态后才推送新扫描提交0f8296f，避免取消尚在运行的模拟器。新Android38207613389当前in_progress，尚不宣称扫描/比较回归通过。还需授权后扫描接线、持久前后记录、聊天差异审阅、设备任务及新版本标签发布；公开最新版仍0.20.0。
