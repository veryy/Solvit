# 插件页面阶段

以 `v0.19.0` 源码为起点，分支 `feat/plugin-pages`。保留既有业务、数据、签名和历史；旧版记忆管理入口在完整迁移前继续可用。

## 源码依据

固定版本见 `upstream-sources.json`，本阶段已读取：

- Operit `ui/features/memory/screens/MemoryScreen.kt`、`screens/dialogs/EditMemoryDialog.kt`、`screens/graph/model/GraphModels.kt`：搜索/目录、原文与标签编辑、键盘和底部按钮、节点/边数据模型。关系以真实数据为准，不能用展示布局推断关系。
- DSH `packages/preset/agent-preset/skills/cordis-plugin-development/references/ui-plugin.md` 与 `packages/client/ui-settings-shell/tests/apply.client.spec.ts`：插件声明入口、延迟显示、依赖到达/撤销及卸载清理测试。React/DOM 不直接移植到 Android QuickJS。

采用交互和生命周期原则，尚未复制上游实现；原生 Compose 接收 JS/TS 有界声明，业务状态和事件处理留在插件。

## 第一个实现检查点

SDK 增加原生页面节点、页面输入与事件类型；Android 增加独立的 `ScriptPageView` 校验器。支持文本、容器、卡片、按钮、字段、选择器和关系图数据。限制 UTF-8 字节、组件数、深度、字段长度及图规模；拒绝未知组件/字段、重复 ID 和悬空关系。

事件从当前显示节点派生 action，不能传入任意处理器名字；选择/图点击值必须存在于当前数据，表单字段必须属于当前页面。禁用按钮拒绝执行；声明确认的操作要求宿主确认标记，该标记不会来自插件回调。此层不授予存储/网络/文件/Shell 权限。

新增回归验证陈旧节点、捏造字段、禁用操作、未知选择、悬空关系、确认和有界输入；仅在 GitHub Actions 执行，结果待远程报告。当前未接入清单、QuickJS 页面实例、Compose 或市场入口，不能称为已交付插件界面。

## 后续与验收

继续接入页面清单、独立 QuickJS 实例、受限人工操作桥与取消/卸载；通用 Compose 渲染和插件详情/设置入口；记忆插件完整列表、搜索/角色类别筛选、详情来源、编辑删除、真实关系和任务入口。需要带页面的额外示例插件，证明无需专用 App 页面即可安装和交互。

最终验收包括 QuickJS→协议→Compose→事件→存储、权限即时撤销、关闭/卸载清理、后台等待时页面可响应、主题/语言/键盘/底部边距、旧记忆和升级兼容，以及远程单元、模拟器、Lint、固定签名和 APK 发布；真实模型/真机验收继续保留。
