# Solvit 0.43.0

Skills可以把自带的Python/Node文本脚本与引用文件准备到工作区，再通过Ubuntu Shell实际执行。资源准备、Shell运行分别经过聊天内授权；导入或读取技能不会运行代码。

先在「工作区 → Linux」安装Ubuntu并新建Ubuntu Shell，再更新并启用Skills1.2.0、工作区与Shell插件，在角色中允许使用。模型先加载技能正文，调用skills_prepare获得实际目录，再选择UBUNTU会话执行，检查退出码与输出文件。Python项目可使用venv，Node使用独立Ubuntu运行时；依赖需要明确安装，TS仍需自行配置运行器。

旧Skills用户的数据、启用状态和原授权保持。新版增加workspace权限，需明确更新并重新授权；普通聊天仍可读取技能，不强制创建工作区。API1–30市场目录及55个历史包保持不变。

资源发布到独立的新目录，不覆盖项目文件。取消或撤权不会删除已发布、可能已被修改的工作区副本。修复Android应用数据路径别名导致资源准备失败的问题，保留严格目录身份和不覆盖检查。

Ubuntu/PRoot沿用Operit同类的免root方案，继续使用Android内核与应用UID，不能绕过Android权限，也不是不可信代码隔离。QuickJS插件逻辑与Ubuntu Node进程分开。ARM64真机、第三方技能依赖和真实模型自主任务尚待验收；确定性Provider驱动的模拟器工具验证不能替代它们。

发布由完整远程测试、Lint、固定签名APK、设备UI、实际Ubuntu Python/Node执行及市场检查共同把关。[使用指南](skills.md) · [研究与逐轮验证记录](skills-runtime-stage.md)。
