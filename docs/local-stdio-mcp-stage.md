# 本地 stdio MCP：实现与验收记录

起点：公开0.41.0 / `cd9c98c7`，Linux环境已可运行Python、Node/npm及Java。本阶段继续现有实现，不另造虚拟机。

## 实际阅读的上游

- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：`packages/mcp/mcp-client/src/transport.ts`、`connection.ts`、`tests/negotiation-lifecycle.spec.ts`。采用直接stdio、显式子进程环境、旧进程清理完成前不得启动新一代的约束。DSH使用Node MCP SDK；QuickJS没有Node进程API，不能直接引入该传输实现。
- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：`app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/MCPStarter.kt`及`app/src/test/java/com/ai/assistance/operit/core/tools/mcp/McpServerRegistrationTest.kt`。其本地连接使用终端中的Node桥；注册运行连接与向AI暴露工具是两个不同步骤，必须分别验证。
- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)：UTF-8逐行JSON-RPC，stdout只承载协议，stderr独立；不能拿PTY的回显、换行转换和交互提示作为stdio。
- 已核对DSH MIT、Operit LGPL-3.0及上述文件头；本阶段独立实现Android宿主适配，不复制上游源码或新增Node/Cordis运行时依赖。

## 宿主适配

既有普通Shell的stdin用于EOF退出看门狗，程序stdin为`/dev/null`，不能兼任MCP输入。既有PTY保持交互终端用途。新增原生stdio通道，stdin、stdout、stderr与宿主生命期控制分别持有独立描述符；进程组管理和回收不依赖JSON-RPC输入。

子进程只接收显式环境，不继承应用进程环境。调用者必须是宿主构造的启动计划，JS不获得JNI句柄或任意原生启动入口。子进程及其普通后代在会话关闭、启动失败或宿主控制管道关闭后回收。与已有Shell一样，同一Android UID的程序不是对恶意代码的安全隔离；主动逃离进程组的程序不属于此清理保证。

## 后续接入与验收

先远程验证真实管道、分离stderr、环境隔离、启动失败、并发关闭、进程组及后代回收，再接Ubuntu启动计划、插件授权/绑定、逐行边界与资源限额、多连接JS页面、MCP协商和AI工具路由。当前内部原语不代表本地MCP已向用户交付。

正式交付需要远程Node/Python MCP进程及取消/停用回收测试、旧HTTPS连接回归、完整Actions和可下载APK。真实模型与ARM64真机验收仍受既有条件限制；不能用确定性Provider替代真实模型结果。

## 首轮设备失败与修复

