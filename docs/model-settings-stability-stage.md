# 模型设置字段与滚动验证（0.21.2候选）

0.21.1正式发布第一次尝试中的旧模型设置用例触发 `SnapshotStateObserver` 跨线程异常，发生于重新打开配置后 `performScrollToNode` 查找参数字段。原始堆栈保存在[运行38239629561第一次尝试](https://github.com/veryy/Solvit/actions/runs/38239629561/attempts/1)，原条件重跑通过不等于异常根因修复。

本轮实际读取 Operit 锁定版本 `ui/features/settings/screens/ModelConfigScreen.kt` 中 `ThinkingOptionEditor.editorKey` 与 `itemsIndexed(key=...)` 的字段身份方式，以及 AndroidX `Actions.kt` 的滚动实现。`performScrollToNode` 在查找后代时遍历语义树；`performScrollToKey` 通过键获取索引并在UI线程执行滚动。本轮采用官方按键滚动API，无直接复制上游实现代码、无升级Compose依赖。

模型名称和协议声明的参数现在具有稳定列表键。参数键包含协议ID及参数名称，避免不同协议或条件行插入后让参数菜单沿用另一位置的remember状态。已有配置、角色及密钥的持久化格式没有修改。

原有真实模型设置用例采用按键定位，仍验证保存后的加密配置值，并重复三次打开、滚到输出限制和思考参数、检查保存值及关闭。没有删除断言或捕获忽略线程异常。此前失败证据对应旧滚动路径，本轮仍需远程验证新路径；不宣称修复了AndroidX所有语义树读取问题。

AndroidX参考：[Actions.kt](https://android.googlesource.com/platform/frameworks/support/+/202b4bda8adfeb303ac30e359e8436a3752fe2b0/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/Actions.kt)、[官方滚动API](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction)。未运行本地Android或TS构建/测试，候选尚未发布。

同时实际读取同一 AndroidX 提交的 [ScrollToKeyTest.kt](https://android.googlesource.com/platform/frameworks/support/+/202b4bda8adfeb303ac30e359e8436a3752fe2b0/compose/ui/ui-test/src/androidDeviceTest/kotlin/androidx/compose/ui/test/actions/ScrollToKeyTest.kt)：覆盖视口内外、不存在的键、缺少语义动作，并由 `performScrollToKey_invokesActionOnOnUiThread` 明确检查滚动动作的主线程执行。采用已有API及其测试约定，没有复制上游测试代码。

候选提交 `523fe7276247d2a98ffd2401e90b7583a00429c9` 的[远程运行](https://github.com/veryy/Solvit/actions/runs/38244926746)已通过编译、Lint、签名指纹、覆盖升级、271个单元及42个模拟器测试（实际XML均无失败、错误或跳过）。报告产物11744761467和11746536081已下载核对，连续三次重开的模型设置用例及角色界面到请求用例通过。正式标签发布结果待补；一轮通过不是对全框架线程问题的证明。

## 正式标签首次失败与复核

[正式标签38246821214第一次尝试](https://github.com/veryy/Solvit/actions/runs/38246821214/attempts/1)的构建、271个单元、Lint、签名、插件及公开搜索通过。实际下载UI产物11746559079：42个测试仅1个失败，无错误或跳过；模型设置三次重开及角色请求链路通过，未出现原设置异常。失败发生在旧有 `PluginPageUiTest.packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin` 第126行：滚动后点击时找不到 `plugin_node_filter`。

本轮读取该测试、`PluginPageContent.kt`、`PluginPageScreen.kt` 及记忆插件 `pages.ts`，实际日志记录筛选前输入法弹出。区分三项预测：焦点带入视口与键盘布局争夺滚动则只丢失视口外按钮；插件刷新则页面版本/内容改变；页面关闭则出现关闭或错误状态。当前源码中输入文本只更新本地字段，点击前未派发页面事件，尚无页面刷新/关闭证据；也没有足够现场节点证据确认焦点竞争。整个运行失败终态后原条件重跑失败任务，结果待补，不以重跑通过冒充修复记忆页面问题。
