# Skills 资源目录与 Ubuntu 执行

2026-10-16。上一轮0.42.3已发布；本阶段尚未发布，不把导入或准备资源等同于脚本执行成功。

## 上游实际核对

- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：`packages/skill/tool-skill/src/index.ts`及`tests/tool-skill.spec.ts`，`skill/src/index.ts`的resourceBase与目录渲染，`skill-filesystem/tests/skill-filesystem.spec.ts`的真实目录/符号链接/读取失败。采用按角色发现、加载时返回资源基准、执行工具独立授权的分工，不把导入触发器变成自动执行。
- 同提交`tool-workspace-dependencies/src/index.ts`：显式解释器路径和环境信息，不声称QuickJS提供Node进程或模块API。
- ZAI coding plugins `0446d0bb0bc537d97d3ab3664c4b8b9c4a0e1254`：`plugins/glm-plan-bug/skills/case-feedback-skill/SKILL.md`及`scripts/submit-feedback.mjs`。实际示例依赖Node内置https及显式认证环境，要求只执行一次。只阅读，没有运行反馈脚本、发送消息或采用其认证配置。
- Operit固定`dbf71916`/TerminalCore`04586898`的PRoot与软件安装链沿用[Linux阶段](linux-environment-stage.md)；不是复制预编译程序。

DSH为MIT，ZAI包为Apache-2.0，Operit参考为LGPL-3.0；本阶段独立适配，没有复制这些实现源码。

## 已提交的第一步

新增通用工作区文本资源发布原语：最多40文件、单个32KiB/合计220KiB，严格UTF-8与路径校验，在私有缓存准备后使用现有原生目录发布器原子、不覆盖地发布到新UUID目录。返回工作区相对路径及源内容摘要，不赋予执行权限，不写入既有项目文件。取消/失败只清理自己的私有暂存；已经发布的副本归工作区所有，不能自动删除其后续修改。突然结束遗留的是系统可清理缓存，不自动重放发布；缓存目录有容量上限。普通应用UID下的Shell不是恶意代码安全沙箱。

新增6项远程单元：Unicode及稳定摘要、不执行/不覆盖、路径/容量拒绝、取消清理、根目录变化/符号链接、并发目标及发布后撤权保留。尚未运行。下一步接插件授权桥、Skills TS资源准备和Ubuntu实际Agent验证，未完成前不更新用户指南为已支持运行。

API31候选新增`workspace.resources`和单工具`requiresWorkspace`声明。该字段只控制是否显示工具，不授予工作区/执行权限；普通聊天仍可列出/读取Skills，准备资源必须有绑定。通用授权桥核对原始确认中的资源名、当前插件代码/权限/启用状态、角色、聊天绑定和一次性调用；文件正文来自已授权插件，目的路径由宿主选择，不接受模型填入额外文件正文/路径。插件可以提供自己的资源格式，Kotlin不解析SKILL.md或硬编码Skills业务。新增3项桥授权及API声明/SDK构建检查，尚待远程执行。

Skills1.2.0/API31候选增加`skills_prepare`。先加载正文，按需准备当前角色允许技能的完整文本包，返回`resourceBase`与工作区相对路径，再复用已授权Ubuntu Shell；导入、加载、准备都不会运行脚本。补充.mjs/.cjs文本资源，TS仍需要显式运行器，不声称兼容所有桌面技能或二进制资产。旧Skills1.1.1原始字节保留为API1–30市场包及APK兼容资产；已安装用户不自动增加workspace授权。既有更早原始种子只升级到兼容包，明确更新新版才重审权限。新增JS角色范围/路径和不执行检查，页面及聊天旧行为保留。
