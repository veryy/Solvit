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

## 两标签基线与覆盖修正

定向39059472042/source dc5a8c5f在22.227秒通过原实际PTY场景；artifact12110487842，sha256:0af2d68e95b617133ad5de921e06c51f56ad5bf3c6dd5a5dace25a6ff21768b0，大小/摘要/CRC、原始runner与截图均实际核对。截图只有Shell 1与Second HTML Shell两个标签，短名称完整，未复现原完整回归的三标签拥挤。不能据此宣称问题已修复或布局没有问题。

修正同一实际回归的准备步骤：经真实HTML创建及原生内联授权再增加短名称Shell 2，随后创建原长名称，固定复现截图中的至少三标签条件；原始宽度断言与后续PTY行为断言保留。生产代码和APK仍完全不改。下一轮以相同生产APK重新测量这个明确缺失的条件，非重试相同场景。

## 三标签截图复现与断言修正

定向39059969679/source59db4173原runner在22.852秒报告通过，但实际查看artifact12110754262（sha256:c1a175a0b59fed82b4f3d77e1e9e5a6a044a232d3ca16c9080495c6be9358aaa）的三标签PNG，Shell 1与Shell 2均显示She…，与build96完整截图一致。整包大小/摘要/CRC已核对。说明已视觉复现，旧断言未捕捉缺陷，不能称作有效绿测。

原生HTML button的scrollWidth可只反映已压缩的匿名内容盒，即使文字已ellipsis，仍等于clientWidth。修正同一真实页面回归：用按钮实际computed font测量标签文字宽度，和clientWidth减去真实左右padding的可用文字宽度比较。断言输出每个名称、needed和available，以区分CSS字体/内边距和容器布局。仅测试变更，保留三标签创建、截图和全部真实PTY行为；产品布局仍未改动。此轮变化针对已证明的断言盲区，不重试原无效测量。

## 有效红测：短名称被压缩

定向39060467479/sourceadf3b88c在13.54秒实际失败于新增宽度断言；artifact12111645609，sha256:8825204d57440a8aeefa637914b6c8ecd55188be70ffc2be81559b5cdfe73858，整包大小/SHA/CRC与原始runner已核对。Shell 1和Shell 2文字各需要47.5390625 CSS像素，实际文字可用区仅41像素；长名称需要139.1796875而可用121。前面的实际PTY输入、持久环境、原始按键与Ctrl-C均已通过，失败位置是所需布局边界，不是编译或Shell执行。

这与build96完整三标签截图、59db4173独立三标签截图一致，支持默认flex-shrink将短标签压窄的第一假设。尚未实现布局修正；下一步沿用Operit已读的有界自然宽度/独立横向视口原则，避免压缩短名称，并验证选中标签可见。完整聊天草稿回归的超时优先调查，不能把这个红测分支合进build97。
