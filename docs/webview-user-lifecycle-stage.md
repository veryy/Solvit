# WebView：不接管 Compose 调度的操作对照

诊断测试位于独立分支 `test/webview-user-lifecycle`（023223cb、b7be1fdc），尚未合入生产主线。本文件同步实际结果，不代表新增功能或已发布修复。

本阶段接在 [0.43.3 已发布](release-0.43.3.md)之后。旧 WebView124 原生 SIGSEGV 的失败签名、默认 Unconfined 测试布局竞争，以及 StandardTestDispatcher 下 240 次通过的不同范围，均继续保留在[原记录](webview-lifecycle-stage.md)。一次正式通过不能证明原生根因已消失。

## 上游实际阅读

用 `scripts/fetch-upstreams.py --verify-only` 核对固定提交，未执行上游程序：

- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `core/tools/defaultTool/websession/browser/WebSessionWebViewHost.kt`：容器与浏览器所有权分离，换容器前解除父视图，clear 移除子视图和布局监听。该锁定版本的 Android 测试中未找到对应宿主生命周期回归，不能冒称参考了不存在的设备测试。
- Operit Terminal Core `045868988e4091d613cb34b2941057703899b96e` 的 `terminal/main/TerminalScreen.kt`：实际宿主 Activity 与软键盘窗口配置成对设置、恢复。
- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `client/ui-sidebar-browser/src/client/electron/ElectronWebViewImpl.ts` 与 `tests/electron-lifecycle.client.spec.ts`：挂载、取消、迟到创建、释放只执行一次、重挂载和失效事件边界。Electron 设备/guest 实现不等于 Android WebView，不将其直接移植为原生修复。

只采用明确检查实际挂载/释放和正常收尾的验证思路；本次没有复制上游实现，不新增分发依赖或变更其许可证。现有 Android `HtmlPageProbe` 继续作为实际挂载视图的只读检查。

## 待区分的判断

1. 默认 Compose 测试调度引发的后台布局/时序影响原生浏览器：不安装 Compose 测试规则后，原操作路径可能不再复现；未复现仍不足以证明因果或真机安全。
2. 产品页面释放与更新竞争独立于测试规则：普通 Activity 上保留创建、快速切换、关闭及重建仍可能触发。
3. WebView124/x86_64 平台时序或渲染问题：如普通路径仍崩溃，需再用独立浏览器和实际签名对照；不能提前归因 Chromium。

## 本次操作路径

新增 `WebViewUserLifecycleTest`，不使用 Compose 测试规则、替换帧时钟、`setContent` 或服务模拟。以真实 `ActivityScenario<MainActivity>` 启动/重建，系统 `UiAutomation` 的无障碍树定位控件，实际 `Instrumentation.sendPointerSync` 触摸；工作区名称通过系统文本动作填写。使用实际模型配置服务发布就绪状态，临时中文与配置在 finally 恢复，不记录凭据。

每次重建后从侧栏新建对话，确认无绑定工作区，再真实创建。要求交互终端的实际 WebView 已挂载，立即切换命令任务；不增加 DOM-ready 等待来避开原快速释放时序。要求浏览器已脱离、命令输入出现，再关闭并确认聊天仍保留绑定。正常 Activity 收尾不由 Compose 规则驱动。状态查找均有 15 秒边界，不重试点击或忽略失败。

诊断先用正式 0.43.3 的同一生产 APK 跑 3 次，确认驱动有效；之后才决定是否执行原来的 30 次/轮、8 轮压力。新增两个测试选择，不改变生产源或 APK 版本；测试产物仍只由 GitHub Actions 构建。当前尚未运行，不能写成通过或根因已确定。


## 三次普通调度基线通过

