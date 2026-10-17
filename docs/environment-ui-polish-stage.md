# Linux环境与终端管理交互打磨

基线为已正式发布的0.43.8；本阶段尚未交付。重新检查Git工作树及公开版正式设备归档，实际查看linux-plugin-before-install与workspace-terminal-session-manager截图。环境页说明、状态和操作为同权重的纵向堆叠；管理页把会话选择、快捷键与关闭放在一起。继续使用插件自己的HTML/JS和原生权限/确认桥，不重写PTY或安装器。

## 上游依据

2026-10-17执行fetch-upstreams.py --verify-only，全部锁定checkout一致。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb的ui/features/demo/wizards/OperitTerminalWizardCard.kt将环境状态作为摘要、展开后才呈现配置；TerminalCore 045868988e4091d613cb34b2941057703899b96e的terminal/ui/SetupScreen.kt按软件分类折叠、保留独立安装操作。没有找到对应独立Setup/Wizard UI测试，不声称上游已验证Solvit流程。

DSH d743267388641bc76f17c45ce8b4c231aed1d32c的packages/client/ui-tool/src/client/tool/models/terminal-card-model.ts及tests/terminal-card.client.spec.tsx实际区分运行中、已退出及失败；摘要也必须保留失败状态，展开内容不能改变原状态。采用状态/细节分层及原始输出保留的原则，适配Android WebView、原生确认与已有事件标识。已读DSH MIT、Operit/TerminalCore LGPL-3.0根许可证；不复制组件实现、不添加依赖；现有xterm离线分发和许可证保留。

## 实施与验收

环境页突出当前状态及对应操作，按需展开软件、说明和日志；真实下载量可计算时才显示百分比，未知总量不伪造进度。刷新保持阅读位置和展开状态，失败摘要保持可见；打开折叠区不发安装指令，安装与软件操作仍由原生确认。终端管理优先会话和当前连接，快捷键折叠；真实PTY、模型终端接管确认及关闭语义保留。

保留原版官方插件包，核对摘要并验证升级保持设置/授权/停用、修改包及卸载选择。GitHub Actions生成插件，检查真实HTML触摸、主题/大字/键盘、原生确认及实际PTY流程，然后完整单元/Lint/设备/恢复/Linux门禁和固定签名APK发布。真实模型、ARM64真机及完整路线继续按agent-direction.md保留。
