# Solvit 0.43.1

已发布0.43.1/build84/API31。[下载APK](https://github.com/veryy/Solvit/releases/download/v0.43.1/solvit-debug.apk) · [完整Actions](https://github.com/veryy/Solvit/actions/runs/38777281162) · [代码提交](https://github.com/veryy/Solvit/commit/59a6174ed70f0480e67b0f8a9d699feb9fdb0f86)。

Skills可以把自带的Python/Node文本脚本与引用文件准备到工作区，再通过Ubuntu Shell实际执行。资源准备、Shell运行分别经过聊天内授权；导入或读取技能不会运行代码。

先在「工作区 → Linux」安装Ubuntu并新建Ubuntu Shell，再更新并启用Skills1.2.0、工作区与Shell插件，在角色中允许使用。模型先加载技能正文，调用skills_prepare获得实际目录，再选择UBUNTU会话执行，检查退出码与输出文件。Python项目可使用venv，Node使用独立Ubuntu运行时；依赖需要明确安装，TS仍需自行配置运行器。

旧Skills用户的数据、启用状态和原授权保持。新版增加workspace权限，需明确更新并重新授权；普通聊天仍可读取技能，不强制创建工作区。API1–30市场目录及55个历史包保持不变。

资源发布到独立的新目录，不覆盖项目文件。取消或撤权不会删除已发布、可能已被修改的工作区副本。修复Android应用数据路径别名导致资源准备失败的问题，保留严格目录身份和不覆盖检查。

Ubuntu/PRoot沿用Operit同类的免root方案，继续使用Android内核与应用UID，不能绕过Android权限，也不是不可信代码隔离。QuickJS插件逻辑与Ubuntu Node进程分开。ARM64真机、第三方技能依赖和真实模型自主任务尚待验收；确定性Provider驱动的模拟器工具验证不能替代它们。

发布由完整远程测试、Lint、固定签名APK、设备UI、实际Ubuntu Python/Node执行及市场检查共同把关。[使用指南](skills.md) · [研究与逐轮验证记录](skills-runtime-stage.md)。

0.43.0因新增Ubuntu探针漏接生产执行身份服务而被发布门禁阻止，没有发布APK。本版补齐测试装配，并断言模型看到的Shell执行身份为APP；不放宽原生身份核对。

已知问题：首轮完整UI检查在Activity反复重建时出现系统WebView124原生SIGSEGV。原APK后续8轮/24次重建未复现，尚未定位根因；不能把一次通过说成已修复。失败报告和后续完整检查分别保留在[阶段记录](skills-runtime-stage.md)。ARM64真机也仍待验收。

## 正式验证与下载核对

正式同源重跑38777281162六项门禁全部通过：611单元、4项报告门禁检查、Lint、固定签名APK、141 SDK/7市场检查、公开搜索和实际Ubuntu环境。原始UI归档11994954330已完整下载，7717840字节、SHA256 `81235d9c94c3cf65fd202955c2035cf084b8f6fc0aa98fe61147bebec35df684`，CRC与原始XML核对app68/Web3/终端25项，无失败、错误或跳过。覆盖升级种子、15场导入/备份/Git强杀恢复及实际stdio宿主SIGKILL全部通过；后者四个记录PID均已消失。

Linux原始归档11994363376已完整下载/CRC/XML核对11单元与3设备用例；实际设备日志确认Python3.12.3、Node24.21.0/npm11.19.0、Java17，以及两种脚本实际失败→修复→读取共享输出。Agent使用确定性Provider，不能替代真实模型任务。首轮WebView崩溃报告与24次未复现记录保留，后续完整回归通过不等于修复根因。

APK：58,955,031字节，SHA256 `7e078b242d6a4c173c8f648c19a336f89332d2cebc1d225517f04fda4267910f`。Linux源码ZIP：1,072,711字节，SHA256 `3ec996167107bb6d2b99008c5488407bece4a301337cb6fea04af0dea1759212`。正式产物已完整下载核对；公开资产大小和GitHub摘要一致，APK公开下载实际返回206及ZIP文件头。未再次全文下载公开APK，不把入口检查说成全文字节对比。

固定签名仍为 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`；正常覆盖安装保留数据，卸载会删除数据。
