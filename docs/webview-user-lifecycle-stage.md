# WebView：不接管 Compose 调度的操作对照

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
