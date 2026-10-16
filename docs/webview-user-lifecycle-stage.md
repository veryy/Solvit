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
