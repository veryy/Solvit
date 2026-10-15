# WebView 生命周期排查

2026-10-16。初始基线0.43.1/build84；文档所有权修复已随[0.43.2](release-0.43.2.md)发布。原生SIGSEGV在旧版压力中复现，新版压力又遇到不同的Compose异常，排查仍未完成。以下保留逐轮证据。

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

## 原生崩溃已在原正式APK复现

1d4d91ef压力诊断38783699801终态失败。原始归档11997340813（623544字节，SHA256 `bd58ac51e5bd98d44325ecc0d0b825d09653a9cbde8d59a1f97a6e803c261b6b`）已完整下载/CRC及原始结果核对：第一轮74.775秒、30次重建全部通过；第二轮只完成2次，在第3次期间主进程SIGSEGV，instrumentation明确Process crashed，并首错停止。native pc仍为0x357d7e5、libwebviewchromium.so、BuildId 68dabd72c6088238cbfc9a5fbfe211f916771df5，信号code128/SI_KERNEL，r9=efefefefefefefef；与前两次正式失败同签名，不是另一项断言失败。此证据推翻“短循环未复现足够”的判断，但尚不能仅凭寄存器判定上层根因。

将只有测试/诊断改动的压力入口cherry-pick到main，默认仍保留原三次重建。接下来以0.43.2正式产物在相同系统WebView124运行相同压力；未切provider、不增加产品延时。诊断来源准入从“整个流程终态”改为严格检查build任务已经终态success，APK与native产物已上传完成后可与独立UI任务并行诊断；原来的生产源码逐文件一致性检查继续执行，正式发布门禁完全保留。

0.43.2正式构建单元原始归档11997171225（430442字节，SHA256 `60b92ca870bfd652bcd68df8028d32e4039fcb228f070f84d9771124f769d015`）已完整下载/CRC/全部原始XML核对611项，无失败、错误或跳过。任务日志核对4项报告门禁、Lint/assemble及固定证书f076abf8…通过。实际APK58955111字节、SHA256 `d70b99e301c965c2f2d3692c372354486ce29acb6ec55d93419f777c7a235b23`，正式归档及APK内层CRC通过，ARM64/x86_64 PRoot/loader ELF存在。源码ZIP1072711字节、14条目，SHA256 `8159edb249dd29762134baaf8aa886744af689264287535bc815df4eb441f766`，正式外层归档及内层CRC已核对。公开发布和UI仍待结果。

Ubuntu正式任务116589760648成功，已下载完整日志；其原有实际设备logcat门禁核对Python3.12.3/Node24.21.0/npm11.19.0、Java17、Skills/MCP Python与Node完整标记，日志实际输出Production版本元组。此次未重复下载整个38MB Linux归档，不声称已独立解析其XML或原始logcat。运行时代码未变，本轮专注页面行为的原始回归和正式安装包。

修复版240次压力对照38784831983的首轮在sdkmanager安装NDK 28.2.13676358时返回“Error on ZipFile unknown archive”，未进入设备或编译产品。完整失败日志已保留，按已确认下载损坏在新runner重跑同一个已终态失败任务；不修改版本、测试超时或产品源码，不把该前置失败当作WebView复现。attempt2仍在进行。

## 修复版压力失败，不能与正式检查混淆

38784831983/attempt2终态失败，原始归档11998447014（790198字节，SHA256 `c097c28afaf454d716add76234c60f9fc15631b7e45ba93cc023f12752d5063a`）已完整下载/CRC。第一轮121.731秒、30次成功；第二轮29次成功后，下一次绘制抛出SnapshotStateObserver previousThreadId49/currentThread main2的IllegalArgumentException，主进程退出。两PID1933/3102分别有30/29个完整成功计数，共59次；没有Fatal signal，存在10条Chromium内部destroyed-WebView警告。因此该失败与旧版SIGSEGV不同，不能归为同一根因，也不能称原计划240次通过。

保留原压力操作和异常默认处理器，下一轮只在测试中记录SnapshotStateObserver指定的另一个线程名与栈；唯一前缀SNAPSHOT_THREAD_DIAG，任何取证异常都在finally调用原fatal handler，不吞掉崩溃，不记录用户表单或密钥。排名假设是测试线程触发布局、后台资源回调访问UI、旧Owner跨重建复用；线程栈会分别反证，尚未改生产线程或升级Compose。官方SnapshotStateObserver源码明确其非线程安全，但这不是当前调用方已定位的证据。

## 0.43.2正式发布核对

