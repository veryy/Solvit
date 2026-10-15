# WebView 生命周期排查

2026-10-16。公开基线0.43.1/build84；历史两轮系统WebView124原生SIGSEGV与后续未复现记录保留，本阶段尚未修复或发布。

沿用diagnose流程。此前单独重跑workspace-startup累计多轮未复现；本轮增加失败原始顺序中的七个现有场景，保留终端/PTY、记忆、编辑分支和插件管理前置，再到原有三次Activity重建/创建关闭工作区。仍使用正式0.43.1原APK和相同生产源码，遇首错停止，保留完整原生日志和provider信息。组合场景单轮300秒，其余原有场景120秒不变；不替代全套发布门禁。

源码审查另发现可独立验证的文档更换风险：WebView由remember(document)与DisposableEffect(browser)管理，但AndroidView没有相同身份键。新增真实设备回归在同一个组合位置更换两个离线文档，断言挂载了新实例且实际DOM/消息桥接收当前状态；没有先改生产代码。它不是原生SIGSEGV根因的证据，需要分别报告。

实际阅读上游Operit固定dbf71916的`ui/common/composedsl/ToolPkgComposeDslWebView.kt`（disposedRef、actionLane、onDispose与renderer回调）、`ui/features/token/TokenConfigWebViewScreen.kt`，以及DSH d7432673的`packages/client/ui-settings-shell/tests/apply.client.spec.ts`（销毁移除页面及延迟注册）。参考生命周期撤销与资源归属，独立适配，不复制LGPL源码。Android官方WebView.destroy契约要求先从视图树移除且销毁后不再调用；AndroidView的factory并不在每次update重新执行。后续修改仍需实际设备反证与回归，不能仅凭源码猜测宣称修复。

8dc2144b首轮38782140560/38782145576在源码一致性检查即被阻止，未执行设备：正式APK之后仅更新的plugin-sdk/README.md未在文档白名单中。将这个精确文件加入既有README列表，不允许其他SDK/运行时代码差异；随后重新执行两种诊断。不能把这次工作流前置失败当作复现。

原APK的文档替换[38782376759](https://github.com/veryy/Solvit/actions/runs/38782376759)明确失败：3.225秒内在新文档应挂载WebView的断言失败，实际视图树没有新实例。原始归档11996668266（483681字节，SHA256 `ec7fc67e3fd7fab2cc30b8bf1b86c8a1d35e717eae03617720967c03468909fb`）已完整下载/CRC并读取原始断言。这是文档所有权错误，不是原生SIGSEGV复现。

最小修复：按document为整段页面组合设置身份，旧消息桥、terminal确认和rememberCoroutineScope随旧页面一起离开；AndroidView的onRelease承担正常销毁，避免由独立DisposableEffect先销毁却留下旧视图节点。renderer错误仍立即清理对应实例，update核对live后才调用WebView。保留原origin/CSP/权限与不重放执行规则。新增回归纳入完整报告门禁（app最少69），待远程由红转绿和完整设备验证。尚不宣称原生SIGSEGV根因已修复。

原APK七场景[38782380747](https://github.com/veryy/Solvit/actions/runs/38782380747)已成功。原始归档11996074223（1790078字节，SHA256 `9d8bdb0ba6e753cbe2e3659bf088ba1815ddf5a38b9accc9972111a62a20d6f9`）完整下载/CRC及三份iteration核对，每份OK (7 tests)，共21场景、原有9次重建。完整原生日志没有Fatal signal，但有3次destroyed-WebView警告，因而不能把警告本身当成SIGSEGV复现。

分开保存三条可反证假设：文档身份未传给AndroidView会造成替换后无新挂载实例（原APK已复现）；快速终端模式切换/销毁时有迟到回调（原日志有Chromium内部loadingStateChanged栈，但不直接证明应用调用）；WebView124自身实现/图形状态影响复现率（尚未更换provider实验）。没有同时改变provider、关闭硬件绘制或删除场景。官方契约来源：[AndroidView的factory/update/onRelease](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)、[WebView.destroy](https://developer.android.com/reference/android/webkit/WebView#destroy())、[renderer退出](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail))。

已准备0.43.2/build85，代码35b91982，正式[38783582460](https://github.com/veryy/Solvit/actions/runs/38783582460)运行中，专项[38783587337](https://github.com/veryy/Solvit/actions/runs/38783587337)验证修复后的同一个文档替换回归。846989f4的main候选38783382950主动取消，由正式同功能标签完整门禁替代，不算成功。另建只含测试改动、生产仍与0.43.1相同的diagnose/webview-startup-stress分支1d4d91ef，[38783699801](https://github.com/veryy/Solvit/actions/runs/38783699801)执行每轮30次、三轮最多90次的原快速切换重建；首错停止，与新修复分开比较，尚待结果。

修复后的同一文档替换专项[38783587337](https://github.com/veryy/Solvit/actions/runs/38783587337)已通过。原始归档11997090912（454619字节，SHA256 `7af3de92b8392a15f13f220b04e2d5efe36c676b0dd4c9c5ed19b231faeb4844`）完整下载/CRC，result-1实际OK (1 test)。保留同一断言：新文档必须挂载不同实例，并实际显示second DOM及接收当前Lifecycle状态；原APK对应断言明确失败。此专项包未声称完成完整Linux/应用发布验收，正式标签38783582460仍在运行。