远程诊断[38727233386](https://github.com/veryy/Solvit/actions/runs/38727233386)，`946b9df2`：四架构原生编译及测试APK编译通过；API35任务通过，API26的9项测试均失败。API26产物11968294682已下载并检查ZIP CRC，设备logcat明确记录`SIGSYS / SYS_SECCOMP`，x86_64寄存器`rax=0x1b4`对应`close_range`。旧Android的seccomp直接杀死调用进程，不能依赖该调用返回`ENOSYS`后再回退。初版还把监督进程死亡导致的启动管道EOF误认成exec成功，失败测试实际捕获了不存在的命令也被错误接受的情况。

修复改用`/proc/self/fd`及旧版允许的`getdents64`/`close`，仅在fork后单线程子进程中遍历自身描述符表；不执行可能触发旧版seccomp的新调用。增加子进程显式就绪标记，然后才接受exec的CLOEXEC EOF，缺少标记必须报启动失败。继续由相同API26/35实际用例回归，不关闭或放宽Android权限限制。

第二轮`f8b432b9`，[38728185095](https://github.com/veryy/Solvit/actions/runs/38728185095)两任务均成功。已实际下载产物11969591375/API26与11969461342/API35，SHA256与GitHub元数据一致、ZIP CRC正常；XML分别为9设备用例和11 Linux运行时单元用例，无失败、错误或跳过。四架构编译成功不能替代ARM真机验收。

随后增加严格逐行UTF-8边界：保留跨读取的多字节码点、按字节限制单帧、拒绝非法编码/空行/未完成EOF，失败后不继续解析。Ubuntu生产环境探针增加Python和Node两个真实stdio JSON-RPC测试服务：初始化、工具发现、调用后写入共享工作区、独立stderr、显式环境和EOF退出。它们是确定性协议fixture，不是第三方MCP兼容或真实模型验收；实际结果如下。

### 后续实际取回的报告

- `62a4c315`，[38729523268](https://github.com/veryy/Solvit/actions/runs/38729523268)：API26/35各9设备、11 Linux单元、12系统执行单元全部通过，包含TERM忽略后的直接worker回收。两份产物11969673338/11969388476已下载、SHA256及CRC核对，实际XML无失败/错误/跳过。
- `f9bff109`，[38729749733](https://github.com/veryy/Solvit/actions/runs/38729749733)：应用和测试源码集成编译通过；产物11969249069已下载核对，实际473单元通过，覆盖core74/runtime84/workspace82/git12/storage41/chat66/market114。未运行本地构建。
- `9e4000ce`，[38729208323](https://github.com/veryy/Solvit/actions/runs/38729208323)：真实Ubuntu的3设备及11运行时单元通过；产物11969798780，35,472,172字节，SHA256 `eba9b885cf1dc5d49e7b2e8ea8830c3dd155bcf27dd97ad487fdd3efe8d09a6e`，已下载并核对元数据、CRC与XML。实际日志确认Python3.12.3、Node24.21.0/npm11.19.0、Java17.0.20.1，以及Python/Node各自的initialize/tools-list/tools-call/EOF/显式环境/共享文件结果。探针在同一生产安装流程中执行，不是桌面模拟。
- `afe83683`，[38730705181](https://github.com/veryy/Solvit/actions/runs/38730705181)：新增外部SIGKILL脚本的首轮失败在`run-as: unknown package: dev.solvit.terminal.test`，9常规设备测试已成功，强杀尚未开始。Gradle/UTP完成常规连接测试后移除测试APK；脚本需重新安装本次构建的唯一测试APK，并确认实际包路径，再启动外部强杀用例。此轮不能算宿主死亡验收通过。
- 修复后的`0d79682c`，[38731662908](https://github.com/veryy/Solvit/actions/runs/38731662908)：API26/35两任务成功，各9设备、11 Linux单元及12系统执行单元通过；另外各自实际SIGKILL一次JVM，记录的宿主、监督进程、worker及后代四个PID均消失，包含僵尸回收检查。已下载产物11971021756/API26（28,837字节，SHA256 `37626a69b91c0a6b1bcac043dcd1e0e0c247a5d475be8a614faa032fdf3b784e`）与11971081609/API35（29,229字节，SHA256 `f9903caf99f145aca418facf8d90c132d136aff3b7b531625bdbf0669d099b0e`）；元数据、CRC、实际XML、APK安装成功及identity/result四PID对应全部核对。此实际强杀不等于仅调用`close()`；Android自身的进程清理也参与宿主死亡后的整体结果，测试不单独归因于watchdog。

交付状态：`feat/local-stdio-mcp`已提交上述内部传输及证据，公开版本仍为0.41.0。下一步为插件授权/多连接页面/AI路由；尚未生成宣称支持本地MCP的新版本APK。当前MCP插件依然仅开放HTTPS。

## 插件桥下一步必须满足的约束

- Kotlin只持有平台进程/原始帧/取消/限额，MCP版本协商、工具发现、调用和页面继续位于JS。不得往QuickJS伪装注入Node全局对象。
- 命令来自用户保存的本插件加密连接配置。宿主独立读取、校验具名stdio配置；AI的工具参数仅选择已配置服务器，不接受模型临时塞入命令、环境、宿主路径或额外配置。页面试连接需要明确用户操作，不能保存即自启动。
- 原生入口需新版本API、服务声明及shell/workspace权限，不能借HTTPS连接旧network授权静默启动进程。JS不取得JNI句柄。既有HTTPS配置、旧包哈希、用户禁用、卸载、配置与授权需要保留。
- 连接属于插件版本、角色、聊天、工作区、服务器配置快照。每次操作重新检查启用/授权/绑定；停用、撤权、重绑定、角色变化、关闭及失败回滚都要停止对应进程。成功回合可复用连接；停止/失败回合遵从既有`ToolInvocationContext`/`AgentExecutionContext`资源所有权。
- 同服务器不允许旧进程未清理就启动下一代；不自动重放有副作用的调用。不默认启用无限重连。stdout待消费队列和stderr留存均需有上限；stderr及配置不能直接进入模型错误信息。
- 原生直接worker必须回收；普通后代发进程组信号停止，孤儿由Android init回收。设备用例要区分停止运行与僵尸回收，不能用只检查一个PID替代整个生命期断言。
- 最后验证多连接页面、Agent确定性路由、取消/进程崩溃/权限撤销/插件停用、宿主实际进程死亡和清理，再运行全套发布验收。内部原语通过不能更新README声称stdio已可使用。

## API30 宿主授权桥（开发中）

继续核对 DSH 同一固定提交的 `mcp-client/src/transport.ts` 和 `tests/server-context.spec.ts`：采用显式环境、作用域隔离与旧代清理，不引入 Node/Cordis 运行时。新增 `process.stdio` 生命周期服务与 API30 声明，具名启动参数只由宿主读取加密 `connections`；工具参数无法覆盖命令。连接绑定聊天、角色完整快照、工作区目录身份和配置摘要，操作与存活检查均重新核对。页面试连接使用宿主的一次性、120秒审阅凭证，保存配置不会启动；页面事件结束关闭试连接。工具写入核对实际确认的名称/参数并只允许一次，不自动重放。

`9c14baa5` 的[远程诊断38734403948](https://github.com/veryy/Solvit/actions/runs/38734403948)已报告两平台任务成功。报告将继续下载核对；新宿主桥、配置校验及页面确认的用例本次提交后远程执行，当前不能标为通过。JS MCP 插件仍是 HTTPS 版，API30 接口尚未作为成品发布。

`9c14baa5` 的两个实际产物已下载：API26/11972026096，35,085字节，SHA256 `c52e94df15e91a4e6b59ea5e03c84162a97753a4dd0d31c1be20de898e362cbf`；API35/11973275196，35,590字节，SHA256 `bb3a10a7b8a5690b9e32776caa5950a8354dc4dab36d7793875ecda9b0bdd301`。CRC与摘要一致；每个平台14设备、11 Linux运行时、12系统执行单元均无失败/跳过，另一次实际宿主SIGKILL后四PID全部消失。

本次加入 JS/TS MCP1.2.0 候选：HTTPS配置保持原格式，本地连接显式 `type:stdio`，命令、字面参数、环境、工作区相对目录经JS与宿主分别校验。MCP协商/分页发现/调用仍在JS，通过原生原始帧桥收发；按宿主不透明作用域隔离聊天会话，原生进程代次失效后重新协商，失败写入不自动重放。页面提供传输切换、显式stdio配置导入、保留或清除加密环境、一次性审阅测试。本次新增5项JS用例及API30构建声明用例，尚待远程执行及采用生成包；不能把候选源码视为发布。

API30宿主桥提交`01fb9c2f`的[远程编译及单元38737631108](https://github.com/veryy/Solvit/actions/runs/38737631108)成功。实际产物11973653992（107,913字节，SHA256 `a5a4fcb1f4f51fce713eed358c5e9817793e253577ea7af295bb8fa524524901`）CRC正常，480单元无失败；其中配置4项、桥3项覆盖确认参数替换/一次性调用、作用域、配置变化/撤权/重绑定、页面审阅与资源关闭。应用与设备测试源码编译成功不等于这些页面已在设备实际运行。

`96779391`的[插件生成38738839463](https://github.com/veryy/Solvit/actions/runs/38738839463)在市场单元失败：TS构建、typecheck及插件用例已通过，市场仍拒绝API30。修正市场版本支持，并保存1.1.1的原始包及历史目录排序；加入API10..29逐版旧包完整字节核对，API30才提供1.2.0。此失败未部署到线上。

市场兼容修复`82dfb8a1`的[38739463398](https://github.com/veryy/Solvit/actions/runs/38739463398)成功：TS类型检查、插件及市场用例、真实HTTPS MCP/Skills/搜索检查通过。生成包产物11975905525（865,745字节，SHA256 `afe413973b3ee5e2e2944f553a9c29a2748a85fa513fdc3068a0a78c28953061`）已下载核对摘要/CRC，15个包采用远程生成字节，10个既有APK资源同步。

补充兼容迁移：旧MCP的network授权不静默扩展成shell/workspace。既有原始旧包自动兼容升级只到保留的1.1.1，HTTPS继续工作，配置仍保留；用户明确安装1.2.0后需审阅新增权限。首次安装的1.2.0仍默认关闭。

新增生产Ubuntu探针：通过实际QuickJS内置包、市场授权桥、原生stdio和ToolAgentRuntime，用确定性Provider依次列服务器、发现工具、执行Python/Node文件写入并读取工具结果生成后续答复；检查第二聊天状态、停用后的两个进程回收。该验证需远程设备执行，通过后也仅是确定性Provider，不能替代真实模型/ARM64真机验收。

生成包采用后的`75d34951`契约回归失败于两个HTTPS集成用例：旧测试只注册Web服务并授予network，新API30包明确要求Linux/进程/工作区服务及额外权限。更新测试环境声明宿主能力，平台桩在任何HTTPS路径触发进程或Linux调用都会失败；继续真实TLS服务器/QuickJS请求与取消验证，并额外断言network单独授权不能启用新版。旧版自动升级测试保留network权限与1.1.1断言，未放宽生产权限门禁。

生产探针再补充实际页面未确认拒绝/确认发现/关闭后回收、等待中的工具取消、服务器崩溃不重放，以及Node连接不被Python故障牵连；这些设备检查尚未通过，待远程运行。

采用生成包的`75d34951`，[38740071929](https://github.com/veryy/Solvit/actions/runs/38740071929)再次通过TS/插件/市场、在线搜索/HTTPS MCP/Skills与提交包一致性检查。正式Ubuntu设备探针与全量APK验收仍在后续，公开版本保持0.41.0。

市场API30已在部署服务器的私有before/next快照实际验证：API1..29的目录内容全部保持，54个既有版本完整下载字节一致，API30提供MCP1.2.0。SSH读取超时后没有重启核对进程，重新观察取得已完成`verified.json`。尚未切换公网服务；本次版本号准备0.42.0/build79，正式设备及全套发布门禁尚待通过。

下一轮`d37b3933`只剩双HTTPS Agent回归失败，发现实际兼容问题：Agent原先把所有声明workspace/shell的工具隐藏于未绑定聊天，新MCP同时支持HTTPS/本地，导致普通聊天也看不到HTTPS工具。新增独立的工具可用性字段`requiresWorkspace`，默认保留原行为；声明原生stdio的混合插件允许先列服务器/使用HTTPS。本地启动仍由宿主要求捕获绑定，并未放宽进程授权。既有双TLS/实际Agent无工作区用例作为回归，工具策略摘要包含此字段变更。

## 实际QuickJS/Agent链路证据

`75d34951`的[Ubuntu38740072541](https://github.com/veryy/Solvit/actions/runs/38740072541)成功，实际产物11975683743（37,915,103字节，SHA256 `803c48028c066fad1b04a1ee0cc0ff2d7b8825adcb3c6cf2e1b76a5e9497da85`）已完整下载核对CRC/摘要，3设备与11 Linux运行时单元无失败/跳过。设备日志分别有Python和Node的`STDIO_MCP_AGENT`标记，经过实际QuickJS包、宿主授权、发现、调用、后续模型步骤与共享文件；停用后两进程回收及其他聊天未连接也通过。Provider为确定性测试实现，不是外部模型。

HTTPS普通聊天修复后的`1536823e`，[契约38742300979](https://github.com/veryy/Solvit/actions/runs/38742300979)成功，实际产物11975499486（108,314字节，SHA256 `1ebe184adf9b815813657424e40fdc19973284606e320445244d1e3f6d1ad0e4`）CRC/摘要正常，481单元（core75/runtime84/workspace82/git12/storage41/chat66/market121）均无失败/跳过；包含无工作区双HTTPS Agent回归和旧MCP配置/授权兼容，应用及设备源码编译通过。附加页面/取消/崩溃场景仍待新一轮Ubuntu设备结果，尚未正式发布。

附加场景`1536823e`的[Ubuntu38742300168](https://github.com/veryy/Solvit/actions/runs/38742300168)成功。产物11976855995（37,931,917字节，SHA256 `efc9cbfdad0427687f83e1e2acd2e836edd881d9ad55daf898a203d1131b441d`）实际下载、CRC/摘要、3设备与11运行时XML均核对通过；日志包含页面未确认拒绝/确认发现后回收、等待中取消、崩溃不重放、独立Node存活、停用回收及聊天隔离。API26/35底层实际SIGKILL证据另见前文，未用普通close替代。

代码已快进合入main，正式标签`v0.42.0`指向`3164c98e270b9dd914aa52d461be7dfbb38a89f4`；[完整正式流程38743958374](https://github.com/veryy/Solvit/actions/runs/38743958374)已启动，插件与搜索任务成功，完整APK/Lint/UI/Linux仍在进行。当前不能宣称新APK已发布。

## 正式构建外部依赖失败及修复

`v0.42.0`的38743958374失败于两个任务下载talloc2.5.0：Samba主站与官方镜像多次curl28超时/56连接中断，尚未编译App或运行正式设备任务。插件、市场、搜索已通过，不能因此声称APK通过。未发布0.42.0，不移动既有标签。

从已完整校验的0.41.0公开源码包提取未经修改的上游talloc源归档（690,172字节），摘要仍为既定`912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`。将它保留在`third_party/build-sources`，构建脚本优先读取，仍强制摘要验证、NDK逐架构源码构建并随对应源码ZIP分发；没有引入预编译执行器。本地仅做摘要、归档读取及bash语法检查，未运行构建/测试。准备0.42.1/build80重新走完整门禁，保留失败标签和日志。
