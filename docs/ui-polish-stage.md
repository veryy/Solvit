# UI 打磨：先改善信息层级和操作路径

## 用户目标与范围

用户认为已有界面与操作逻辑仍需整体打磨。本轮按日常使用路径推进，保留原有完整功能路线，不把新增入口或通过测试的数量作为体验验收。

1. 设置：导航首页只保留入口、当前状态和短说明；主题详细控制收进二级页面。语言继续靠后。检查从子页返回、当前选择与实际持久化是否一致。
2. 聊天：继续整理标题、消息、折叠工具、授权、排队和输入区之间的层级；待处理状态应明确告诉用户正在等什么、可以做什么。重点验证键盘弹出后正文与操作仍能使用。
3. 工作区：文件、Changes、Shell 与环境共用清晰的目录/会话状态；长任务的进度、取消、失败与结果反馈遵循相同规则。
4. 插件：统一宿主页与 HTML 页的字号、留白、主题、返回和加载/空/错误状态，业务界面仍由插件提供。

验收至少包含实际截图审阅、浅深色、小屏/大字、键盘、滚动到底、返回路径和取消/失败状态。不能仅根据代码中的尺寸认定好看或可用；未执行的项目保持未完成。

## 已查看的真实基线截图

来源：远程任务 `38841085736` 的 `android-ui-reports` 报告归档（本地文件 `solvit-ready-clean-ui.zip`，SHA-256 `dea74c71607182ff29c68ba0119a0b22d2253181d0456d18e3e4530a8b0c1616`；基线，不是本次候选结果）。已实际查看：

- `settings-bottom-insets.png`：分组和卡片反复堆叠，诊断、记忆、语言、关于有相近的视觉重量；后续需要进一步合并导航分组及降低次要信息强调。
- `chat-keyboard-floating.png`：输入框已随键盘上移，不能继续声称此功能缺失；顶部模型名与两个快捷按钮、空态工作区按钮仍需一起评估层级，不能只单独缩小控件。
- `plugins-bottom-insets.png`：插件详情把长权限说明、配置、业务页、卸载与启停同时放入弹窗。应区分主要状态与操作、查看内容与危险操作，停用时说明如何启用，不能只摆一个不可用的入口。

这些截图只覆盖确定性测试场景；尚未审阅本轮修改后的截图，也没有完成整套视觉验收。

## 第一批实际修改

- 设置首页的“界面配置”同时收纳聊天外观与主题入口，移除首页展开的主题按钮和配色开关，显示当前主题名称。
- 独立外观页采用可整行点击的主题单选与动态配色开关；Android 12 以下明确禁用不适用的动态配色控制，保留原设置值。
- 保留现有偏好存储；页面开关使用可恢复状态。设置标题、说明、右箭头之间留出间距，行标题统一使用正文层级。
- 新增真实界面路径回归：从设置打开外观，选择深/浅色并读取持久值，返回重进保持选择，改变配色并返回聊天；生成设置首页及浅深色截图供审阅。

这只是首批设置调整，尚未完成整体 UI 打磨。聊天授权/队列、工作区和插件页未在本提交中修改。

## 上游与适配依据

`python3 scripts/fetch-upstreams.py --verify-only` 已核对锁定版本。

Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：

- `app/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/SettingsScreen.kt`：分类导航与简洁入口，外观详细控制由独立页面承担。
- `.../screens/ThemeSettingsScreen.kt` 与 `.../screens/theme/ThemeSettingsBasicTab.kt`：外观页按主题/配色/字体组织。
- `app/src/test/java/com/ai/assistance/operit/data/preferences/ThemeScopeMigrationPolicyTest.kt`：原有主题值不能因页面改造或角色状态被意外覆盖。本次不引入角色主题作用域或数据迁移。

DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：

- `packages/client/ui-settings-shell/tests/apply.client.spec.ts`：入口遵循宿主能力和生命周期，不能将无对应服务的设置做成可操作入口。

