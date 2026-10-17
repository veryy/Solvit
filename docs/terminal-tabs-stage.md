# 终端标签可辨识度打磨（2026-10-18）

## 现象与当前范围

build96完整设备产物12109486750的真实`workspace-interactive-html-pty.png`截图中，多个短Shell名称显示为相似的`She…`。页面使用现有xterm.js与Android PTY，不重新实现终端。先复用build97的已完成远程构建，测量实际WebView标签，再决定修改。此提交只有回归用例和诊断入口，没有产品修复或新APK声明。

按当前证据排序的假设：一是Flex将多个标签压缩，短名称也裁切；二是名称本身过长；三是选中标签没有自动滚动到可见范围。实际DOM的`textContent/clientWidth/scrollWidth`区分前两项；后续才验证选中项的可见范围。不能只凭CSS文本宣布根因成立。

## 实际上游、许可与Android适配

全部9个固定上游已用`python3 scripts/fetch-upstreams.py --verify-only`核对。Operit terminal-core固定045868988e4091d613cb34b2941057703899b96e：

- `src/main/java/com/ai/assistance/operit/terminal/view/canvas/TerminalTabRenderItem.kt`：id、名称、选择状态分离。
- `src/main/java/com/ai/assistance/operit/terminal/view/canvas/CanvasTerminalView.kt`的`setTabs`、`getTabsViewportWidth`、标签宽度/滚动计算：按名称测量并限制宽度，用独立横向视口容纳标签，新标签出现后滚动，而非把全部标题压窄。
- 搜索此固定仓库没有找到对应`src/test`或`src/androidTest`用例，不能声称有上游移动标签回归证明。

DSH固定d7432673（完整revision见upstream-sources.json）：`packages/client/ui-sidebar-terminal/src/client/TerminalGuide.tsx`及`terminal.module.css`、`tests/terminal-guide.client.spec.tsx`。实际测试覆盖主按钮直接打开与按需Shell菜单，未证明手机多标签宽度行为。只借鉴独立清晰的Shell选择入口，不据此扩展任务范围。

实际LICENSE分别为LGPLv3与MIT。本阶段不复制代码、不新增依赖；后续若DOM证据支持，将在现有HTML插件中用标准CSS/DOM适配有界标签与横向滚动，不打包上游运行时。

## 回归边界

扩展已有`ChatLayoutTest.interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput`：在真实页面创建Second HTML Shell后，保存真实截图并读取每个标签的文字、可见宽度、内容宽度；不超过12字符的短名称必须完整显示，长名称允许有限省略。保留后续真实PTY、环境隔离、Ctrl-C、AI会话、管理与退出码断言。独立`terminal-tabs`入口复用相同生产源APK，构建和测试仅在Actions运行。

当前待远程测量，不计为通过，也不把现有截图当作新测试结果。
