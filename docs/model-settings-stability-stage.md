# 模型设置字段与滚动验证（0.21.2）

0.21.1正式发布第一次尝试中的旧模型设置用例触发 `SnapshotStateObserver` 跨线程异常，发生于重新打开配置后 `performScrollToNode` 查找参数字段。原始堆栈保存在[运行38239629561第一次尝试](https://github.com/veryy/Solvit/actions/runs/38239629561/attempts/1)，原条件重跑通过不等于异常根因修复。

本轮实际读取 Operit 锁定版本 `ui/features/settings/screens/ModelConfigScreen.kt` 中 `ThinkingOptionEditor.editorKey` 与 `itemsIndexed(key=...)` 的字段身份方式，以及 AndroidX `Actions.kt` 的滚动实现。`performScrollToNode` 在查找后代时遍历语义树；`performScrollToKey` 通过键获取索引并在UI线程执行滚动。本轮采用官方按键滚动API，无直接复制上游实现代码、无升级Compose依赖。

模型名称和协议声明的参数现在具有稳定列表键。参数键包含协议ID及参数名称，避免不同协议或条件行插入后让参数菜单沿用另一位置的remember状态。已有配置、角色及密钥的持久化格式没有修改。

原有真实模型设置用例采用按键定位，仍验证保存后的加密配置值，并重复三次打开、滚到输出限制和思考参数、检查保存值及关闭。没有删除断言或捕获忽略线程异常。此前失败证据对应旧滚动路径，本轮仍需远程验证新路径；不宣称修复了AndroidX所有语义树读取问题。

AndroidX参考：[Actions.kt](https://android.googlesource.com/platform/frameworks/support/+/202b4bda8adfeb303ac30e359e8436a3752fe2b0/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/Actions.kt)、[官方滚动API](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction)。未运行本地Android或TS构建/测试。

同时实际读取同一 AndroidX 提交的 [ScrollToKeyTest.kt](https://android.googlesource.com/platform/frameworks/support/+/202b4bda8adfeb303ac30e359e8436a3752fe2b0/compose/ui/ui-test/src/androidDeviceTest/kotlin/androidx/compose/ui/test/actions/ScrollToKeyTest.kt)：覆盖视口内外、不存在的键、缺少语义动作，并由 `performScrollToKey_invokesActionOnOnUiThread` 明确检查滚动动作的主线程执行。采用已有API及其测试约定，没有复制上游测试代码。

候选提交 `523fe7276247d2a98ffd2401e90b7583a00429c9` 的[远程运行](https://github.com/veryy/Solvit/actions/runs/38244926746)已通过编译、Lint、签名指纹、覆盖升级、271个单元及42个模拟器测试（实际XML均无失败、错误或跳过）。报告产物11744761467和11746536081已下载核对，连续三次重开的模型设置用例及角色界面到请求用例通过。正式发布结果见下；一轮通过不是对全框架线程问题的证明。

## 正式标签首次失败与复核

[正式标签38246821214第一次尝试](https://github.com/veryy/Solvit/actions/runs/38246821214/attempts/1)的构建、271个单元、Lint、签名、插件及公开搜索通过。实际下载UI产物11746559079：42个测试仅1个失败，无错误或跳过；模型设置三次重开及角色请求链路通过，未出现原设置异常。失败发生在旧有 `PluginPageUiTest.packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin` 第126行：滚动后点击时找不到 `plugin_node_filter`。

本轮读取该测试、`PluginPageContent.kt`、`PluginPageScreen.kt` 及记忆插件 `pages.ts`，实际日志记录筛选前输入法弹出。区分三项预测：焦点带入视口与键盘布局争夺滚动则只丢失视口外按钮；插件刷新则页面版本/内容改变；页面关闭则出现关闭或错误状态。当前源码中输入文本只更新本地字段，点击前未派发页面事件，尚无页面刷新/关闭证据；也没有足够现场节点证据确认焦点竞争。整个运行失败终态后原条件重跑失败任务，结果如下，不以重跑通过冒充修复记忆页面问题。

## 发布状态

[正式运行第二次尝试](https://github.com/veryy/Solvit/actions/runs/38246821214/attempts/2)已全部成功，[0.21.2 Release](https://github.com/veryy/Solvit/releases/tag/v0.21.2)为非草稿、非预发布，最新版本API确认 `v0.21.2`。实际下载成功UI产物11747267156及正式单元产物11746118127，XML核对271个单元和42个模拟器测试均无失败、错误或跳过；记忆筛选、模型设置三次重开、角色请求链路都通过。公开APK已实际下载，与构建产物11745948120中的APK逐字节一致：23,067,851字节，SHA-256 `7ca187938d8539bcf28a47d355533e963487ecb323ad526288fd4ae5fc616da5`，亦与Release资产元数据一致。

本次设置用例在候选和正式首轮都通过；首次失败属于另一个记忆页面用例，其根因仍未确认。该问题优先继续诊断，同时保留用户真实模型角色回答、流式、搜索及真机任务验收。测试门禁通过不代替这些验收。

## 0.22正式门禁中的再次失败

0.22.1运行38298054367的第1、2次尝试都在同一处失败：协议切换后performScrollToKey已返回，model_name字段在performClick获取节点时不存在，ChatLayoutTest.kt:754。新版HTML记忆/多MCP及实际图谱PNG检查通过，不能把这两次设置失败记为已修复。公开搜索两次另遇Bing人机验证和DDG错误。原发布未产生APK。

读取Operit实际ModelConfigScreen.kt、ModelApiSettingsSection.kt及ComposeDslTextFieldEchoGuardTest.kt，核对Provider条件字段和编辑焦点状态边界；没有复制LGPL源码。当前Solvit协议按钮/菜单未清理仍在编辑的配置名称焦点，假设它导致IME重排及bring-into-view与列表滚动互相抢位置。聚焦原模型设置操作并增加协议选择后名称不再处于文本编辑焦点的回归，先在旧实现观察结果，再决定修复；不放宽超时、不跳过原滚动/保存/三次重开断言。

聚焦旧实现[38300906008](https://github.com/veryy/Solvit/actions/runs/38300906008)失败于新增焦点回归：期望0个编辑中的model_profile_name，实际1个Focused=true。确认协议选择没有结束旧文本编辑。候选在FullScreenPage内部取得实际Dialog的LocalFocusManager/键盘控制器，打开协议与Provider参数菜单前clearFocus(force=true)并hide，不清空输入内容。仍需验证原模型名滚动/输入和保存/三次重开，才能判断原失败是否解决；当前仅确认焦点缺陷，不把假设和最终根因混为一谈。