[38783582460](https://github.com/veryy/Solvit/actions/runs/38783582460)六任务全部成功，代码35b91982。UI原始归档11997604017（7744481字节，SHA256 `3336b5b6218b4d107a3950cac2781937f26ca21609e709f5e09dda89ecb2976b`）完整下载/CRC/原始XML核对app69/Web3/终端25，共97项，无失败/错误/跳过，明确包含PluginWebPageLifecycleTest的文档实例/DOM断言。15份独立恢复verify、覆盖升级seed均OK；实际stdio宿主SIGKILL的host8774/supervisor8790/worker8791/descendant8792全部gone。独立报告摘要与原XML一致，不借用专项结果替代正式回归。

公开Release414144215/v0.43.2为非草稿/非预发布且latest，APK644795283、源码644795433的大小与GitHub摘要均与完整下载的正式内层产物一致。APK58955111字节、SHA256 `d70b99e301c965c2f2d3692c372354486ce29acb6ec55d93419f777c7a235b23`；源码1072711字节、SHA256 `8159edb249dd29762134baaf8aa886744af689264287535bc815df4eb441f766`。实际公开正文已补充两个不同压力失败及不能声称240次通过；不将正式绿色与独立压力失败相互覆盖。ARM64真机、真实模型和第三方依赖仍待验收。

公开 APK 与源码下载入口均另以范围请求返回 HTTP 206，并读取到 ZIP 文件头；完整内容校验来自前述正式 Actions 产物，不将范围请求视为公开文件完整下载。

## Compose 异常的实际竞争栈

继续读取同一失败归档11998447014的原始logcat，Android测试运行器已经导出完整THREAD_STATE：DefaultDispatcher-worker-4正在`AndroidWorkspaces.bind → save → StateFlow.setValue → ApplyingContinuationInterceptor → Snapshot.sendApplyNotifications → Recomposer → TestMonotonicFrameClock.performFrame → measureAndLayoutForTest`中执行TabRow布局。主线程同时在dispatchDraw读取同一Owner。这是比只有previousThreadId更强的证据；不是测试语义树打印栈，也不是页面资源回调。

实际核对Google Maven原始ui-test/ui-test-junit4 Android 1.7.6源码：ComposeUiTest.android.kt默认UnconfinedTestDispatcher；FrameDeferringContinuationInterceptor在父dispatcher无需调度时直接resume；TestMonotonicFrameClock在帧末执行measureAndLayoutForTest；MainTestClockImpl/AbstractMainTestClock将显式推进scheduler包装到UI线程。准备仅将ChatLayoutTest的effectContext设为StandardTestDispatcher，让IO发布者只排队、不直接执行重组/布局，保留全部原操作、断言、次数和超时；不更改生产StateFlow的正确后台发布方式、不升级Compose、不捕获产品异常。工作流38787642743仍在旧默认dispatcher上取证，保留独立结果。此项是否有效以相同压力复验为准，尚未称已修复，更不将其等同于WebView SIGSEGV根因。

源码来源：`https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test/1.7.6/ui-test-1.7.6-sources.jar`、`https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-junit4-android/1.7.6/ui-test-junit4-android-1.7.6-sources.jar`。只读取源码，没有运行本地构建或测试。

## 单换 dispatcher 的实验失败，已撤回

c157cfa9的[38788926124](https://github.com/veryy/Solvit/actions/runs/38788926124)终态失败。原始归档12000375188（548081字节，SHA256 `49305b69cd22cd3b18655bd6eb55a2001d1b6fbdeed0e24f784d099404f75d35`）完整下载/CRC核对：第一轮30个操作完成，未出现Fatal signal或Snapshot异常，但测试收尾在`repeatOnLifecycle.removeObserver → TestCoroutineScheduler → withTestCoroutines.runTest`抛出必须在主线程执行的异常。不能算第一轮通过，更不能写成240次通过。单换StandardTestDispatcher不足，已撤回；同配置旧APK对照38790008135和完整回归38789402515主动取消，不当作验证成功。

继续只读取官方源码：1.7.8仍保留ApplyingContinuationInterceptor的AbstractCoroutineContextKey，1.8.0/1.8.3已经移除。该差异可能涉及withContext(Main)无法正确替换被包装的拦截器，但需核对实际上游修复与测试，不能仅凭版本较新宣称解决；尚未升级生产依赖，也不以自定义清理时机掩盖测试收尾异常。

## 旧调度器专项完成240次，仍不能证明根因消失

取证轮[38787642743](https://github.com/veryy/Solvit/actions/runs/38787642743)成功，实际使用正式0.43.2 APK及原默认dispatcher。原始归档12000345762（1289690字节，SHA256 `af083bee39da26ccd03d1139ab51253fb0b98639cf77d4aa388a8ca8c0515114`）完整下载/CRC：8份iteration各OK (1 test)，原生日志恰好240个完整计数、8个30/30末项，零Fatal signal、零Snapshot异常；仍有53个Chromium内部destroyed-WebView警告。此结论只属于该轮，不覆盖38784831983的59次后失败，也不证明警告是原生崩溃根因或已修好间歇问题。临时SNAPSHOT_THREAD_DIAG处理器已经删除；原始日志本身已有所需竞争栈，不再留全局fatal handler。

已实际读取AndroidX上游[c500530fe429fb363e2aed6741a67c938b6f6891](https://github.com/androidx/androidx/commit/c500530fe429fb363e2aed6741a67c938b6f6891)源码与新增`ApplyingContinuationInterceptorTest`、`CustomEffectContextTest`修改：官方Bug358628457确认AbstractCoroutineContextKey使用错误，导致不能替换另一个ContinuationInterceptor。1.8.3官方Maven源码包含修复，1.7.6/1.7.8不含。后续在隔离候选分支对齐Compose BOM2025.06.01（UI/runtime/foundation1.8.3、Material3 1.3.2），保留默认规则先验证依赖修复本身；不同时引入自定义dispatcher或特殊teardown。候选未通过前不合入/发布、不宣称WebView原生根因已解决。
