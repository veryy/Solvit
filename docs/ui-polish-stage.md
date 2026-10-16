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

第一批实现提交 `0feccb333c633822333b8008dde678528010b6fb` 已推送，远程 [Actions 38973262745](https://github.com/veryy/Solvit/actions/runs/38973262745) 正在执行构建；尚未完成构建和设备回归，尚未查看候选截图，尚未发布包含这些修改的 APK。本地不运行 Android/TS 构建与测试。已有插件生命周期修复继续留在独立分支，不能把它的结果计作本次 UI 验证。


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
