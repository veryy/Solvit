# 主聊天页主题与系统栏一致性

2026-10-18承接已交付build100（main 32aab355）。上一目标轮已完成可下载APK、完整远程回归与主分支实际写入，属于实际进展；继续UI/交互与完整Agent路线，不将整个目标缩为本窗口。

## 实际源码与上游

九个锁定参考checkout已verify-only。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb的`ui/theme/Theme.kt` 143–240在主题组合中用SideEffect更新Activity窗口、导航栏与图标；649–657为颜色明暗函数。借鉴随应用配色更新宿主窗口的边界，不直接复制：其isAppearanceLight...赋值取反与注释/函数定义不一致，不能原样当作正确颜色契约。Solvit继续采用自身FullScreenPage已有的surface luminance判定。已读Operit LGPL-3.0 LICENSE，不复制实现或引入依赖。

已实际读取`ThemeScopeMigrationPolicyTest.kt`（主题迁移保留范围）和`ThemeTargetOperationCoordinatorTest.kt`（主题目标操作协调）；它们不是Android系统栏设备验证，不能证明图标可读。Solvit现有settingsIndexOpensThemeAndRetainsChoiceWhenReturning只验证设置页和持久值，未检查返回主界面的Activity窗口。

实际观察：MainActivity在onCreate调用一次enableEdgeToEdge，SolvitTheme随后按用户偏好选择配色；当前无根窗口随此配色更新的代码。FullScreenPage有独立窗口的系统栏更新。因此需要在真实主界面区分应用主题/系统主题，而非从使用测试MaterialTheme的聊天截图推断生产白边。

## 先复现再修复

新增SystemBarsThemeDeviceTest，运行实际MainActivity/SolvitTheme，不替换setContent或模型服务。通过真实设置按钮选择深色/浅色/跟随系统，返回主聊天并检查Activity系统栏图标、导航对比遮罩；保存提交帧后的真实系统截图。覆盖系统浅/应用深、应用深重建、系统深/应用浅、跟随系统的深浅变化；在finally恢复应用偏好和模拟器night模式。原聊天/角色/插件/工作区测试与数据迁移、稳定签名保留。

新增root-theme同生产APK远程诊断入口；App必需89（此前88），原回归不删。初始提交仅测试/诊断/文档，继续复用完整build100生产APK；尚未运行新回归，当前不声称已发现或修复生产缺陷。若复现，再按窗口更新/配置刷新/对比遮罩等可证伪假设逐项检查。全部Android与TS构建/测试只用GitHub Actions。

本阶段不涉及角色、消息、API密钥、工作区、插件包或存储迁移；真实模型任务、真机/ARM64、正式市场部署及完整路线继续保留。正式Release仍以已发布状态为准。
