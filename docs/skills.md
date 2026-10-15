# Skills 与角色

Skills 技能库是 TS 插件 `dev.solvit.skills`，逻辑和页面由 QuickJS、离线 HTML/JS 运行。它管理任务说明和资源文件；Android 宿主处理文件选择、角色持久化与工具授权，Ubuntu 环境负责实际运行 Python、Node 等程序。

## 先导入，再让角色使用

1. 在「设置 → 插件」启用 Skills 技能库。原来的1.1.1只需要storage；0.43.1内置1.2.0增加workspace权限，旧用户需明确更新并重新授权，原来的技能数据保留。
2. 在设置里的角色管理选择角色，打开技能库，导入 SKILL.md、单个技能的 ZIP，或 Solvit 导出的 JSON。也可以从插件自己的 Skills 页面管理内容。替换同名技能需要确认。
3. 在角色中勾选 Skills 插件和允许使用的技能，再应用到当前对话。新对话继承当前角色，已有对话的选择独立保存。
4. 提出任务。模型先看到可用技能名称和简短描述，需要时调用 `skills_load` 读取正文，用 `skills_read` 读取引用文本。

默认角色使用全部已启用插件和技能；自定义角色只使用绑定插件与勾选技能。选择插件不会自动启用或授权。普通聊天不必创建工作区，也能读取技能。

## 技能带脚本时怎么运行

0.43.1的Skills1.2.0提供 `skills_prepare`。它把当前角色允许使用的技能文本包复制到当前工作区的新目录，返回实际的 `resourceBase`，例如 `/workspace/.solvit-resource-example-…`。导入、读取和准备都不会执行代码；不要猜测技能安装路径。

1. 给对话绑定工作区，在「工作区 → Linux」安装Ubuntu并新建Ubuntu Shell，见[Linux指南](linux-environment.md)。
2. 启用工作区与Shell插件，并在当前角色里允许Skills和工作区插件。
3. 让模型加载技能正文，调用 `skills_prepare`，再通过工作区 `info` 选择 `runtime: UBUNTU` 的Shell。
4. 单独确认执行命令，例如 `python3 '/workspace/返回目录/scripts/main.py'` 或 `node '/workspace/返回目录/scripts/main.mjs'`。
5. 检查Shell退出码与输出，必要时修复输入/依赖并重新运行，最后读取实际生成的文件。命令启动成功不等于任务完成。

项目目录是 `/workspace`。脚本通过自身文件位置解析技能里的相对资源，通过当前目录处理项目文件。Python依赖建议装进项目venv；Node第三方依赖需要明确安装。`.ts`文本可导入，但运行时需自行配置TS解释器或编译流程。QuickJS不提供Node全局对象。

每次准备会产生独立副本，不覆盖既有项目文件，也不自动更新先前副本。副本之后可由工作区编辑；返回的摘要表示准备时的源内容，不表示文件永远不会变化。停止或撤权后不会删除已经发布、可能已被修改的工作区副本。

## 文件格式与限制

参考[Agent Skills规范](https://agentskills.io/specification)。SKILL.md需要YAML中的name、description以及正文；支持引号、多行和metadata，拒绝重复键和别名。

```markdown
---
name: report-example
description: 用自带Python脚本整理工作区数据并验证输出。
---
先准备本技能资源目录，再使用Ubuntu Shell运行scripts/main.py。
脚本按自身位置读取references中的模板，按工作区目录读取输入。
检查退出码和输出文件；失败时先读取错误，不要直接报告完成。
```

ZIP可包含根SKILL.md，或 `report-example/SKILL.md` 和同目录资源。目录名应与name一致。支持scripts、references、assets下允许扩展名的文本，包含py/js/mjs/cjs/ts；不支持二进制资产或全部桌面技能依赖。

- 输入最多512KiB；单个文本32KiB；每包最多40个文本，ZIP展开合计220KiB。
- 技能库最多32个技能，序列化存储最多220KiB。满额或损坏时保留已有数据并报错。
- 拒绝路径穿越、多技能包、无效UTF-8和超限内容。资源准备不接受模型提供任意目标路径或额外文件正文。
- `allowed-tools`只是技能元数据，不能自动授权、绕过角色范围或Android权限。
- 导入、删除、导出是管理操作，不向聊天模型提供。插件仍能访问自己已获授权的存储，角色过滤不是独立进程隔离。

MCP已支持多个具名HTTPS/本地stdio连接，见[MCP说明](mcp.md)。Shell、Ubuntu和Skills是可组合能力，Ubuntu/PRoot仍使用本应用UID，不能取得Android root，也不是不可信代码安全沙箱。

## Hook与验证

Skills通过 `beforeModel` 按角色提供元数据目录，正文按需加载；停用或卸载会注销Hook。每个Hook最多10秒/32KiB，合计64KiB，不改变模型密钥或授权规则。开发接口见[SDK说明](../plugin-sdk/README.md)。

本阶段远程用例、实际Ubuntu脚本探针和发布状态见[验证记录](skills-runtime-stage.md)。确定性Provider驱动的真实工具执行不代表真实模型已自主完成任务；ARM64真机、设备文件选择和第三方技能依赖仍需实际验收。
