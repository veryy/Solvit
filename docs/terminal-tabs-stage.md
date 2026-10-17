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

## 修正候选：自然宽度与选中可见性

在有效红测基础上，标签禁止flex收缩，仍受170px或当前视口宽度上限约束；超长名称继续省略，多个标签使用原横向容器滚动。选择/创建Shell以及尺寸变化时，只调整标签容器scrollLeft使选中项可见，保留xterm与文档位置；aria-pressed同步现有选择状态。不复制上游代码或增加库。真实回归保留47.5/41像素的旧断言，并加入新建后末标签、切回第一标签的真实可见区域与选择语义检查及截图。

终端插件1.1.4升1.1.5，权限/API13/PTY桥接不变。原1.1.4完整包字节保留为升级fixture，SHA256 02433235f4c727d7cd5d9513fb50b9433d0ebf7aa7e78779389d3fafcded9166加入既有仅官方原字节升级白名单；保留原激活与授权、修改/卸载保护规则，并增加原包实际恢复测试。预留新APK code98，避免覆盖已验证的旧候选；尚未远程生成包或验证Android，不等于已经交付。先通过Actions生成并审阅包，再回归与构建；不运行本地Android或TS构建/测试。草稿完整诊断33987c1d独立继续，最终整合须保留其实际修正和清理结果。

## 远程打包与市场版本契约

Actions39061763605/sourcef0ebac5e已生成插件包并通过SDK TypeScript检查与147项测试；市场server.test.mjs:154仍期望终端1.1.4而新清单为1.1.5，导致市场测试失败，尚未进行其后的在线检查。这里漏同步了版本契约，现改为准确期望1.1.5，保留API13、完整性、下载与禁写等全部原断言，不据此称该轮成功。

产物12111666364（881565字节，sha256:3bead74bfba83d3a3555d4c746240be8d2d49d2b9af81858b60139f88694dec6）整包摘要/CRC与全部15个包实际比较，只有terminal变化。新终端包331481字节，清单逐字段等于源码；只有ui/main.js和ui/style.css资源改变，其余资源、许可证、编译后的运行逻辑与旧包完全相同。将实际远程生成字节同步到市场和Android内置资源，不在本地生成。后续以关闭generate_packages的严格模式重新验证源码与已提交包一致；Android及真实WebView回归仍待执行。

升级同时将原始1.1.4包按原SHA字节保留到市场legacy目录，旧的按版本下载地址继续提供相同内容；现有HTTP包下载完整性回归增加该版本，避免仅保留Git历史却使原市场URL消失。只追加历史包，不改写既有legacy文件或目录顺序。

## 严格模式结果与整合候选

严格Actions39062011282/source8a968a9d通过TypeScript检查、147项SDK、7项市场测试（含旧包URL），已提交包与重新生成包完全一致检查，以及默认公网搜索和真实HTTPS MCP初始化/发现/调用/断开。最后线上Skills检查失败，原文Live Skills package differs from committed package；不能称整条工作流成功。该门禁检测部署端尚未同步的既有Skills页面包，与本次终端API/权限无关，不删除检查或把候选当正式发布；既有服务器部署认证问题继续保留。未重复运行已通过的代码检查来获取绿色状态。

为避免未通过的build97和新终端修改分别交付，整合33987c1d的测试诊断路径到build98候选，保留完整历史及两个独立诊断入口。生产98同时包含会话草稿归属修正与新终端页面；完整Android启用原临时草稿步骤/线程探针，原12分钟及全部门禁保留。最终交付仍需解决实际卡点、清理探针并通过完整无探针回归；现不发布第二个未验证APK或正式标签。

## Android首轮版本断言失败，仍无新APK

完整39062250423/sourcedab9c5d6的build job117482431283在plugin-market单元阶段失败，582项原始XML中唯一失败为WorkspaceInstructionsHookTest.releasedTerminalManagementRetainsGrantsDisabledCustomCodeAndUninstall：其升级目标仍写1.1.4，实际新包为1.1.5。之前只同步了专用终端升级类，遗漏了此处跨包恢复断言，现准确更新目标；保留1.1.3历史输入、原SHA、停用/授权/自定义/卸载全部断言。原始artifact12112425789的大小/摘要/CRC及原始XML已核对；App剩余单元、Lint与APK/设备未执行，不能宣称627全部通过或生成了APK。

同一实际market.restore路径再增加1.1.4原包升级用例，使用已经保留的原字节与SHA，验证其停用状态、授权、自定义内容和卸载选择；与已有1.1.4启用升级用例互补，完整单元预期因此为628。版本仍code98，因为失败轮没有交付包；TypeScript/终端页面资源和生产实现未再变化，无需重复已通过的插件代码校验。继续远程完整诊断，不提高原超时或移除断言。
