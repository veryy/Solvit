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

## 真实旧APK失败与窄修复

ee6b9fff的同生产APK定向39079602869已实际失败：选中深色并返回真实主聊天后，状态栏light标志实际true、预期false。归档12118431945的字节数/SHA256/CRC与runner失败已核对；实际截图显示深色聊天、浅色上下条带。后续重建和其他主题场景尚未执行，不能记成全部红测覆盖。

按优先级检查：①Activity未随手动配色更新（预测：将已渲染surface明暗绑定到Activity后，相同旧断言通过）；②系统导航对比遮罩（预测：关闭该遮罩后底部条带消失，但本身不修复状态图标）；③关闭设置后的边距重置（预测：窗口图标已正确时仍存在未绘制系统栏区域，需独立检查布局）。先只引入根窗口主题同步，复用FullScreenPage已有的透明系统栏/对比设置/图标契约；不修改现有decorFits、IME、Scaffold或聊天padding。若仍有条带，继续单变量布局检查，不提前归因。

AndroidX官方activity1.9.3源码归档的EdgeToEdge.kt 71–97/135–145/298–320也已读：默认auto读取resources.configuration.uiMode，设置导航对比遮罩及按系统夜间模式的图标。Solvit手动主题只修改自身AppearanceStore，因此单次onCreate默认调用不足以覆盖手动应用配色。来源是现有依赖的官方源码，只阅读不复制；Python下载因本机CA链失败，随后curl正常验证TLS取得源码，未关闭TLS检查，也未运行本地构建。

新增共享applySystemBarAppearance，FullScreenPage保持原行为，主SolvitTheme在SideEffect对明确传入的Activity窗口应用同一契约，以surface亮度确定图标。版本code101便于覆盖安装，versionName仍0.43.10。原真实设备断言不变；等待修复后的同场景及完整远程回归、实际截图、稳定签名APK核对。不能把条带的具体机制或更广泛真机兼容写成已证明。
