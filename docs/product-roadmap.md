# Solvit 产品实现进度

目标是以 JS/TS 插件为主要扩展方式的 Android Agent 产品。原生 UI 与系统能力由 Kotlin 宿主提供，插件通过稳定服务协议组合。以下是完整目标，不以当前已交付功能代替最终范围。
当前（2026-10-13）API14候选0.23.1/build46在0.22.3 HTML页面/PTY和0.22.4资料分层基础上隔离可选Hook失败、共享读取等待期限，并丢弃卸载/替换的旧资料；用户停止继续传播，失败状态可在最近请求诊断查看。原失败复现与真实QuickJS/HTTP证据见[可选资料阶段](optional-plugin-context-stage.md)，335单元/Lint/固定签名APK与模拟器任务通过[运行](https://github.com/veryy/Solvit/actions/runs/38365614238)，48个模拟器测试及实际诊断截图已核对。普通有限命令和无工作区入口保留原生兼容，终端长提示/控件密度仍需收整。正式Release仍受公网搜索发布门禁阻挡。真实模型角色、可见流式、工具选择、实际任务完成和真机验收未被机制测试替代，以下完整路线保留。


0.22.6 增加实际 APK/角色设置/SQLite/QuickJS 的[真实模型验收入口](real-model-acceptance.md)与文本接收段数诊断。常规317单元/48模拟器、Lint、固定签名APK已通过并核对；新验收类已编译但缺少新的模型凭据，尚未运行。终端Ctrl+C用例按前台任务实际启动和提示符回归同步，原断言保留。不能把受控流式或角色机制回归视为真实模型答案验证。

0.22.7 修复[工具注册器自身期限误取消整轮](tool-deadline-stage.md)：TIMEOUT安全失败回传、资源清理、模型下一步与用户停止分别结算；321单元/48模拟器、Lint、签名/APK成功。公网重新验证英文/中文正文通过、最新版遇人机验证，区域回退仍失败；验证器已保存全部查询结果与导航失败，[搜索复核](browser-search-stage.md)保留原门禁。单次边界修复不等于真实模型或完整JS内核验收，以下路线保持。

0.22.8 完成[工具结果接受与失败清理边界](tool-result-boundary-stage.md)，两层远程红/绿回归及324单元/48模拟器、Lint/签名/APK成功。接下来机制改造应让JS实际组织Agent步骤、流式事件与工具结果，保留宿主授权、资源、持久记录和原生协议签名；只包一层调用Kotlin Agent的代理不能算JS内核完成。真实模型/搜索/任务/真机验收继续保留，以下完整路线不缩减。

0.23.0 完成[包内JS Agent步骤驱动](javascript-agent-stage.md)：Android强制依赖JS服务，JS实际发起每次模型/工具/结算命令；宿主校验顺序、权限、资源和原生转录，流式事件实时送达UI。329单元/48模拟器、Lint、固定签名APK成功。仍非第三方Agent插件协议或完整JS内核，Provider/存储/上下文投影仍原生；接下来继续声明/授权的Agent扩展协议与TS契约、服务生命周期和配置组合。真实模型、搜索、实际任务及真机验收保留。

0.23.1 完成[可安装JS/TS步骤策略](agent-plugin-stage.md)：API14声明、agent权限、默认关闭且仅宿主可写配置、显式选择/冲突、登记资源取消与完整轮次清理等待。TS示例已由远程生成并部署市场，旧API1–13目录保持。335单元/48模拟器、Lint、固定签名APK及84 SDK/2市场通过。仍是有界步骤命令，不是完整JS内核；接下来继续会话/角色选择、消息/模型流与服务配置协议及效果验收，完整路线不缩减。

## 当前优先方向（2026-10-12）

自动记忆 [0.19.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.19.0)，[完整标签门禁通过](https://github.com/veryy/Solvit/actions/runs/38190322667)：213 个单元、33 个模拟器测试及 Lint/APK/固定签名/TS/公网搜索。后台保存默认关闭，须明确授权和开启；真实模型与真机验收待完成，具体边界见 [阶段记录](automatic-memory-stage.md)。插件自带页面已在 [0.20.0](https://github.com/veryy/Solvit/releases/tag/v0.20.0) 交付；当前继续工作区执行、错误修复、验证和持久事实审阅。真实模型与真机验收保留，不以机制通过代替。

依据 [DSH、Operit、Claude Code 与智谱源码/官方文档对照](agent-direction.md)，先完善执行链和实际任务验收：聊天稳定性与角色/流式 → 搜索闭环 → 上下文预算与自动记忆 → 工作区执行及验证 → 手机操作。更多模型协议暂缓，已有长期范围继续保留。

0.15.2 已分离事件接收与展示，角色身份只注入一次；[153 个单元测试、Lint、APK、签名和模拟器检查通过](https://github.com/veryy/Solvit/actions/runs/38158456320)。0.15.3 新增实际请求和接收/显示耗时诊断，[远程验证通过](https://github.com/veryy/Solvit/actions/runs/38160317381)。已实际克隆六个参考仓库并锁定版本，阶段参考规则与证据见 [源码参考](upstream-reference.md)。[0.16.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.16.0)：159 个单元测试、27 个模拟器测试、Lint、APK、签名、TS/市场、公网来源与官方版本时效检查通过，详见 [阶段记录](browser-search-stage.md)。上下文预算、自动记忆及真实模型/真机验收继续推进，远程通过不能替代这些验收。

[0.17.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.17.0)：已保存记忆自动检索、原生开关、每步上下文刷新与 API 7 非密钥配置；[标签验证](https://github.com/veryy/Solvit/actions/runs/38167779375) 的单元、模拟器、Lint、APK、固定签名、TS/市场和公网搜索通过。具体边界见 [记忆阶段记录](memory-context-stage.md)，后台自动抽取、全请求预算与摘要仍未完成。

[0.18.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.18.0)：全请求预算、输出预留、压力触发的工具结果投影裁剪与持久历史摘要；[标签验证](https://github.com/veryy/Solvit/actions/runs/38172527497) 通过 179 个单元及 31 个模拟器测试、Lint、APK、固定签名、插件和公网搜索。后台长期记忆抽取及真实模型摘要质量仍待完成，见 [上下文阶段](context-budget-stage.md)。

## 当前验证

- 0.1.0：基础文本聊天、流式取消、多会话、加密密钥设置；15 个单元测试、Lint 和 APK 构建通过 GitHub Actions。
- 0.1.1：更新检查与顶部卡片已发布；对应 Actions 测试、Lint、APK 和 Release 发布成功。
- JS 插件宿主和原生市场：测试、Lint 和 APK 构建通过 [Actions 38103439870](https://github.com/veryy/Solvit/actions/runs/38103439870)。QuickJS 使用 compileSdk 36。0.2.0 已发布 APK。
- 0.3.0：模型工具调用主体、SQLite 数据迁移与 HTTP→QuickJS 集成测试通过 [Actions 38104490115](https://github.com/veryy/Solvit/actions/runs/38104490115)，[0.3.0 已发布 APK](https://github.com/veryy/Solvit/releases/tag/v0.3.0)，[标签构建与发布通过](https://github.com/veryy/Solvit/actions/runs/38104671862)。
- 市场服务已部署 HTTPS，目录、下载校验测试与健康检查通过；当前目录包含时间与纯 TS 编写的记忆工具；记忆包已部署，服务器目录与下载校验测试通过。
- 0.4.0：TS SDK、记忆插件、离线安装与宿主集成已实现；[Android Actions 38105286718](https://github.com/veryy/Solvit/actions/runs/38105286718) 和 [TS/市场验证 38105244171](https://github.com/veryy/Solvit/actions/runs/38105244171) 通过，[0.4.0 已发布 APK](https://github.com/veryy/Solvit/releases/tag/v0.4.0)，[Android、TS、市场验证与发布通过](https://github.com/veryy/Solvit/actions/runs/38105586294)。
- 0.5.1：TS MCP、加密插件配置、声明式配置页、HTTPS 取消通知与取消后插件复用已发布；[45 个 Android 测试、Lint、APK、TS、市场、实际公网 HTTPS MCP 与发布通过](https://github.com/veryy/Solvit/actions/runs/38107628413)，[下载 0.5.1 APK](https://github.com/veryy/Solvit/releases/tag/v0.5.1)。0.5.0 标签被新增取消回归阻止发布，修复保留在后续提交中。
- 0.6.0：Skills 文本包导入/导出、角色绑定、只读 JS beforeModel Hook 已发布；[55 个 Android 测试、23 个 TS 测试、Lint、APK、市场、公网 MCP/Skills 与发布通过](https://github.com/veryy/Solvit/actions/runs/38109369009)，[下载 0.6.0 APK](https://github.com/veryy/Solvit/releases/tag/v0.6.0)。市场已上线含 ISC 署名的 Skills 1.0.1，旧客户端过滤验证通过。
- 尚未验证真机安装、文件选择和真实模型与 Android 工具的完整联动。
- 已验证旧发布包证书实际不一致：0.1.1 为 `38e4509c12faf87d88884164d3ff6c3917c56f5084c9b491147abd567462c0a1`，0.6.0 为 `2e19f1533b57158d82700f975cee433b6cd6dc64effa6f210a7c7b8431a8be66`。0.7.0 明确指定 CI 密钥路径，并强制校验固定证书；旧版无法承诺覆盖安装。正常同签名覆盖保留数据，卸载后的配置需预先导出的备份恢复。
- 0.7.0 设置分组、主题、模型列表/订阅 JSON 获取、应用内更新/进度/说明、密码加密配置备份、每轮时间上下文已发布；[73 个 Android/JVM 测试、26 个 TS 测试、2 个市场测试、Lint、APK、证书指纹、公网 MCP/Skills 和发布通过](https://github.com/veryy/Solvit/actions/runs/38112413032)，[下载 0.7.0](https://github.com/veryy/Solvit/releases/tag/v0.7.0)。公网 API 1/2/3 目录和全部包字节与仓库一致。

- 0.8.1 与 0.9.1 已发布：工作区、多 Shell、AI JS 插件草稿与原生审阅安装、白底线条图标和目录浏览。0.9.1 修复兼容网关空结束原因，并加入按插件选择 App/Root 身份；[94 个单元测试、6 个 Android 35 模拟器测试、TS/市场、Lint、APK、签名和发布通过](https://github.com/veryy/Solvit/actions/runs/38121549711)。真实 HTTPS 普通聊天、工具请求与结果回传后回复通过；模拟器实际 JS→Android Shell、覆盖更新后的加密配置和旧聊天保留通过。Root 真机及完整真实模型手机交互仍待验证。

- 0.10.0：原生 Android PTY、JS/TS 终端插件、输入/Ctrl-C/关闭、身份与资源回收已发布，通过真实模拟器检查。
- 0.11.0：原生 Markdown 富文本、独立折叠思考、同一回复内的工具详情、按需工作区已发布；[102 个单元测试、18 个模拟器测试、Lint、APK、TS/市场和签名检查通过](https://github.com/veryy/Solvit/actions/runs/38128744679)。
- 0.12.0 源码：聊天分支服务插件、原子 SQLite 快照、编辑旧问题和回复分支已提交；[109 个单元测试、模拟器界面检查、Lint、APK 和签名通过](https://github.com/veryy/Solvit/actions/runs/38130101183)。发布入口见 [0.12.0 Release](https://github.com/veryy/Solvit/releases/tag/v0.12.0)。

- 0.13.0：六种界面语言、角色兼容指令合并、实时流式显示、ANSI 终端、按角色和工具版本记住授权、联网搜索已发布；[119 个单元测试、23 个模拟器测试、Lint、APK、TS/市场、公网 MCP/Skills 与发布通过](https://github.com/veryy/Solvit/actions/runs/38136742535)。
- 0.14.0：多组加密模型配置和聊天/工具后续轮次分别选择模型已提交，[123 个单元测试、24 个模拟器测试、Lint、APK 和签名通过](https://github.com/veryy/Solvit/actions/runs/38138273585)，覆盖配置迁移、备份序列化、双 HTTP 服务路由与原生设置操作。多协议、本地推理及其余清单继续推进。

- 0.15.0：Claude 原生协议、Provider 注册/路由、原始签名转录、独立模型目录和声明式协议设置已实现；[145 个单元测试、26 个模拟器测试、Lint、APK 和签名通过](https://github.com/veryy/Solvit/actions/runs/38144065045)，包括连续工具回传、跨协议任务路由、长记录重开/分支、协议参数持久化与旧 APK 覆盖更新。真实 Claude 账户和真机仍待验。
- 0.15.1：按用户优先级暂停 Gemini，先补齐默认/自定义角色身份、逐字显示、语言弹窗、插件网格与免配置公开搜索；[Android 测试、Lint、模拟器、覆盖升级和签名验证通过](https://github.com/veryy/Solvit/actions/runs/38152202018)，[TS 与公网搜索验证通过](https://github.com/veryy/Solvit/actions/runs/38149487718)。实际模型身份回答和真机联动仍需验证。

## 工作清单

用户新增优先项：插件可提供自己的业务界面。[通用 JS/TS 插件页面与记忆示例](plugin-ui-plan.md) 已完成候选代码及远程验证，设置中的记忆/后台任务入口已使用 APK 内置 TS 页面；可安装便签示例验证通用页面接口。0.20.0 已正式发布，接下来继续工作区执行验证；完整既有路线继续保留，真机及真实模型验收未完成。

| 工作 | 实现/验证状态 | 完成证据要求 |
| --- | --- | --- |
| Release 更新检查、顶部下载通知、手动检查 | 0.7.0 已发布：设置手动检查、顶部自动检查、应用内说明和后台下载、进度/取消/安装前校验；远程编译和 Lint 通过，真机待验 | 版本比较、错误处理、可信 APK 地址测试；真机更新卡片和下载验证 |
| JS 宿主、TS SDK、插件包、依赖与资源回收 | JS 宿主和测试已通过远程构建；TS SDK 和打包、TS 测试已通过远程验证 | Android 上加载执行 JS；依赖、取消、卸载测试 |
| 插件市场与服务器部署 | HTTPS 服务已上线；原生安装启停和权限已通过编译与 Lint；真机待验 | 真实目录 API、插件下载校验、安装启停与设置；部署健康检查 |
| 附件、Markdown、分支、摘要、上下文管理 | Markdown 与折叠思考 0.11.0 已发布；分支 0.12.0、预算和持久摘要 0.18.0 已发布并通过单元/模拟器验证；附件待实现 | 数据持久化、模型请求与原生 UI 联调 |
| 多协议、多配置、任务模型选择、本地推理 | 0.14.0 已增加多配置和聊天/工具后续轮次分别路由，远程单元与模拟器验证已通过。模型列表已有；Claude 原生协议与独立 Provider 注册/路由已写入，145 个单元与 26 个模拟器测试、Lint、APK 和签名检查通过 Actions 38144065045。Gemini、本地推理和更多任务仍待实现，见原生协议下一步文档 | 每种协议及本地模型端到端调用 |
| 工具调用、执行进度、结果回传、有界循环 | 主体测试、Lint 和 APK 已通过；SQLite 迁移与 HTTP→JS 集成测试通过 Actions 38104490115，真机/真实模型待验 | 实际模型调用工具；完整取消、授权和执行记录 |
| 内置 MCP 插件、安装配置与角色绑定 | 0.5.1 已发布；TS、公网 HTTPS 演示、Android HTTPS 与取消通知、宿主取消后复用及停用回归通过 Actions 38107628413；0.6.0 已实现按角色绑定 MCP 插件；多个具名HTTPS服务器、独立会话和HTML管理已写入0.22.0候选并正在完整验收；动态工具注册、每工具绑定、OAuth、stdio 等待实现 | 真实 MCP 服务连接、发现和调用 |
| 内置 Skills 插件、按需加载与角色绑定 | 0.6.0 已发布，55 个 Android/23 个 TS 测试通过；真实 QuickJS→角色→Agent→SQLite 集成验证、公网目录与下载校验通过；真机/真实模型待验 | 技能包导入、发现、上下文注入 |
| 内置记忆插件、画像、抽取、合并、图、文档分块、混合检索 | TS 事实合并、候选批量抽取、画像、直接关系、文本分块和 BM25 已提交且 TS 测试通过；Android 宿主测试通过 Actions 38105286718，自动 Hook、向量混合检索待实现 | 持久化、检索、更新删除和上下文使用 |
| Shell、Linux 环境、终端、SSH/SFTP、编辑器、项目管理 | JVM Shell 作业引擎已提交并通过真实 Linux 子进程测试 Actions 38110132814；Android 工作区绑定、目录浏览、多 Shell 命令会话、原生 UI 和 JS 插件接入已发布并通过模拟器验证；Root 身份选择和执行前检查已实现，Root 真机待验；PTY 与同一进程的持久环境 0.10.0 已发布并通过模拟器验证；Linux 与 SSH/SFTP 仍待实现 | 本地和远程执行、流式输出、取消及文件操作 |
| 普通权限、无障碍、Shizuku、Root 与逐工具策略 | 普通应用身份、Root 检测/请求、按插件模式、执行前核验与逐次确认已发布；Root 拒绝、撤销、模式变化和持久化单元测试通过；Root 真机、无障碍、Shizuku 和逐工具策略 UI 待实现 | 设备检测、授权交互、实际执行前强制检查 |
| Hook、模型扩展与声明式插件 UI | 声明式配置 UI、只读 beforeModel Hook、角色上下文与后台记忆 Hook 已发布；API 9 JS/TS 业务页及记忆/任务/便签示例已通过候选远程门禁，0.20.0 正式 APK 已发布；其他模型扩展和 Hook 待实现 | 插件改变执行流程与渲染设置，不修改 App 核心 |

## 实施原则

1. 每个可检查功能及时提交、推送并远程构建，失败日志驱动修复。
2. 现有聊天、数据与原生界面持续维护，不重新从零开发。
3. 系统能力、凭据、授权和资源限制由宿主执行；业务插件主要使用 TS，发布时打包成 JS。
4. Operit 和 DSH 用作架构与接口研究来源；直接采用代码前检查对应许可证并满足要求。
5. 服务器密码、APK 私钥和模型密钥不进入源码、日志或插件目录。

原生模型协议的下一步与数据保留要求见 [Provider 扩展计划](provider-extension-plan.md)。

- 0.20.0：[正式标签 Actions](https://github.com/veryy/Solvit/actions/runs/38201381181)全部成功，实际报告236个单元/39个模拟器测试无失败、错误或跳过；Lint、固定签名、旧版本覆盖升级、68个SDK/2个市场测试、公网MCP/Skills及联网搜索门禁通过。JS/TS记忆/任务页面和可安装便签示例已交付，API9市场与API8兼容包下载已核验。真机和真实模型质量未验收，不把此结果当作完整Agent任务验收。


## 工作区执行候选进展

参考DSH工作区变更/步骤事件与Operit会话终端实现，逐步日志、文件写前/写后证据、安全失败代码及有界工作区快照已在候选分支验证。[授权快照运行38208643824](https://github.com/veryy/Solvit/actions/runs/38208643824)全成功，实际266个单元和40个模拟器测试均无失败/错误/跳过。聊天内文件审阅和实际APK QuickJS→AndroidShell→失败修复→SQLite重开→聊天查看任务尚在远程验证，首次编译失败已按日志补充App测试的直接OkHttp依赖；当前修复运行38209958118进行中。

0.21.0版本号和候选说明已提交，不等于正式发布。后续仍包括完整逐行文件diff、项目规则自动加载、真实模型自主任务及真机验收，再推进手机观察/动作闭环。完整长期范围保持不变，详情见[阶段记录](workspace-evidence-stage.md)。


## 0.21.0正式交付

[0.21.0 Release](https://github.com/veryy/Solvit/releases/tag/v0.21.0)已由[正式标签Actions38214915959](https://github.com/veryy/Solvit/actions/runs/38214915959)发布，源提交d14ea90。测试、Lint、APK、固定签名、覆盖升级、模拟器、SDK/市场、公网MCP/Skills和搜索门禁全部成功；已下载实际XML核对270个单元和41个模拟器测试，均无失败/错误/跳过。交付逐步事实、文件观察/审阅和安全失败结算，脚本模型的Android失败修复任务通过正式模拟器检查。公开APK与该运行的APK产物逐字节一致（23,067,851字节，SHA-256 `3c1349b25055288de9584493eff13c25ca8490949b2b78172a28c6aadd24b05a`），真机和真实模型自主修复未验收。

随后继续完整文件diff、项目规则自动装载、真实模型任务与手机观察/动作闭环。已完成机制和未完成质量验收分别保留，完整产品路线不缩减。

## 0.21.1角色故障复核

用户仍反馈角色无法使用，因此优先暂停新增功能，重新对照 Operit 的角色介绍替换链路和 DSH 提示段契约。实际默认配置的 HTTP 回归复现了自定义角色与内置 Solvit 身份同时发送；修复后全局默认介绍被当前角色替换，显式全局规则保留。设置应用角色→发送→新建对话再发送的模拟器链路通过。

[0.21.1已发布](https://github.com/veryy/Solvit/releases/tag/v0.21.1)，[正式发布门禁](https://github.com/veryy/Solvit/actions/runs/38239629561)重跑失败任务后全部成功；实际核对271个单元、42个模拟器测试及公开APK与构建字节一致。首次搜索人机验证和旧模型设置测试框架线程异常均保留证据，未宣称后者根因已修复。真实模型角色回答仍未验收，不能把机制通过当作用户症状全部解决。详见[角色故障复核](role-followup-stage.md)，完整长期路线保持。

## 0.21.2设置回归与剩余故障

[0.21.2已发布](https://github.com/veryy/Solvit/releases/tag/v0.21.2)，采用Operit字段身份与AndroidX按键滚动API的方式，为模型设置增加稳定列表键并连续三次重开验证。候选及正式首轮设置用例均通过；正式首轮另一个记忆页面用例点击失败，原条件重跑后完整门禁成功。271个单元和42个模拟器测试实际XML已核对，公开APK与构建字节一致。该记忆点击问题仍需优先诊断，不能以重跑替代根因修复。详见[阶段记录](model-settings-stability-stage.md)。真实模型角色回答、流式与搜索验收以及完整工作区/手机路线继续保留。

## 2026-10-13 用户纠正后的当前顺序

0.21.3的[正式标签门禁](https://github.com/veryy/Solvit/actions/runs/38272313319)全成功，271个单元、42个UI的实际报告无失败/错误/跳过；原查询输入场景另有20次连续通过。它只修复页面输入/操作，不代表插件界面或聊天内核达到用户要求。

当前优先交付插件携带离线HTML/CSS/JS页面及通用宿主桥，以现成JS图谱组件重做记忆首页，节点详情/编辑和次要管理按需打开；随后做多个具名MCP连接、加密凭据、独立会话和AI实际跨服务器工具路由。聊天角色/可见流式/工具结果与任务完成继续以真实效果验收。现有TS控件树不是用户要求的JS/HTML自主业务界面。新的源码范围与首个回归检查点见[HTML页面阶段](https://github.com/veryy/Solvit/blob/feat/plugin-html-pages/docs/plugin-html-stage.md)。完整项目规则、文件diff、手机操作和其他既有路线继续保留，不能用本次页面门禁替代。


## 0.22.0候选：插件HTML与多MCP

记忆1.4.0/API10和MCP1.1.0/API10的业务页由插件自带HTML/CSS/JS绘制，Android只提供通用容器、权限、加密与服务桥。记忆采用离线Cytoscape关系图，详情/编辑和后台任务按需打开；MCP支持最多8个具名HTTPS服务器及独立会话。工作区、Skills和其他旧控件树尚未转换，不能称为所有插件UI都已完成。

原图谱实体触摸、编辑、重开及删除确认用例在[38293650208](https://github.com/veryy/Solvit/actions/runs/38293650208)通过。临时诊断已清理，远程生成38294468996的原字节已提交；[干净完整候选38295011493](https://github.com/veryy/Solvit/actions/runs/38295011493)进行中，尚未发布。双HTTPS路由使用真实QuickJS和确定性Provider，不能作为真实模型选择/完成任务的证据。真实角色、可见流式、工具结果与失败修复任务仍需效果验收；全路线保持。

干净候选38295011493已全成功，实际报告285个单元/44模拟器无失败、错误或跳过；72 SDK/2市场与源码包一致性通过。取回图谱首屏截图发现捕获早于实际帧绘制，截图探针依据Android官方帧提交契约修正，继续核对真实画面后正式发布；不把白屏截图或状态已就绪当作图谱可见。

## HTML/JS插件页面当前候选

0.22.2/code34将记忆图谱、后台任务和最多8个具名MCP连接管理迁移到插件HTML/JS，宿主通用容器承担主题、权限、确认、加密与清理。完整Android候选38307706985实际287单元/44模拟器、Lint、固定签名APK和覆盖升级通过；正常SDK38307760810实际75 SDK/2市场及源码/包一致性通过。[候选下载与实际截图](release-0.22.2.md)。独立搜索质量/区域回退失败，尚未正式发布；仅支持HTTPS Streamable HTTP桥接，多MCP不等于动态远程工具逐项注册。真实模型角色/流式/工具选择、搜索引用和真机任务继续保留为未完成验收，原完整路线不缩减。
