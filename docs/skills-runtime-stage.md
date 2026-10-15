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
