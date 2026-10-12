# 记忆页面筛选点击诊断

0.21.2首次正式模拟器运行在旧记忆页面测试第126行找不到筛选按钮，原条件重跑通过。完整证据见[设置阶段](model-settings-stability-stage.md)，不能将该结果当作记忆页面根因修复。

本轮读取锁定Operit的 `MemoryScreen.kt` 的 `MemorySearchBar`（IME搜索提交后隐藏键盘），并复核Solvit的 `PluginPageScreen.kt`、`PluginPageContent.kt`、记忆插件 `pages.ts` 和原用例。之前读取的AndroidX `Actions.kt`、`ScrollToKeyTest.kt` 提供列表定位契约。Operit现有记忆单元测试覆盖数据与候选而没有该滚动/IME操作组合，不能当作该交互的上游回归。没有复制上游实现或测试代码。

三项预测按当前证据排序：输入框焦点/IME带入视口与滚动竞争，则页面仍在、只丢失视口外按钮；插件刷新，则内容或页面版本变更；页面关闭，则整个列表消失或出现页面错误。文本输入只更新本地字段，按钮点击前未派发插件事件，暂不支持刷新推断；仍需失败瞬间状态。

首轮诊断的原操作、十秒等待、滚动、点击和所有持久化/删除/禁用插件断言保持不变。仅失败时捕获各节点数量、可见边界、查询框焦点、输入法显示状态和截图，之后重新抛出原异常。诊断内容限模拟器固定测试资料，不增加应用运行时提示词或密钥日志。

手动Android工作流增加可选 `memory-filter-stress` 模式，连续20次执行同一个真实APK→QuickJS→SQLite→Compose用例，然后运行原完整UI套件。遇到第一次失败即停止并保存现场；不忽略失败，也不替代完整测试或发布门禁。默认push/tag仍运行原完整套件。各轮实际结果见下，未修改应用功能或APK版本，未运行本地Android/TS构建和测试。

## 原条件复现与下一项实验

诊断提交 `a6a72d0` 的[远程运行38252017215](https://github.com/veryy/Solvit/actions/runs/38252017215)构建、Lint、固定签名及271个单元通过（XML无失败/错误/跳过）。重复原用例在第1次就失败并停止，未运行其后完整UI套件。实际下载UI产物11749141984及日志：`stage=reopened-filter-click`，查询框仍有焦点，IME `mInputShown=true`，列表和查询框各1个节点，筛选按钮0个，页面错误0个；列表边界y=231..689，查询框y=335..503。截图显示输入框、类别及固定底栏占据视口，页面没有关闭。该证据排除整页消失，也不支持插件已导航到其他页面。

下一实验只增加输入后关闭原生键盘，再执行同一滚动和按钮点击。使用实际读取的AndroidX Test [Espresso.closeSoftKeyboard](https://developer.android.com/reference/androidx/test/espresso/Espresso)与 [CloseKeyboardAction.java](https://github.com/android/android-test/blob/main/espresso/core/java/androidx/test/espresso/action/CloseKeyboardAction.java)，后者等待InputMethodManager关闭结果；不增加固定sleep或放宽断言，不修改插件/应用布局。原条件已失败，下一次20轮与完整UI结果待验证。若通过，仅说明收起键盘后的程序滚动路径稳定，不宣称用户保持键盘打开时的交互或原生布局动画全被修复。

`cd0ac11` 的[运行38254299660](https://github.com/veryy/Solvit/actions/runs/38254299660)在测试APK编译时失败，下载build114828274183日志明确是 `espresso` 与 `closeSoftKeyboard` 不可见，未运行模拟器。Compose测试依赖不能当作该API的直接编译依赖；本轮显式增加 `androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")`。版本已核对[官方发布记录](https://developer.android.com/jetpack/androidx/releases/test)，与现有2024年测试库系列一致，仅进入测试APK。下一运行继续原实验，不把编译失败当作运行时假设结果。

## 收起键盘的对照结果与保留原交互的下一轮

`e5b66cb` 的[运行38255273459](https://github.com/veryy/Solvit/actions/runs/38255273459)全部成功。实际下载单元产物11750067044和UI产物11750303451，分别核对271个单元、42个完整模拟器测试均无失败/错误/跳过；20份单独instrumentation输出也逐份确认 `OK (1 test)`、无失败。该对照支持IME/可见范围影响，仍不能确定是焦点回滚还是匹配后代的滚动实现问题，也没有证明键盘打开的原操作已修复。

下一轮恢复键盘保持打开的原操作，仅将两处筛选定位改成AndroidX `performScrollToKey("filters")`。现有 `PluginPageContent.kt` 已按TS节点ID为列表项声明稳定键，记忆插件的 `filters` 是筛选按钮所在行。采用已读取的官方 `Actions.kt` 与 `ScrollToKeyTest.kt` 的按键定位契约，不修改原生页面/TS内容，不移除点击、编辑、重开、取消删除和最终删除断言。收起键盘的试验调用与临时Espresso直接依赖已撤回。下一轮仍需20次原键盘条件及完整UI验证，不以较容易通过的收起键盘路径代替。

## 保持键盘打开的定位实验结果与实际界面修复候选

`65a544d` 的[运行38259021676](https://github.com/veryy/Solvit/actions/runs/38259021676)构建通过，但原交互重复在第5次失败；前4次通过不能当作修复。下载UI产物11751773584后确认同一现象：查询仍有焦点、IME打开、列表存在、筛选不在可见节点中、没有页面错误。稳定行键不能独自解决输入框被带回视口的问题。

本轮读取Operit同一搜索栏的真实布局（输入框weight(1f)、操作按钮同一Row）及AndroidX锁定提交202b4bda8adfeb303ac30e359e8436a3752fe2b0的 `compose/foundation/foundation-layout/src/androidDeviceTest/kotlin/androidx/compose/foundation/layout/RowColumnTest.kt`，其中实际检查加权子项宽度和位置。采用相邻操作的布局原则，没有复制上游代码。

0.21.3候选把查询与筛选、清除按钮放在TS声明的同一行。通用原生渲染器对含输入框的行分配剩余宽度，按钮保留自身宽度；没有输入框的行仍可横向滚动。用户提交时先复制字段再隐藏键盘、清除焦点，防止结果刷新后重新拉回输入框。插件数据、权限和节点ID不变，记忆包版本1.3.1；现有真实UI回归额外检查筛选和清除按钮可见，不主动收起测试键盘。

先用显式手动包生成工作流远程构建TS，取回产物并提交到两处已有包目录。仅该手动生成模式允许生成文件尚未提交；普通push、调用和正式发布仍要求源码与提交包一致。随后仍需原输入条件20轮、完整UI、单元与Lint。当前候选未验证通过、未发布，不能宣称实际用户设备或角色模型效果已通过。

远程TS生成[38264853828](https://github.com/veryy/Solvit/actions/runs/38264853828)的SDK编译、类型检查和60项SDK测试通过，市场测试因固定版本断言仍是1.3.0而失败。保留失败，不跳过该断言：改成新版本1.3.1并从该运行已上传产物11754461130取回实际编译的记忆包，写入市场及APK资源两处。下一次普通门禁必须重新构建并核对已提交包。
