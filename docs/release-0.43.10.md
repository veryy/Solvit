# Solvit 0.43.10/build96（已验证候选，尚未正式发布）

这版继续整理聊天侧栏，承接build95的阅读导航，以及前轮的记忆图谱、Skills/MCP插件页面和终端修复。

- 增加“按标题搜索”，忽略大小写与首尾空格；可一键清空，无结果时明确提示。只搜索标题。
- 关闭后重新打开侧栏会恢复全部对话；同名会话仍按原ID选择和删除，排序与删除确认保留。
- 搜索键盘弹出后，列表与底部设置保持在键盘上方；搜索键收起键盘。长标题省略，150%字体下设置与会话操作可见。
- 保留“回到最新消息”和继续跟随输出；角色统一来源、原生新会话继承与此前插件页优化继续保留。

![实际模拟器：键盘搜索、匹配会话和150%长标题](images/chat-history-search-0.43.10.png)

## 远程验证与下载

[完整Android Actions39053920487](https://github.com/veryy/Solvit/actions/runs/39053920487)在源码[a1408f66](https://github.com/veryy/Solvit/commit/a1408f66dfbc48e0a878a95c075abd1ec48f17b5)上成功：626项单元、Lint、固定签名、115项常规模拟器用例（App86/Web3/terminal26）及15场独立进程中断恢复。原始XML/runner/PID、必需角色/流式/搜索回归、整包摘要/CRC和实际截图已核对。真实存储搜索及150%字体定向2项通过；无探针环境定向8轮各2项通过。此前一次环境刷新超时未再现，根因仍未证明，未修改环境生产逻辑。

[下载solvit-debug-apk候选产物](https://github.com/veryy/Solvit/actions/runs/39053920487/artifacts/12108279914)（GitHub可能需要登录）。实际包名dev.solvit.app、版本0.43.10/build96，59659415字节，签名沿用原版，可覆盖同签名旧版。APK SHA-256：

```text
1a7a311979d3434c8f9bb43586586690b6e224e3731eb495ac1ea6ce809e678a
```

[对应Linux源码产物](https://github.com/veryy/Solvit/actions/runs/39053920487/artifacts/12108373885)内层ZIP为1072711字节，SHA256 3ef1f98bab68f62281bc2e196f2017b1bb2539e3760fcafcf5295f7172497c2f。本轮未改Linux实现；仅正式标签触发的Linux/搜索/插件独立门禁仍需在正式发布时执行。

## 正式发布与尚未验收的部分

2026-10-17实际查询：正式Release仍0.43.9。此前市场同步包已准备，服务器SSH batch认证被拒绝，尚未部署；需恢复有效连接、保留历史包同步，并通过原有严格live和正式标签检查，才正式发布0.43.10。没有降低门禁或移动历史标签。

真机、真实模型回答质量、实际账号任务、ARM64及更多第三方服务兼容仍未验收。截图与设备用例使用可控Provider。本轮[阶段记录](chat-history-search-stage.md)保留各次失败、定向结果与最终证据；未运行本地Android/TS构建或测试。

此前[build95原说明](https://github.com/veryy/Solvit/blob/9042ac00a25da1f30e72ac623d9089840de80472/docs/release-0.43.10.md)、[聊天阅读导航](chat-reading-navigation-stage.md)、[记忆记录](memory-search-viewport-stage.md)、[Skills/MCP记录](skills-mcp-ui-polish-stage.md)保留，历史失败未改写。完整路线见[后续方向](agent-direction.md)。
