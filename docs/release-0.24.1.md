# Solvit 0.24.1：精简终端与图谱缩放修复

交互终端由插件 HTML/JS 和离线 xterm.js 绘制。连接和关闭会话收进管理弹窗，次要按键默认收起；键盘弹出后的短视口采用单行输入、中断和发送，全部快捷键仍能通过管理弹窗访问。管理页内容独立滚动、返回按钮固定，按键完整显示，焦点颜色跟随主题；六种语言覆盖新控件。宿主继续负责权限、PTY、进程和取消。

记忆图谱首次和手动“适配”限制自动放大至基础尺寸，避免少量节点的标签过大；大图可缩小适配，手动缩放、节点详情与编辑保留。

build51/API15，内置记忆1.4.1、终端1.1.3。原始旧插件包按精确哈希升级，保留启用、授权和数据；修改过或已卸载的包不会被覆盖。旧版本下载字节保留，APK 沿用固定签名。

[正式 Actions](https://github.com/veryy/Solvit/actions/runs/38391356635) 全部通过：下载报告确认344单元、48模拟器测试，均0失败/错误/跳过；86 SDK与2市场测试、Lint、固定签名、覆盖升级、插件源码/包一致性、公开搜索和线上 MCP/Skills 均通过。图谱和真实 PTY/键盘回归均先复现再修复，实际截图见[阶段记录](https://github.com/veryy/Solvit/blob/main/docs/plugin-ui-density-stage.md)。

[公开 APK](https://github.com/veryy/Solvit/releases/download/v0.24.1/solvit-debug.apk) 已实际下载，与 Actions 产物逐字节一致：23,540,285 bytes，SHA-256 `c3bd9a55b110c109b9f2eee298d490318343a91740ae1edc9c3c726e5aa9feb7`。也可从 [solvit-debug-apk 产物](https://github.com/veryy/Solvit/actions/runs/38391356635/artifacts/11809311362) 下载（需登录 GitHub）。

真实模型角色、网关流式、工具选择、完整任务和真机交互仍待验收；本次受控模型会话与页面回归不代表这些项目完成。
