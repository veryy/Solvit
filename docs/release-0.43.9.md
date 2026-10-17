# Solvit 0.43.9

这一版继续整理Linux环境页和终端管理，让状态、主要操作及细节各有层级。

- Linux页以当前状态和对应操作为主，软件管理、安装记录、环境说明按需展开；刷新保留展开状态和日志阅读位置。下载总量未知时不显示假百分比，解包/初始化继续显示处理中。失败摘要始终在折叠区外，日志可展开查看。
- 终端管理先展示当前Shell、Android/Ubuntu环境与实际状态，再选择连接；快捷键默认收起，关闭操作独立，键盘打开时返回按钮仍可达。
- 修复终端退出码在切换Shell后丢失的显示问题；真实退出码和失败状态继续保留。
- 插件页接收Material主题的错误色和次要文字色，深浅主题中的失败和关闭操作有清楚的视觉区别。
- 原版Linux1.0.0/1.0.1/1.0.2及终端1.1.3可保留设置、授权和停用状态升级；修改包及卸载选择继续保留。插件没有新增权限。

0.43.9/build92，固定APK签名和数据格式继续沿用。[正式Actions](https://github.com/veryy/Solvit/actions/runs/39004170518)完整门禁通过：623项单元、Lint、固定签名、111项常规模拟器用例、1.5倍字体完整聊天、15场进程中断恢复、覆盖升级、stdio宿主实际强杀，以及插件、公开搜索和Ubuntu检查。原始XML、runner与实际截图已核对。

[阶段证据](environment-ui-polish-stage.md)。真实模型任务、App内真实GitHub账号写入、ARM64真机与第三方依赖兼容仍待验收；屏幕操控继续暂缓。

[下载APK](https://github.com/veryy/Solvit/releases/download/v0.43.9/solvit-debug.apk) · [solvit-debug-apk构建产物](https://github.com/veryy/Solvit/actions/runs/39004170518/artifacts/12090367575)

公开APK及Linux源码包已匿名实际下载，与本次正式产物逐字节一致，GitHub摘要与ZIP CRC通过。APK SHA-256：`76f71d9492b244f87bcce0ccfa7af9e99eab41f1db712417454ce91b42ea7633`。固定签名证书SHA-256：`f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。标签`c2a157febbd37e66c5a974d20563952e0e1f9038`保留。
