# 记忆搜索视野与返回行为（2026-10-17）

0.43.10候选的后续打磨，继续使用现有插件HTML/Cytoscape界面。前一轮仅验证双节点图谱，不能据此宣称大量记忆下的搜索视野已验收。本阶段先复现，尚未修改生产代码或发布新APK。

参考实现已用 `scripts/fetch-upstreams.py --verify-only` 核对锁定版本。Operit dbf71916 的 `app/src/main/java/com/ai/assistance/operit/ui/features/memory/screens/MemoryScreen.kt:354` 保持图谱挂载；`screens/graph/GraphVisualizer.kt:244–273,307–357` 保存视野及已有节点位置，根据节点/边变化处理布局。其测试目录没有对应图谱视野测试；读取 `api/chat/library/ChatMemoryWindowPlannerTest.kt` 只作为记忆上下文测试边界，不把它当图谱验证。DSH d7432673 的 `packages/client/ui-slots/src/index.ts` 及 `tests/core.client.spec.ts` 明确插件拥有视图注册契约；本项目继续由记忆插件控制图谱，不向原生宿主加入记忆业务。Operit LGPL-3.0、DSH MIT；未复制源码或增加依赖。

本地 `plugin-sdk/test/memory-pages.test.mjs` 已覆盖实际筛选与256节点/512边数据，但不验证浏览器视野。新增真实Android页面回归：写入33条原生存储记忆，打开实际插件图谱，使用实际Cytoscape视野API确定性重现移开画面，在150%文字下搜索单条，检查真实绘制边界与缩放，实际触摸节点打开详情并返回，检查查询和视野保留。没有替换图谱布局/坐标、页面或存储。诊断入口 `memory-viewport`，保留原有编辑/删除/取消回归。

待远程结果区分：旧视野保留、尺寸更新时序、读取坐标滞后。编译、设备结果、截图和APK状态以随后记录为准。