023223cb/[38830043612](https://github.com/veryy/Solvit/actions/runs/38830043612)成功，正式生产源一致性检查通过，复用0.43.3/build86正式APK。归档12016917778（486701字节，SHA256 `818de778cf569187817860ae15688d054f8b1d83824621fe1c79be28fed495ef`）完整下载/CRC，实际OK (1 test)、13.458秒，USER_LIFECYCLE_COMPLETED恰好1/3、2/3、3/3，正常Activity关闭与测试finally恢复均执行。系统WebView仍为com.android.webview124.0.6367.219；零Fatal signal、SnapshotStateObserver或removeObserver。保留1次destroyed-WebView警告，不能将警告本身当作失败或据此宣称根因已消失。

使用同一023223cb测试与同一正式APK已实际启动[38830783282](https://github.com/veryy/Solvit/actions/runs/38830783282)，workspace-user-lifecycle-stress、8轮、每轮30次、每轮300秒。尚未得到实际完整计数，不能写成240次通过。未改变生产代码、APK版本、WebView实现或渲染模式，尚无新功能版发布。


## 普通调度同样复现原生失败

同一023223cb、正式0.43.3 APK的[38830783282](https://github.com/veryy/Solvit/actions/runs/38830783282)终态失败。归档12017048765完整下载，636726字节，SHA256 `ea95ac50b195d7ac372338b3daadab3decfb215f34c896fae42611a0f17b4574`，所有ZIP成员CRC通过。第一轮实际OK (1 test)、90.853秒；第二轮进程崩溃。原始logcat中PID2188恰好完成1..30/30，PID3204完成1..20/30，共50次；第51次尚未完成。不能写成计划的240次通过。

实际WebView仍为124.0.6367.219，SIGSEGV/SI_KERNEL128、pc0x357d7e5、libwebviewchromium.so BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5，与原失败一致。零SnapshotStateObserver/removeObserver，25条destroyed-WebView警告。没有Compose测试规则仍复现，否定“只由默认测试调度产生”的解释；不能据此区分插件回调、页面脚本与平台原生问题，也不能推断ARM64真机受影响。

## 下一项最小原生对照

新增诊断专用BareWebViewLifecycleTest：仍用同一正式APK、MainActivity和应用服务，但把实际Activity内容替换为原生FrameLayout/WebView，不加载插件HTML、业务桥或Compose AndroidView。重建Activity，挂载并加载离线HTML、实际排队一次脚本更新，再按产品相同的移除父视图→停止加载→移除子视图→destroy顺序立即释放；不等待DOM-ready、不延后destroy。检查真实attach、父视图和移除结果，finally正常清理，销毁后不再调用WebView方法。

这是粗粒度的移除对照；若同签名仍出现，可排除插件文档/桥是必要条件。若通过，只能说明这个较小负载未复现，不能证明平台安全或产品修复。MainActivity及应用服务仍存在，不能称为独立纯WebView应用。[Android官方destroy契约](https://developer.android.com/reference/android/webkit/WebView#destroy())要求先从视图系统移除、之后不再调用WebView方法；本测试遵守该顺序。上游所有权及实际测试参考延续本阶段开头记录，没有复制源码或新增依赖。仅远程构建/运行；生产实现与版本保持不变，结果待实际Actions回收。

原生对照已提交b7be1fdc并启动[38831963505](https://github.com/veryy/Solvit/actions/runs/38831963505)，明确复用正式38826293232的APK，8轮×30次，仍待编译与实际设备结果。生产源码一致性门禁继续开启；本次仅诊断测试/记录，不发布新APK，不把预期次数写成通过。


## 原生容器对照240次通过

b7be1fdc/[38831963505](https://github.com/veryy/Solvit/actions/runs/38831963505)成功。归档12018465940完整下载/CRC，1036952字节，SHA256 `b0364b5dbf029f49039ad214f1f19b1f5ba6e7a3091284363a7fed9758994974`。8份iteration各实际OK (1 test)，30.177/30.884/33.724/29.032/29.469/28.838/31.123/28.809秒；原始logcat中8个独立进程各精确记录1..30/30，共240次，WebView仍124.0.6367.219。零Fatal signal/SnapshotStateObserver/removeObserver/destroyed-WebView警告。未改变正式APK。

较小页面和原生容器未复现，不能证明平台安全，也不能将差异只归因某一个宿主方法：原生对照同时移除了业务页面、消息桥和Compose容器。下一步只恢复产品页面宿主组合，保持最小内容，判断业务终端脚本是否是必要条件。

## 产品宿主＋最小文档对照

新增PluginHostLifecycleControlTest：正常Activity/Compose调度，不安装测试规则；实际调用生产PluginWebPage，包含其AndroidView所有权、WebMessage监听、资源拦截和释放逻辑。只提供固定离线HTML、最小solvitPageUpdate与ready消息，没有终端脚本、工作区服务或授权模拟。每次Activity重建后设置实际内容，要求浏览器挂载后立即切掉页面并等待真实脱离，不加DOM-ready等待或延迟销毁。finally/ActivityScenario正常关闭。若同签名复现，业务终端页面不是必要条件；若通过，说明较小宿主负载未触发，仍需按实际失败路径继续定位，不能宣称修复。

参考仍为本阶段已读取的Operit容器所有权、DSH实际lifecycle测试，以及Android先移除再destroy契约；本轮只是细化对照，无上游代码复制、依赖或生产修改。编译和设备执行只用Actions，新结果尚待回收。

产品宿主对照已提交8bf24f02并实际启动[38833277294](https://github.com/veryy/Solvit/actions/runs/38833277294)，仍复用同一正式APK，8轮×30次，编译和设备结果待回收。原生对照通过不能替代产品失败路径或真实模型/ARM64验收。


## 最小产品宿主复现同签名崩溃

8bf24f02/[38833277294](https://github.com/veryy/Solvit/actions/runs/38833277294)终态失败；不是编译问题。归档12018332881完整下载/CRC，565427字节，SHA256 `4f9104de40a3175ab7edafdc89edcf168c5bd0cb329ccc83bb968520a2525237`。第一轮仅实际完成1..8/30，第9次期间主进程SIGSEGV/SI_KERNEL128，pc0x357d7e5、BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5，与产品操作路径完全相同；WebView仍124.0.6367.219。零SnapshotStateObserver/removeObserver，4条destroyed-WebView警告，其栈仅为Chromium loadingStateChanged/MessageQueue，不包含应用调用，不能据此确定根因。

这证明终端业务脚本、工作区服务和用户授权不是该原生失败的必要条件。仍不能区分产品WebView桥/资源/回调与Compose容器释放。继续读取锁定Operit BrowserWebViewSupport.kt 的onRenderProcessGone、closeSession、cleanupWebViewOnMain：其先解绑父容器/会话，再stopLoading、about:blank、onPause、removeAllViews、destroy；没有直接套用这些动作作为未知根因修复。上游对应宿主设备回归仍未找到，沿用DSH实际lifecycle测试对照，不宣称不存在的测试。

下一对照NativePluginHostControlTest仅在测试中将最小文档的桥/资源与释放组合放进原生FrameLayout：保留HTTPS离线资源、同一CSP、ready/状态脚本、WebMessage注册/移除、加载/渲染错误回调和一次性release，不用Compose AndroidView。该测试是对生产相关行为的诊断复制，状态简化为固定view，不代表完全等价实现；不注册终端权限或其他业务动作。使用same APK/Activity/版本，挂载后立即释放。若同签名复现，可排除Compose容器为必要条件；若通过，仍须在产品容器路径继续定点采样。未改生产或发布新APK，远程结果待回收。

下一原生桥/资源对照已提交1dcaceaf并实际启动[38834185607](https://github.com/veryy/Solvit/actions/runs/38834185607)，生产APK仍来自38826293232，8轮×30次；尚未完成，不报告通过。补充参考完整路径为Operit `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/websession/browser/BrowserWebViewSupport.kt`，没有复制其源码。


## 原生桥/资源对照240次通过

1dcaceaf/[38834185607](https://github.com/veryy/Solvit/actions/runs/38834185607)成功。归档12019275113完整下载/CRC，958540字节，SHA256 `7e5e1e768b8068d41e37bc535333f28f27f9243845429aded8eeded208de2508`。8轮各OK (1 test)，20.920/22.092/21.146/22.067/21.124/22.137/20.144/22.884秒；原始logcat中8个独立PID各精确完成1..30/30，共240次。WebView仍124.0.6367.219，零Fatal signal/SnapshotStateObserver/removeObserver，1条destroyed-WebView警告。该警告也存在于通过的原生路径，不能将其本身当作崩溃原因。

## 容器所有权候选

当前按预测排序：①若AndroidView销毁阶段仍接触已销毁的直接子View，保留原生容器、只销毁内层浏览器可能改变失败；②若产品状态更新/Compose组合时序独立触发，单独改变容器后同签名仍可能出现；③资源/渲染加载时序差异仍可能参与，原生较小负载通过不证明其安全。优先只验证第一项，不同时修改桥、资源、页面、释放顺序、销毁延迟或渲染模式。

候选以FrameLayout作为AndroidView持有的稳定视图，document仍拥有同一个WebView；release仍先失效令牌、移除消息监听、移除浏览器父视图，再stopLoading/removeAllViews/destroy。UI更新仍由live门禁调用浏览器，容器背景随主题更新。采用Operit WebSessionWebViewHost.kt中容器与活跃浏览器分开管理、清理内层所有权的原则；没有复制实现或引入依赖。DSH实际lifecycle测试的迟到回调/单次释放边界继续保留。

已有PluginHostLifecycleControlTest在正式APK上第9次失败，候选不改变该测试和断言，先等待远程编译，再用候选APK重放同一8轮×30次。之后必须回到真实工作区操作路径与完整设备/恢复/签名回归，不能用最小对照替代产品验收。候选未验证、未合入主线、未发布新APK；不得提前宣称根因或修复成立。

候选提交[5c7cffe3](https://github.com/veryy/Solvit/commit/5c7cffe320452b081d8947daf32d4d4b3cac4063)已推送独立fix/webview-owned-container分支，[完整远程38835247602](https://github.com/veryy/Solvit/actions/runs/38835247602)已实际启动，仍待结果。生产主线未合入；后续须用该轮完成的APK重放原最小宿主与实际工作区压力，不借用正式旧APK的对照通过结果。


## 容器候选已构建，原失败复测进行中

5c7cffe3的[38835247602](https://github.com/veryy/Solvit/actions/runs/38835247602)/build116762812326成功。单元归档12018863081完整下载/CRC，437845字节，SHA256 `8d6db26bcc67229cfcd5d4fb50a8936eae61392fa4733ee76a67c537496dd91e`；直接读取原始XML确认611项、失败/错误/跳过均0。完整任务日志确认4项报告门禁、Lint/assemble及固定证书f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3通过。候选APK已上传12018803400，尚未下载并核对完整APK，不报告其内层摘要。

完整设备回归verify-ui116765105368已启动；分支任务按现有规则跳过仅tag启用的插件/搜索/Linux和发布，不将其写成六项正式发布门禁通过。以该轮完成的候选APK，未修改PluginHostLifecycleControlTest、原断言或8轮×30次参数，已实际启动[38836042401](https://github.com/veryy/Solvit/actions/runs/38836042401)复测。旧正式APK在同一测试第9次期间失败；候选仍待实际结果，不提前宣称修复。后续保留真实工作区压力、完整升级/恢复、最终tag全部门禁、公开下载及ARM64/真实模型边界。


## 容器候选仍复现，撤回该改动

5c7cffe3的[38836042401](https://github.com/veryy/Solvit/actions/runs/38836042401)失败。归档12019446809完整下载/CRC，507903字节，SHA256 `6c0b29b1fcc88b544e48f2cf43ef150e0e27a7982d04326e8a47d66b478715e3`。第一轮实际完成1..27/30，第28次期间仍为SIGSEGV/SI_KERNEL128、pc0x357d7e5、BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5；零SnapshotStateObserver/removeObserver，2条destroyed-WebView警告。不能将完成次数增加解释为修复。原生容器对照与产品宿主仍有异步状态/加载时序差异；外层容器本身不足以解决。

额外读取Google Maven官方ui-android:1.8.3 sources.jar（SHA256 `63bd2776ad08453c2d84c5cb4ec936f3e52e595bedb2bc1bbcad11abaefbe2d1`），AndroidView.android.kt的releaseBlock与AndroidViewHolder.android.kt的onRelease/onDetach/onLayout/全局位置回调：它持有传入View引用，onRelease执行释放回调；这支持检查所有权边界，但没有证明原生崩溃时确实调用了已销毁视图，也不能据此声称AndroidX有该缺陷。没有复制上游源码或添加分发依赖。

独立诊断分支恢复原来的直接WebView容器实现，仅临时加入WEB_PAGE_LIFECYCLE_DIAG阶段日志：构造、load、资源开始/返回、ready/更新脚本、释放入口及监听/父视图/stop/子视图/destroy前后。日志仅使用自增实例编号、固定阶段和存活布尔值，不输出URL、文档、消息、配置或凭据。目的为判断崩溃紧邻哪个实际方法/回调，不再仅凭粗粒度对照猜修复；日志本身可能改变时序，若未复现也不算修复。最终交付前必须删除全部临时标记，并回到无日志的原失败路径验证。仅远程构建，尚待运行；容器候选不合入正式主线，不发布该失败APK。

临时诊断提交[b3341886](https://github.com/veryy/Solvit/commit/b334188601cae9268042589998b681c106cc100d)，[远程38837209253](https://github.com/veryy/Solvit/actions/runs/38837209253)已实际启动，仍待构建与实际重放。已验证失败候选38835247602的verify-ui116765105368为cancelled，剩余release也cancelled；不将其作为完整回归通过。正式主线仅同步记录，不合入失败容器或临时日志，没有新APK发布。


## 临时阶段诊断已构建，重放待回收

b3341886/[38837209253](https://github.com/veryy/Solvit/actions/runs/38837209253)/build116768902192成功。单元归档12019897773完整下载/CRC，437849字节，SHA256 `6976df2c0bfa9faadde0ae2b1f2daac3933aff3f2e45df65600bb4f64595d808`；原始XML确认611项，失败/错误/跳过均0。完整日志核对4项报告门禁、Lint/assemble和固定证书通过。该构建仅含原实现与临时阶段标记，不是已验证的修复；尚未完整下载诊断APK，不报告其内层摘要。

已实际启动[38837934088](https://github.com/veryy/Solvit/actions/runs/38837934088)，使用该轮完成的诊断APK、相同PluginHostLifecycleControlTest与8轮×30次参数。目标是关联原生失败与最后的创建/资源/消息/更新/释放阶段，不把预期次数或加入日志后的未复现当作修复。正式主线没有合入临时日志，没有新APK发布；真实工作区无日志重放、完整tag全部门禁、ARM64与真实模型验收继续保留。


## 临时日志复现：应用释放方法均已返回

b3341886/[38837934088](https://github.com/veryy/Solvit/actions/runs/38837934088)失败。归档12019469062完整下载/CRC，546455字节，SHA256 `aeb39782fecc95f64d61a2136013268934e14064f982b28d69f9b83d25ad3419`。实际完成19次，第20次期间同一SIGSEGV/pc0x357d7e5/BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5。492条阶段事件中所有before/after与resource begin/return均配对；第20实例destroy.after在02:02:37.668，fatal在02:02:38.843，相差1.175秒。因此没有证据显示某个已记录的应用释放方法尚未返回，不能把最后一个方法直接当作崩溃点。

第20实例没有message/ready事件，却在02:02:37.399已执行update.evaluate，页面仍未握手。按预测排序：①未就绪文档上的脚本队列可能参与，ready后才发状态应改变失败；②异步加载/渲染清理可能独立存在，ready门禁若仍失败需继续检查；③消息桥回收可能独立参与，当前日志不足以直接排除。下一步只改状态下发就绪门禁，不同时更改清理、容器或渲染模式。

再次实际阅读Operit锁定BrowserWebViewSupport.kt的onPageStarted/onPageCommitVisible/onPageFinished与注入辅助脚本，以及DSH ElectronWebViewImpl.ts的dom-ready/loadPending和electron-lifecycle.client.spec.ts的就绪前不加载、取消与迟到消息边界。借鉴明确的就绪状态和取消边界，不能把Electron的API直接移植为Android修复。Solvit已有docs/plugin-html-pages.md协议明确要求页面先注册接收函数，再发送ready；本次无上游代码复制或新增依赖。

先新增实际设备PluginPageReadyStateTest：页面已定义接收函数但故意尚未发送ready；宿主实际Compose状态改为Latest并确认可见提交，要求页面收到0次状态。随后发送真实ready消息，必须收到最新而非Initial；继续修改宿主时仍能收到After ready。不使用Compose测试规则或模拟浏览器，不要求重复状态去重。先用未修复的b334诊断APK运行同一断言，结果待回收；不能写成已失败/通过，也不能用该协议测试替代原生压力。


## 就绪门禁候选：仅改变脚本状态下发

新增按document生命周期隔离的pageReady状态，初始false，只有当前存活页面的真实ready消息才置true；关闭时清除。未就绪期间仍更新原生背景并保留latestState，但不向WebView提交状态脚本。ready回调一次下发当时最新的view/token/主题/语言/busy，之后实际状态变化继续下发。保留原直接容器、桥、资源、销毁顺序与阶段日志，单独验证“就绪前的脚本队列”预测，不同时改成about:blank/onPause或延迟销毁。

PluginPageReadyStateTest的旧APK同断言任务已启动；本候选还需远程编译、同断言通过、旧最小宿主原生压力和实际工作区路径、最终移除日志后完整回归。若协议回归通过但原生压力仍失败，只能声称状态下发修正，不能把它当作原生根治。暂未合入正式主线或发布新APK。

旧APK同断言任务[38839015993](https://github.com/veryy/Solvit/actions/runs/38839015993)使用24bbdf41测试与b334诊断APK；就绪候选[2e6f045a](https://github.com/veryy/Solvit/commit/2e6f045a659d0ea7fbc91635abe94e809267605d)的[38839204234](https://github.com/veryy/Solvit/actions/runs/38839204234)已实际启动。两者均待结果，不声称红绿已完成。原生诊断已回收，已请求停止旧38837209253剩余完整设备任务；其build成功和目标原生失败各自保留，不将该诊断轮计为完整发布门禁通过。正式主线仅同步记录，没有合入日志或未验证候选，没有新APK发布。


## 延迟 ready 旧版断言已失败，候选已构建

旧版同断言38839015993/116774552876已实际失败：PluginPageReadyStateTest第78行要求页面尚未显式ready时接收0次状态，实际收到1次。归档12021030201已完整下载并核对CRC，466033字节，SHA256 `8d76c1649996b1962ca29b9f91300b20c37f25aabd1047e047096bfe7cb3d7dc`；原始instrumentation输出为1项、1失败，4.810秒。这是状态协议回归的明确红灯，不能替代原生崩溃根因证明。

候选2e6f045a的38839204234/build116775147204成功。报告归档12020576260完整下载/CRC，437184字节，SHA256 `cbddbe95aea28ee783a46185a9585e9889e539031a40892dc00b566a0452989a`；144份原始单元XML合计611项，失败/错误/跳过均0。完整设备任务仍在运行，不计为完整发布通过。

已用该候选APK实际启动同一延迟ready断言[38839890509](https://github.com/veryy/Solvit/actions/runs/38839890509)及原最小宿主8×30压力[38839893183](https://github.com/veryy/Solvit/actions/runs/38839893183)，结果待回收。旧诊断38837209253的verify-ui116770886304与release已确认cancelled，build成功及目标原生失败记录保留。正式主线仍无临时日志或未验收代码，没有新APK发布。


## 就绪协议已红绿确认，原生崩溃仍然存在

候选2e6f045a的同断言[38839890509](https://github.com/veryy/Solvit/actions/runs/38839890509)/116777324395成功，原始输出1项、3.639秒。归档12020801561完整下载/CRC，460551字节，SHA256 `d6ee383fc08ac2e99952318a05072702d3b6b09d26484482be96b2800ebded0b`。结合旧版同断言预期0/实际1的失败，明确证明就绪前缓冲、ready后最新状态及后续更新生效。

原最小压力[38839893183](https://github.com/veryy/Solvit/actions/runs/38839893183)/116777327923仍失败：实际只完成1次，第2次期间同一SIGSEGV/pc0x357d7e5/BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5。归档12021281262完整下载/CRC，548943字节，SHA256 `7c8b2792c21368d6d616de7d24fadc31c09112a67f53de461e4f6caa47f8cf8f`。45条阶段事件全部配对；第二实例只有update.deferredUntilReady、无message或evaluate，destroy.after在02:25:08.076，fatal在02:25:09.290，相差1.214秒。它明确否定“阻止就绪前状态脚本足以根治”的预测，不能以协议回归通过宣称原生修复。

下一单变量诊断只省略显式removeWebMessageListener，仍立即关闭live/pageReady和正常destroy，检验异步消息桥回收是否参与；随后才分别考虑加载/渲染回收、Compose移除时序。诊断不得合入或发布为已验证修复。原就绪候选的完整设备任务仍在运行，主线尚无临时日志，没有新APK发布。


## 独立整理已确认的就绪协议修正

清理分支从正式main单独提取pageReady门禁和实际延迟ready回归；不包含四个生命周期诊断对照、临时阶段日志或省略消息监听移除的实验。就绪协议旧版失败/诊断候选通过已记录，但这份无日志源码仍需重新远程验收。独立报告门禁提高app下限为71，并要求实际存在PluginPageReadyStateTest的对应方法，不能由不相关通过项代替；新增一项报告门禁回归。常规设备预期71+3+25=99不是已完成结果。

发布若进行，仅说明“宿主在页面握手后下发最新状态”；保留WebView124/x86_64原生压力已失败及根因未定，不声称此修正解决快速切换原生崩溃。真实模型、ARM64及后续路线不变。仍须完整远程检查和固定签名APK，尚未合入主线或发布。


无日志分支843dd344的38841085736/build116781078497已成功。归档12021388072完整下载/CRC，437476字节，SHA256 `8bdb60755f948d17a823bc379f4f7251b8f9186a8f8f538833a05c8d2595ca30`；原始XML确认611项、失败/错误/跳过均0，完整build日志确认新增后的5项报告门禁、Lint/assemble与固定签名通过。verify-ui116782900173尚未结束，不计为完整交付验收。插件页面文档补充就绪前只保留最新状态、初始化完成再握手及旧页面失效边界；没有更改生产代码或测试。


## 原最小压力240次通过，整理无日志生命周期候选

a6b000de的[38841583915](https://github.com/veryy/Solvit/actions/runs/38841583915)/116782682402成功。归档12021727090完整下载/CRC，1100159字节，SHA256 `2f2018c3551119cca440028388c21978a2842e4509928069108bccc12538514a`；8个实际进程各完成1..30/30，共240次，8份输出各OK(1test)。时长29.001/29.617/30.135/29.203/30.304/27.805/30.100/29.262秒。实际provider仍为com.android.webview124.0.6367.219，零Fatal/SnapshotStateObserver/removeObserver；61条destroyed-WebView警告，进一步说明该警告并非此崩溃的充分条件。日志中实际省略240次显式listener remove。原真实工作区8×30压力[38842564272](https://github.com/veryy/Solvit/actions/runs/38842564272)已启动，尚未完成。

另，38840785119的常规verify-ui116782434495在sdkmanager下载system-images;android-35;default;x86_64时失败：Error on ZipFile unknown archive，模拟器未启动、应用测试未执行。这是基础设施失败，不是应用回归或一次通过；其已完成build仍有效，不能计为完整UI验收。

实际阅读Chromium124.0.6367.219、提交`7741f71cbce3b7dfe3eb5890976d2596364b0733`的原始源码与测试：

- android_webview/java/src/org/chromium/android_webview/AwContents.java：destroy先标记失效，再把destroyNatives排入UI任务；后者统一清理原生对象。
- android_webview/browser/aw_contents.cc/.h：AwContents以unique_ptr持有JsCommunicationHost，后者在整体销毁中回收。
- components/js_injection/browser/js_communication_host.cc/.h：RemoveWebMessageHostFactory先erase工厂，再通知frame；消息对象集合仍保留，正常析构则先销毁消息集合、后工厂集合。
- components/js_injection/browser/js_to_browser_messaging.cc/.h：消息对象保存AcrossTasksDanglingUntriaged raw_ptr工厂，首次PostMessage才CreateHost。显式移除工厂与首次在途消息之间存在与现象吻合的生命周期风险；未把未符号化pc直接宣称解析到此函数。
- android_webview/browser/js_java_interaction/aw_web_message_host_factory.cc：工厂和实际消息host持有Java监听引用，随整体宿主销毁清理。
- android_webview/javatests/src/org/chromium/android_webview/test/JsJavaInteractionTest.java的移除监听测试：覆盖首次消息已完成后移除、加载前移除、重复移除；不能冒称覆盖本次加载后/首次消息前的销毁间隙。

这些文件来自Google官方Gitiles，只读研究；BSD许可头已核对，无源码复制或新增依赖。该源码与单变量压力提供强关联，仍不能替代无日志实机路径验证或独立符号化归因。

无日志候选保留pageReady修正，关闭时仍先原子关闭live/pageReady，拒绝迟到消息和能力调用；随后移除父视图、stopLoading/removeAllViews/destroy，消息监听随浏览器整体所有权回收，不单独提前移除。没有任意延时、全局计时器或容器替换。保留原失败最小宿主和真实工作区方法作为常规实际回归，两者不接管Compose测试调度；连同延迟ready由独立报告门禁明确要求class/method，app下限73、常规预期101项，未运行前不写成通过。下一步必须远程build、无日志原最小/真实工作区压力及完整门禁；尚未合入正式主线或发布。
