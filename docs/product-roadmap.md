# Solvit 产品实现进度

目标是以 JS/TS 插件为主要扩展方式的 Android Agent 产品。原生 UI 与系统能力由 Kotlin 宿主提供，插件通过稳定服务协议组合。以下是完整目标，不以当前已交付功能代替最终范围。

## 当前验证

- 0.1.0：基础文本聊天、流式取消、多会话、加密密钥设置；15 个单元测试、Lint 和 APK 构建通过 GitHub Actions。
- 0.1.1：更新检查与顶部卡片已发布；对应 Actions 测试、Lint、APK 和 Release 发布成功。
- JS 插件宿主和原生市场：测试、Lint 和 APK 构建通过 [Actions 38103439870](https://github.com/veryy/Solvit/actions/runs/38103439870)。QuickJS 使用 compileSdk 36。0.2.0 已发布 APK。
- 0.3.0：模型工具调用主体、SQLite 数据迁移与 HTTP→QuickJS 集成测试通过 [Actions 38104490115](https://github.com/veryy/Solvit/actions/runs/38104490115)，[0.3.0 已发布 APK](https://github.com/veryy/Solvit/releases/tag/v0.3.0)，[标签构建与发布通过](https://github.com/veryy/Solvit/actions/runs/38104671862)。
- 市场服务已部署 HTTPS，目录、下载校验测试与健康检查通过；当前目录包含时间与纯 TS 编写的记忆工具；记忆包已部署，服务器目录与下载校验测试通过。
- 0.4.0：TS SDK、记忆插件、离线安装与宿主集成已实现；[Android Actions 38105286718](https://github.com/veryy/Solvit/actions/runs/38105286718) 和 [TS/市场验证 38105244171](https://github.com/veryy/Solvit/actions/runs/38105244171) 通过，[0.4.0 已发布 APK](https://github.com/veryy/Solvit/releases/tag/v0.4.0)，[Android、TS、市场验证与发布通过](https://github.com/veryy/Solvit/actions/runs/38105586294)。
- 0.5.1：TS MCP、加密插件配置、声明式配置页、HTTPS 取消通知与取消后插件复用已发布；[45 个 Android 测试、Lint、APK、TS、市场、实际公网 HTTPS MCP 与发布通过](https://github.com/veryy/Solvit/actions/runs/38107628413)，[下载 0.5.1 APK](https://github.com/veryy/Solvit/releases/tag/v0.5.1)。0.5.0 标签被新增取消回归阻止发布，修复保留在后续提交中。
- 0.6.0：Skills 文本包导入/导出、角色绑定、只读 JS beforeModel Hook 已发布；[55 个 Android 测试、23 个 TS 测试、Lint、APK、市场、公网 MCP/Skills 与发布通过](https://github.com/veryy/Solvit/actions/runs/38109369009)，[下载 0.6.0 APK](https://github.com/veryy/Solvit/releases/tag/v0.6.0)。市场已上线含 ISC 署名的 Skills 1.0.1，旧客户端过滤验证通过。
- 尚未验证真机安装、文件选择和真实模型调用。
- 首版使用临时 CI debug 签名。后续构建改用 GitHub Actions Secret 中的固定签名，以保证后续版本可覆盖安装。0.1.0 与新签名不兼容，不能声称可以直接覆盖安装。

## 工作清单

| 工作 | 实现/验证状态 | 完成证据要求 |
| --- | --- | --- |
| Release 更新检查、顶部下载通知、手动检查 | 0.1.1 已发布，远程验证通过；真机待验 | 版本比较、错误处理、可信 APK 地址测试；真机更新卡片和下载验证 |
| JS 宿主、TS SDK、插件包、依赖与资源回收 | JS 宿主和测试已通过远程构建；TS SDK 和打包、TS 测试已通过远程验证 | Android 上加载执行 JS；依赖、取消、卸载测试 |
| 插件市场与服务器部署 | HTTPS 服务已上线；原生安装启停和权限已通过编译与 Lint；真机待验 | 真实目录 API、插件下载校验、安装启停与设置；部署健康检查 |
| 附件、Markdown、分支、摘要、上下文管理 | 待实现 | 数据持久化、模型请求与原生 UI 联调 |
| 多协议、多配置、任务模型选择、本地推理 | 待实现 | 每种协议及本地模型端到端调用 |
| 工具调用、执行进度、结果回传、有界循环 | 主体测试、Lint 和 APK 已通过；SQLite 迁移与 HTTP→JS 集成测试通过 Actions 38104490115，真机/真实模型待验 | 实际模型调用工具；完整取消、授权和执行记录 |
| 内置 MCP 插件、安装配置与角色绑定 | 0.5.1 已发布；TS、公网 HTTPS 演示、Android HTTPS 与取消通知、宿主取消后复用及停用回归通过 Actions 38107628413；0.6.0 已实现按角色绑定 MCP 插件；多服务器、每工具绑定、OAuth、stdio 等待实现 | 真实 MCP 服务连接、发现和调用 |
| 内置 Skills 插件、按需加载与角色绑定 | 0.6.0 已发布，55 个 Android/23 个 TS 测试通过；真实 QuickJS→角色→Agent→SQLite 集成验证、公网目录与下载校验通过；真机/真实模型待验 | 技能包导入、发现、上下文注入 |
| 内置记忆插件、画像、抽取、合并、图、文档分块、混合检索 | TS 事实合并、候选批量抽取、画像、直接关系、文本分块和 BM25 已提交且 TS 测试通过；Android 宿主测试通过 Actions 38105286718，自动 Hook、向量混合检索待实现 | 持久化、检索、更新删除和上下文使用 |
| Shell、Linux 环境、终端、SSH/SFTP、编辑器、项目管理 | 待实现 | 本地和远程执行、流式输出、取消及文件操作 |
| 普通权限、无障碍、Shizuku、Root 与逐工具策略 | 待实现 | 设备检测、授权交互、实际执行前强制检查 |
| Hook、模型扩展与声明式插件 UI | 声明式配置 UI、只读 beforeModel Hook 和角色上下文已发布并通过远程验证；模型扩展、其他 Hook 和 UI 待实现 | 插件改变执行流程与渲染设置，不修改 App 核心 |

## 实施原则

1. 每个可检查功能及时提交、推送并远程构建，失败日志驱动修复。
2. 现有聊天、数据与原生界面持续维护，不重新从零开发。
3. 系统能力、凭据、授权和资源限制由宿主执行；业务插件主要使用 TS，发布时打包成 JS。
4. Operit 和 DSH 用作架构与接口研究来源；直接采用代码前检查对应许可证并满足要求。
5. 服务器密码、APK 私钥和模型密钥不进入源码、日志或插件目录。
