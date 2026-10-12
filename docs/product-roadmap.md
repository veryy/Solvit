# Solvit 产品实现进度

目标是以 JS/TS 插件为主要扩展方式的 Android Agent 产品。原生 UI 与系统能力由 Kotlin 宿主提供，插件通过稳定服务协议组合。以下是完整目标，不以当前已交付功能代替最终范围。

## 当前优先方向（2026-10-12）

自动记忆 [0.19.0 已发布](https://github.com/veryy/Solvit/releases/tag/v0.19.0)，[完整标签门禁通过](https://github.com/veryy/Solvit/actions/runs/38190322667)：213 个单元、33 个模拟器测试及 Lint/APK/固定签名/TS/公网搜索。后台保存默认关闭，须明确授权和开启；真实模型与真机验收待完成，具体边界见 [阶段记录](automatic-memory-stage.md)。下一优先项为插件自带页面，当前仍在独立分支开发，尚未交付。

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

用户新增优先项：插件可提供自己的业务界面。自动记忆版交付后，先实现 [通用 JS/TS 插件页面与记忆示例](plugin-ui-plan.md)，再继续工作区执行验证。当前 Kotlin 记忆管理页不算插件自带界面；完整既有路线继续保留。

| 工作 | 实现/验证状态 | 完成证据要求 |
| --- | --- | --- |
| Release 更新检查、顶部下载通知、手动检查 | 0.7.0 已发布：设置手动检查、顶部自动检查、应用内说明和后台下载、进度/取消/安装前校验；远程编译和 Lint 通过，真机待验 | 版本比较、错误处理、可信 APK 地址测试；真机更新卡片和下载验证 |
| JS 宿主、TS SDK、插件包、依赖与资源回收 | JS 宿主和测试已通过远程构建；TS SDK 和打包、TS 测试已通过远程验证 | Android 上加载执行 JS；依赖、取消、卸载测试 |
| 插件市场与服务器部署 | HTTPS 服务已上线；原生安装启停和权限已通过编译与 Lint；真机待验 | 真实目录 API、插件下载校验、安装启停与设置；部署健康检查 |
| 附件、Markdown、分支、摘要、上下文管理 | Markdown 与折叠思考 0.11.0 已发布；分支 0.12.0、预算和持久摘要 0.18.0 已发布并通过单元/模拟器验证；附件待实现 | 数据持久化、模型请求与原生 UI 联调 |
| 多协议、多配置、任务模型选择、本地推理 | 0.14.0 已增加多配置和聊天/工具后续轮次分别路由，远程单元与模拟器验证已通过。模型列表已有；Claude 原生协议与独立 Provider 注册/路由已写入，145 个单元与 26 个模拟器测试、Lint、APK 和签名检查通过 Actions 38144065045。Gemini、本地推理和更多任务仍待实现，见原生协议下一步文档 | 每种协议及本地模型端到端调用 |
| 工具调用、执行进度、结果回传、有界循环 | 主体测试、Lint 和 APK 已通过；SQLite 迁移与 HTTP→JS 集成测试通过 Actions 38104490115，真机/真实模型待验 | 实际模型调用工具；完整取消、授权和执行记录 |
| 内置 MCP 插件、安装配置与角色绑定 | 0.5.1 已发布；TS、公网 HTTPS 演示、Android HTTPS 与取消通知、宿主取消后复用及停用回归通过 Actions 38107628413；0.6.0 已实现按角色绑定 MCP 插件；多服务器、每工具绑定、OAuth、stdio 等待实现 | 真实 MCP 服务连接、发现和调用 |
| 内置 Skills 插件、按需加载与角色绑定 | 0.6.0 已发布，55 个 Android/23 个 TS 测试通过；真实 QuickJS→角色→Agent→SQLite 集成验证、公网目录与下载校验通过；真机/真实模型待验 | 技能包导入、发现、上下文注入 |
| 内置记忆插件、画像、抽取、合并、图、文档分块、混合检索 | TS 事实合并、候选批量抽取、画像、直接关系、文本分块和 BM25 已提交且 TS 测试通过；Android 宿主测试通过 Actions 38105286718，自动 Hook、向量混合检索待实现 | 持久化、检索、更新删除和上下文使用 |
| Shell、Linux 环境、终端、SSH/SFTP、编辑器、项目管理 | JVM Shell 作业引擎已提交并通过真实 Linux 子进程测试 Actions 38110132814；Android 工作区绑定、目录浏览、多 Shell 命令会话、原生 UI 和 JS 插件接入已发布并通过模拟器验证；Root 身份选择和执行前检查已实现，Root 真机待验；PTY 与同一进程的持久环境 0.10.0 已发布并通过模拟器验证；Linux 与 SSH/SFTP 仍待实现 | 本地和远程执行、流式输出、取消及文件操作 |
| 普通权限、无障碍、Shizuku、Root 与逐工具策略 | 普通应用身份、Root 检测/请求、按插件模式、执行前核验与逐次确认已发布；Root 拒绝、撤销、模式变化和持久化单元测试通过；Root 真机、无障碍、Shizuku 和逐工具策略 UI 待实现 | 设备检测、授权交互、实际执行前强制检查 |
| Hook、模型扩展与声明式插件 UI | 声明式配置 UI、只读 beforeModel Hook 和角色上下文已发布并通过远程验证；模型扩展、其他 Hook 和 UI 待实现 | 插件改变执行流程与渲染设置，不修改 App 核心 |

## 实施原则

1. 每个可检查功能及时提交、推送并远程构建，失败日志驱动修复。
2. 现有聊天、数据与原生界面持续维护，不重新从零开发。
3. 系统能力、凭据、授权和资源限制由宿主执行；业务插件主要使用 TS，发布时打包成 JS。
4. Operit 和 DSH 用作架构与接口研究来源；直接采用代码前检查对应许可证并满足要求。
5. 服务器密码、APK 私钥和模型密钥不进入源码、日志或插件目录。

原生模型协议的下一步与数据保留要求见 [Provider 扩展计划](provider-extension-plan.md)。