许可核对：Operit 根许可 LGPL-3.0，DSH MIT；本次只借鉴导航和能力反馈原则，自行使用现有 Compose Material 3 及 AppearanceStore 实现，未复制上游代码、引入依赖或运行上游应用。

## 验证记录

第一批实现提交 `0feccb333c633822333b8008dde678528010b6fb` 已推送，远程 [Actions 38973262745](https://github.com/veryy/Solvit/actions/runs/38973262745) build 已成功；设备回归仍在执行，尚未查看候选截图，尚未发布包含这些修改的 APK。本地不运行 Android/TS 构建与测试。已有插件生命周期修复继续留在独立分支，不能把它的结果计作本次 UI 验证。


## 第二批：插件管理的层级与可达性

实际读取锁定 Operit `ui/features/packages/dialogs/PackageDetailsDialog.kt` 的紧凑标题、独立滚动内容与删除确认；DSH `packages/client/ui-plugin-manager/src/client/PluginManagerPage.tsx` 以及 `tests/components.client.spec.tsx` 中 `opens a bundle’s page with its facts and rows, and uninstalls from it`、`confirms an uninstall by the package’s title and runs the action through it`，核对状态、版本、详情导航和卸载边界；另读 `packages/preset/agent-preset/skills/cordis-plugin-development/references/ui-plugin.md` 的继承宿主主题、页面清理与本地化要求。上游许可同上，未复制代码或增加依赖。

本批宿主管理界面修改：

- 已安装详情先显示状态与版本，插件页面采用明确的整行入口；停用状态提示原因，保留启用操作。
- 权限与工具上下文说明可展开查看，卸载位于独立分隔区；启用时的原生完整权限确认和卸载确认继续执行。
- 市场详情和启用确认的长正文支持滚动，避免说明过长挤掉底部操作。
- 设置和插件卡片降低背景强调，保持当前宿主配色。
- 新文案补齐六种语言。真实市场页面回归增加权限折叠/展开后进入页面；增加大字体、长权限说明和页面导航的实际 Compose 布局回归及截图。

本批尚未运行远程验证，不等于已完成大字体或视觉验收；第一批远程任务结果也不能替代本批验证。聊天与工作区的整体打磨继续保留。


## 第三批：聊天队列减少常驻操作

实际读取 Operit `ui/features/chat/components/style/input/common/PendingMessageQueuePanel.kt` 和 `ui/permissions/PermissionRequestOverlay.kt`，以及 `PendingMessageQueueStoreTest.kt`；DSH `packages/client/ui-conversation/src/client/queue/QueueDock.tsx`、`tests/queue-dock.client.spec.tsx`、`packages/client/ui-approval/src/client/ApprovalPanel.tsx` 和 `tests/ui-approval.client.spec.tsx`。采用队列折叠、独立编辑/移除动作和单次授权作为主要操作的原则；不照搬 Operit 的浮窗授权或 26dp 小点击区域。宿主仍在对话里审批，队列和执行状态机不变；未复制上游代码。

- 队列默认收起为数量栏，带展开/收起状态；展开后只突出“立即发送并打断”，编辑和移除收到每条消息的操作菜单。
- 长消息预览显示省略号，队列列表仍可独立滚动；已记住的展开和编辑内容只属于界面状态，不宣称支持进程死亡后的队列恢复。
- 单次工具授权改为主要按钮，拒绝与持续授权保留明确操作和权限范围说明。
- 原真实 Agent/权限/队列回归按实际新路径操作：收起状态确认发送按钮可见，展开、编辑、移除，再打断发送；新增授权等待、队列收起/展开且键盘可见时的实际截图。

本批尚未远程验证。后续仍需约束授权与队列同时出现时的总高度、整理工作区导航和统一插件业务页状态；不能因本次折叠就宣称聊天布局已全面完成。


## 远程跟踪（本轮收尾时）

- 设置分支 `0feccb333c633822333b8008dde678528010b6fb`：[38973262745](https://github.com/veryy/Solvit/actions/runs/38973262745) build 成功，设备回归仍在执行。下载并核对 `test-and-lint-reports` 归档 SHA-256 `7cc2b8331cb7fa868f52d4a4194ef52df2e9bc6dcfab65872e3ea0d370647630` 与 ZIP CRC，原始 XML 汇总 611 单元、0 失败/错误/跳过；构建步骤包括 Lint 和固定签名检查。候选截图尚未返回，不能称为视觉验收通过。
- 插件分支 `850d98b3`：[38974196177](https://github.com/veryy/Solvit/actions/runs/38974196177) build 仍在执行。
- 整合了前三批改动的聊天分支 `a0267f44`：[38974481747](https://github.com/veryy/Solvit/actions/runs/38974481747) build 仍在执行。后续优先核对这个整合候选，确认实际执行新增设置回归、两个插件布局方法和更新后的 Agent/队列路径，不能只看任务状态。

尚未合入 main、提升版本或发布新的正式 APK。仍需完成本批截图审阅与完整验证，再继续工作区导航、总高度约束和插件业务页的统一反馈。此前生命周期候选保留在独立分支；当前候选尚未包含它，不能宣称已消除原生崩溃。


## 第四批：工作区保留更多内容空间

实际查看已验证基线归档的 `workspace-terminal-keyboard.png`：顶端标题、目录名/模式、分栏、Shell 选择条依次占用四行，键盘上方留给终端的区域较少。实际读取锁定 Operit `ui/features/chat/webview/workspace/WorkspaceScreen.kt` 及 `WorkspaceManager.kt` 的按聊天绑定和独立内容区；DSH `packages/client/ui-workspace/src/client/WorkspacePicker.tsx` 和 `tests/workspace-picker.client.spec.tsx` 的当前选择、目录切换和忙碌状态反馈。核对许可后采用交互原则，未复制代码。

- 将当前目录与切换入口合并到工作区标题栏；终端模式操作收进同一标题栏，移除额外的一行头部，保留 Shell/目录/Git/Linux 分栏。
- 长目录名显示省略号，完整选择仍在目录选择器中；忙碌时目录切换继续禁用，不改变绑定与执行逻辑。
- 真实工作区辅助路径增加几何断言，确认目录切换、终端模式均在分栏上方且可见；已有真实 PTY/键盘截图会覆盖新的宿主布局。
- 收紧单次授权按钮的左右内边距，降低小屏中因强调按钮额外宽度导致换行的风险，实际效果仍需截图确认。

本批尚未远程验证。工作区创建/绑定失败时保留输入与明确反馈、插件页加载/错误反馈、授权与队列的总体高度约束继续保留，不以本次头部合并代替这些目标。


## 页面生命周期整合

四项定向任务的完整 runner 归档已核对，证据写入 `webview-user-lifecycle-stage.md`。整合无生产诊断日志的候选：首个页面 ready 后才推送状态，关闭时先撤销 live/ready，再由 WebView 销毁统一回收桥。保留延迟 ready、最小宿主和真实工作区的独立回归，并保留角色侧栏实际关闭的等待。新的设置/插件/队列/工作区布局必须重新通过合并后的完整验证，原定向结果不替代它。


### 键盘、授权与队列同时出现的边界修复

远程同 APK 定向场景 38975774497 失败：chat_tool_approval 顶部144px，角色标题底部167px，已发生重叠；截图还显示底栏遮住顶部操作区域。记录候选假设为 Scaffold 底栏使用整窗高度、IME中间帧、说明区域过长。首先只修布局预算：扣除实际测量的顶部栏高度，IME/导航栏仍由原窗口边距消费；待处理授权及队列共享可滚动区域，输入框保留独立位置。未缩减权限文本、未改授权行为、未隐藏队列。

读取锁定 DSH `packages/client/ui-approval/src/client/ApprovalPanel.tsx`、`ApprovalPanel.module.css` 与 `tests/ui-approval.client.spec.tsx`，以及 `ui-conversation` 的 QueueDock 实现和测试；采用有限空间内滚动详情、独立输入区的原则，Android采用实际TopAppBar测量与Compose权重。Operit `ui/permissions/PermissionRequestOverlay.kt` 的可滚动详情作对照。DSH MIT、Operit LGPL-3.0；本改动独立实现，不复制代码、不增加依赖。原复现断言与截图保留，远程验证待完成。
### 主题与分支断言的边界复核

首轮设置候选 Actions 38973262745 的原始设备报告为 99 项、3 项失败，不能视为通过。主题点击后首个即时 Selected 断言失败，尚未执行持久化断言；调整为先验证 AppearanceStore 的实际值，再等待匹配选中语义，不增加固定延迟、不删选中断言。分支原等待条件可由尚未关闭的编辑框满足，改为必须旧框消失且 chat_input 接收到相同草稿；原数据库/未自动发送断言保留。通过同一生产 APK 的独立远程复核区分产品错误与测试边界。Skills 导出截图仍在系统目录抽屉，原因待定位，未写作已修复。

### 同一生产 APK 定向复核结果

- [38976319451](https://github.com/veryy/Solvit/actions/runs/38976319451)：主题路径实际1项通过，先确认持久化值，再等待选中语义；取得深/浅色真实截图并目视核对。归档12078794605 SHA-256 `5a990e9d823c9d4a01b4b9b82d00c31f05dd3f7cbedaa36ae1ac6a2906eef8d1`、ZIP CRC已核对。这一结果支持原即时断言早于渲染，不代表整套UI已经通过。
- [38976319336](https://github.com/veryy/Solvit/actions/runs/38976319336)：保留先附件/HTTP/重开，再编辑旧问题分支的两项真实路径，通过。归档12078124643 SHA-256 `981d6f73459cf23588c1192f3b2181e6644dca6cfddb67a1a1f703d570341ff9`、CRC已核对；新断言同时要求编辑框关闭和输入框接收草稿。
- 插件候选38974196177、聊天候选38974481747的设备原始XML各101项、3失败、0错误/跳过，不能称为回归成功。两者均复现主题即时断言与分支选择歧义；插件候选另有抽屉关闭断言（整合分支已保留先前修正），聊天候选另有PDF重试滚动超时（待核验当前诊断）。Skills导出在这两轮通过，未据此删除首轮目录抽屉失败记录。两个归档分别核对SHA-256 `3453dd2a465e6d638973b3edc39e37287121091fe01983cc2c9684e62df9c72d`、`4394deb2d226a5b50d965d114504e680f55582d14edce4ac853f83262357167f`及CRC。
- 工作区/原生整合38975519683 build成功，设备部分被同分支下一次工作流并发规则取消，不计为通过。带底栏高度预算修复的整合头78cf8760由[38976601414](https://github.com/veryy/Solvit/actions/runs/38976601414)验证，尚待结果。


### 工作区表单：先固定失败路径

当前原生创建/绑定表单在发起操作后立即关闭，创建名称也立即清空，失败只能在背景工作区看到。新增真实Compose页面的受控服务边界回归，要求等待时保持表单、禁止重复提交，失败后保留名称或目录选择并显示错误、可重试；不以伪造成功替代绑定结果。先用现有生产APK验证失败，再修改实现。

本阶段重新读取DSH `packages/client/ui-workspace/src/client/WorkspacePicker.tsx` 与 `tests/workspace-picker.client.spec.tsx` 中 `disables every menu action from flow open through adoption`、失败与取消路径，Operit `ui/features/chat/webview/workspace/WorkspaceManager.kt` 的目录采纳失败反馈。采用单一进行中操作、失败保留可修正入口与取消不提交原则；Android需要在现有ViewModel/StateFlow中适配，避免绑定已成功但目录列举失败被误认为需要重建。已核对锁定checkout及根许可（DSH MIT、Operit LGPL-3.0），未复制代码或增加依赖。验证待执行。
