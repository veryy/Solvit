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

## 只读文件原语的远程复现

独立分支先加入固定根文件契约与6个实际AndroidWorkspaces/Robolectric回归：超过旧片段上限的完整UTF-8、同大小/mtime更新及删除、符号链接/目录/二进制/超限、精确字节上限、读后授权撤销和父取消。当前WorkspaceService默认“不支持”是预期红灯；后续实现只负责文件读取，规则组合仍留在JS/TS。这里没有声称API16插件桥或模型请求投影已交付。

[远程只读契约38400042395](https://github.com/veryy/Solvit/actions/runs/38400042395)实际6个新用例均失败，Kotlin编译成功；当前接口默认不可用未实现。补入固定名称、NOFOLLOW_LINKS、cap+1流读取、严格UTF-8、读取前后元数据核对和前后授权回调；取消向外传播，错误分类为UNAVAILABLE而非缺失。此原语不是原子文件系统快照，外部进程并发写入仍有平台边界。

[只读原语复测38400821619](https://github.com/veryy/Solvit/actions/runs/38400821619)成功，下载/CRC核对实际XML：新WorkspaceInstructionsTest六项0失败/错误/跳过，整个契约报告193项0失败/错误/跳过。源码`6a8b888`的[完整Android38400749477](https://github.com/veryy/Solvit/actions/runs/38400749477)构建/单元/Lint/签名/APK成功，完整模拟器仍在运行。工作尚未合入main或发布新APK，普通Chat Hook仍不能使用此接口。

下一必需落点为API16专用workspace.instructions：仅beforeModel，只接受空参数；通过原生捕获的ContextInput/当前会话绑定读取，不允许脚本选择工作区或路径；实时验证声明、启用状态、workspace授权、角色允许和绑定，读前/读后均验证。只读Hook不因此开放一般workspace.read/write/shell。接实际TS规则renderer和模型请求验证后再做候选完整门禁、包一致性、市场保留旧版本以及新APK；根规则原语不能替代嵌套触达、完整diff、完整任务和真实模型验收。

## API16实际Hook→Agent请求

只读原语候选完整Android38400749477已经全部成功。新增4项实际QuickJS/Marketplace/AndroidWorkspaces/ToolAgentRuntime回归。首轮38403026471四项在夹具服务缺失处失败（Missing or cyclic dependencies），不能当作缺失投影的有效复现；注册真实WorkspaceService后，38404345659成功走到实际Hook，三项读取/请求/撤权前置失败，越权参数和普通workspace.read拒绝用例通过。原断言与Hook预算保留。

实现专用workspace.instructions，API16和beforeModel限定、空参数、workspace声明及实时grant、包/启用状态、角色快照允许与当前绑定双重核验；原生ScriptHookInvocation捕获ContextInput，JS输入不含会话/工作区ID。读取前后与返回前验证，未绑定普通聊天返回available=false且不创建目录。一般文件/Shell桥仍要求原有工具确认。

TS模块负责固定根/同目录local排序、去重、来源及12KiB UTF-8渲染预算、失败/截断标记。其6项测试与SDK契约在38404477632、市场38404477572远程通过。尚未改内置工作区包或线上服务器，不能声称已发布规则功能；下一步同组native复测及实际TS包→Android→请求与原生协议组合。

[桥接复测38405522249](https://github.com/veryy/Solvit/actions/runs/38405522249)成功，下载实际XML四项0失败/错误/跳过。新增API15拒绝/API16仅Hook/无参数边界，随后38406370524成功；TS增加小预算及local优先后38406147308通过。实际包由38406778903远程生成，仅工作区变动，1.2.0/API16，31604 bytes，SHA `545abc7b667f31468777e378d4c7620709f9e2e03fca6f4c1b490e24bd4b0bbe`，所有其他包比较保持字节一致（包含可安装示例，不等于APK内置数量）。旧1.1.0完整字节27700，SHA `4afcd1206d15bb0f1fba79a93f0e623b0a6e29ff3172b54cadb833b9ea8471e4`保存为legacy，Android加入精确SHA升级路径。

提交远程包及API16目录之后38407531954 SDK/包一致性及38407531929市场测试成功。新实际TS包→Android规则开关/更新、读完后撤权、旧包升级及修改/卸载保留测试在f070090；Android38407723311与契约38407865703正在验收，不声称已通过或发布。服务器尚未部署API16；真实模型、真机、嵌套作用域、完整diff和任务验收继续保留。

实际TS包/读后撤权/升级回归已执行，38407865703与38407723311均只在旧BuiltinMemoryTest目录请求断言失败：测试仍预期hostApi=15，生产已请求16。同步断言，不移除目录参数检查或修改其他用例。候选版本改为0.25.0/build52，继续同组契约与完整Android；此处尚不宣称已发布。

[候选完整Android38409015788](https://github.com/veryy/Solvit/actions/runs/38409015788)构建、Lint与APK成功；下载XML实际358个单元测试0失败/错误/跳过。其模拟器在加入真实Android聊天验收后被同分支新运行取消，不记为完整成功。[契约38409125879](https://github.com/veryy/Solvit/actions/runs/38409125879)下载XML实际201项通过，其中原语6、真实Hook7和ScriptRuntime12。

新增真实APK的四次请求场景：超过6KiB的中文/emoji规则、修改、删除、解绑，检查角色系统消息不被覆盖、没有工具调用和Hook问题、旧规则不留在请求或持久聊天。首次38410727758仅在新测试JUnit断言参数顺序处编译失败；2d87a42修正message/condition顺序，保留断言。完整Android38411978563与定向页面38412047429正在执行。这些场景使用捕获请求的受控Provider，不能证明真实模型遵守规则。

[实际APK定向38412047429](https://github.com/veryy/Solvit/actions/runs/38412047429)成功；下载产物CRC通过，result-1实际OK (1 test)，四次模型请求全部通过。完整38411978563的358单元/Lint/签名/APK成功；UI首次未进入测试，Runner下载android-35系统镜像报Error on ZipFile unknown archive，按原条件仅重跑失败作业，未降低断言或将基础设施错误记为产品回归。

API16线上部署已完成，备份`/opt/solvit-market/.backup-workspace-1.2.0-1791901699222`，含旧server和工作区包；脚本含哈希前置、原子写入、health和旧目录比较及失败回滚。正常验证公网HTTPS API1–15完整JSON与部署前一致，API16仅工作区版本更新；工作区1.0.0/1.1.0/1.2.0公开下载分别5508/27700/31604字节，与Git包逐字节一致。新包SHA545abc7b667f31468777e378d4c7620709f9e2e03fca6f4c1b490e24bd4b0bbe，health ok。仍须完整模拟器及正式标签门禁后才能称为新版本交付。

完整UI重跑已进入测试，49项中工作区页面两个原用例失败：Shell回工作目录在ChatLayoutTest:755等待HTML就绪、WorkspaceHtmlPageTest:47首次打开等待就绪超时。源码确认BuiltinWorkspaceManager仍锁定工作区API12，新APK不可变资源已API16，因此页面构造拒绝；修正仅锁定当前工作区API16，终端API13保持，保留实际页面/原生确认/保存/过时文件及Shell断言。不是用延长超时或跳过页面来绕过。

工作区API16人工页面入口修正acebf13。[定向文件页38415161660](https://github.com/veryy/Solvit/actions/runs/38415161660)成功，下载CRC和result-1实际OK (1 test)，保存/原生确认/过时文件断言保留。完整38415492571实际XML49项中，仅原角色应用→首次发送失败，两个工作区页面和新增规则场景通过；角色fixture记录ready=true、busy=false、hasError=false、saved=0、outcome=null，截图文字保留且按钮可用。不能把此失败归因于项目规则或认为延长超时可解决。

新增临时[DEBUG-role-send]回调次数及触摸前后bounds探针，不记录文本/凭据，并给发送/停止同一按钮稳定testTag。原8秒与角色、请求、入库条件保留。56883ff的[同前置顺序十轮38418610354](https://github.com/veryy/Solvit/actions/runs/38418610354)成功，下载ZIP实际10个独立result-N均OK (3 tests)，总30项。原始失败仍未稳定复现；保留失败记录，不声称已修复角色症状。完整38418609997待验收。
