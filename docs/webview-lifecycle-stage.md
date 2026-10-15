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

新增最小实际设备回归`ComposeLifecycleDispatchTest`：以StandardTestDispatcher启动真实MainActivity，等待实际输入框就绪、三次重建，再要求规则的正常生命周期清理成功。保留导致先前30次压力在收尾失败的真实collector/Activity/rule链，单独验证旧依赖失败与候选新依赖通过，不使用模拟LifecycleRegistry或吞掉removeObserver异常。纳入正式设备计数下限app70，并提供独立compose-lifecycle-dispatch诊断入口。结果待实际远程执行。

最小回归在原正式0.43.2 APK上[38791332059](https://github.com/veryy/Solvit/actions/runs/38791332059)明确失败。原始归档12000172442（457584字节，SHA256 `6c06975be97b92a11bcc9f2ad2aa5ae15dfe61b9ce1ccd6f04de1e3e57cd1b4f`）完整下载/CRC：`ComposeLifecycleDispatchTest.recreatedActivityCleansLifecycleCollectorsOnMainThread`在正常规则收尾的removeObserver主线程检查失败，原栈同样经ApplyingContinuationInterceptor/FrameDeferredContinuation/TestCoroutineScheduler/withTestCoroutines，零Fatal signal与Snapshot错误。测试不以操作循环结束代替规则收尾成功。包含上游依赖修复及同一个新回归的候选eef47e23正在[38791334212](https://github.com/veryy/Solvit/actions/runs/38791334212)执行完整检查；261f18cb旧候选38790968793被新回归候选替代并自动取消，不算成功。还没有新正式APK。

候选eef47e23的38791334212在Lint失败，设备任务未执行。完整日志和原始报告11999389755（438798字节，SHA256 `157d2b8affcb91becce35f62a43535f36ee7421a6caf01b7859e5793d263e24c`）已下载/CRC/XML核对611单元零失败/错误/跳过；新的runtime Lint定位ConfigurationBackupDialog直接在composition读取StateFlow.value，3项错误。修复按钮绑定为collectAsStateWithLifecycle订阅chatReady/chatBusy，点击时的实时校验仍读取服务当前状态。不创建Lint baseline、不降低错误级别，不将单元通过当成整个候选通过。

修复后的候选a48f64c9在[38792853101](https://github.com/veryy/Solvit/actions/runs/38792853101)的build任务116621581773成功，测试/Lint/APK/固定签名及上传完成。新单元归档12001351485（437975字节，SHA256 `16df71b6999ed63555be8d2cedffbe0d4cbbd051017acd76399a5778440b1662`）完整下载/CRC/逐个原始XML与所有失败/错误/跳过计数核对611项。全设备任务116625693306仍在准备UI APK；尚未核对新生命周期回归通过。独立压力[38794154761](https://github.com/veryy/Solvit/actions/runs/38794154761)使用这个已完成构建的同一个APK，计划三轮各30次，首错停止；任务116626056454尚在编译测试包，不能写成90次通过。分支fix/compose-owner-dispatch保留候选；正式版本仍为0.43.2，没有提前合入依赖更新或发布新版本。


## 候选生命周期回归通过，完整回归仍失败

最小生命周期用例在新依赖候选上 [38795874700](https://github.com/veryy/Solvit/actions/runs/38795874700) 通过；原始归档12002755592（473946字节，SHA256 `f535972ba9cb00a7cdb0679b7fcea86f570eeae187b05eb8ff4357e618b5d91a`）完整下载/CRC，实际OK (1 test)、7.305秒，零Fatal signal/Snapshot。与旧APK的红色回归形成同一实际Activity/collector/正常teardown入口的红绿对照。这只证明拦截器生命周期问题修复。

默认dispatcher的候选压力 [38794154761](https://github.com/veryy/Solvit/actions/runs/38794154761) 在完成31次后、第32次出现原生SIGSEGV；原始归档12000953709（680252字节，SHA256 `e76dab5b1cceee8d8803f82399e59fd720784215a2f41794c8b1971ddd176b61`）完整下载/CRC，仍为WebView124相同pc0x357d7e5、BuildId68dabd72c6088238cbfc9a5fbfe211f916771df5。依赖升级不能单独宣称解决原生崩溃。

完整候选38792853101终态失败。原始UI归档12002256646（5109944字节，SHA256 `a7190cb39ff93cd5a2ed97750c22c4e6b1bc671b42793ca5be56b1c35751b25a`）完整下载/CRC/XML：app45项中2失败，Web3/终端25无失败；进程异常后没有完成app70下限。第一个失败是工作区创建入口等待名称框超时，尚未运行Shell命令；第二个是PDF附件导入的IO线程通过默认Unconfined测试dispatcher执行布局，与主线程竞争，原始THREAD_STATE链为ChatViewModel.importAttachment→StateFlow→Recomposer/TestMonotonicFrameClock→measureAndLayoutForTest。候选仅对已有直接竞争栈证据的ChatLayoutTest和PdfChatDeviceTest采用排队的StandardTestDispatcher，保留全部原断言，不修改生产Flow发布方式。

排队版工作区压力 [38795835076](https://github.com/veryy/Solvit/actions/runs/38795835076) 在第一次创建入口超时，完整次数0，不能算计划的240次通过；归档12001733959（480557字节，SHA256 `2452a6147adf81a1e5e2b2b62faf008b4c398f9610a34fae59be091d6aa24e74`）完整下载/CRC，零Fatal signal/Snapshot。下一轮247bf39f仅在该原超时处捕获页面及四个固定标签计数，重新抛出原异常；不增加等待时间，不重试点击，不采集用户文本或凭据。候选尚未合入或发布。


## 工作区创建的点击路径对照

原真实触摸247bf39f在[38797250770](https://github.com/veryy/Solvit/actions/runs/38797250770)再次于创建名称框超时，0次完整操作。归档12003680432（585928字节，SHA256 `14d130367bc70719daab2f2adff1078bb391b67e45faa6a9305b0e82e22cbf68`）完整下载/CRC，实际查看超时PNG仍是聊天首页；固定标签计数为新建工作区1、未绑定提示0、创建并绑定0、绑定已有工作区0。并非创建框被另一个工作区窗口遮挡。

89c936b8仅将这一次触摸替换为直接执行同一按钮的语义动作，保持相同a48f64c9生产APK、创建/绑定/关闭断言、Activity重建与正常收尾；[38798360630](https://github.com/veryy/Solvit/actions/runs/38798360630)成功。归档12003291979（545668字节，SHA256 `42c870cbce5db9f6be4b9ad4bb3f30012ec25f2d2982d5d82e9709abf50c6d37`）完整下载/CRC：OK (1 test)、74.689秒、30个完整操作，零Fatal signal/Snapshot。此对照只将故障缩小到触摸/调度路径，不将绕过触摸当作修复。

实际读取官方ui-test1.8.3的Actions.android.kt确认performClick使用performTouchInput；ComposeIdlingResource.android.kt只根据帧等待、快照与重组决定推进时钟，不直接检查排队的TestCoroutineScheduler任务。下一轮e85f3228恢复真实触摸，单独在点击后推进一个UI测试帧，再保留原10秒等待和全部断言；不重复点击、不使用直接语义动作作为最终验收。远程[38799278434](https://github.com/veryy/Solvit/actions/runs/38799278434)尚未完成，不能提前写成修复通过。


## 确认排队的侧栏关闭任务抢占真实触摸

点击后推进一帧的e85f3228在[38799278434](https://github.com/veryy/Solvit/actions/runs/38799278434)仍于第一次创建超时，0次完整操作；归档12003727374（567472字节，SHA256 `47357b4112401eefd816c9948375bf84808646826d0123144a61825385c9b951`）完整下载/CRC核对同一异常，零Fatal signal/Snapshot。该假设被否定，不保留这个无效改动。

将推进移到真实触摸之前，ef507892在[38800178350](https://github.com/veryy/Solvit/actions/runs/38800178350)通过：归档12003768443（569334字节，SHA256 `d301ad644b70c221193edc3c39b944178cff3834c4d76e99bb66b5096b2f8bd9`）完整下载/CRC，实际OK (1 test)、101.013秒、30个完整计数，零Fatal signal/Snapshot。边界日志确认第一次触摸前侧栏范围为Rect(0,0,809,1731)，推进测试时钟后变为Rect(0,0,0,0)。新对话按钮已调用，但StandardTestDispatcher尚未执行rememberCoroutineScope中的drawer.close；聊天背景节点虽存在，真实触摸先被仍打开的侧栏遮罩消费。直接语义动作因此能绕过遮罩，而在点击之后推进时钟已经太迟。

最终a8833ed0恢复原真实触摸/创建/绑定/关闭/重建路径与10秒断言，仅在获取点击坐标前推进一个UI测试帧，明确断言侧栏已不可见；移除WORKSPACE_CREATE_DIAG日志、超时截图捕获和直接语义动作。没有增加固定睡眠、点击重试、吞异常或改动产品按钮。清理后的完整Android门禁和8×30压力仍待实际执行；该测试调度问题与WebView124原生SIGSEGV不能混为已修复。


清理临时诊断后的a8833ed0候选在[38801340744](https://github.com/veryy/Solvit/actions/runs/38801340744)的build/116650858266成功，测试/Lint/APK/固定签名已完成。原始单元归档12005240519（438165字节，SHA256 `d9ae44dfa2b03c090391d9bfa6e9523082fc0be85264434ce1cd7f8959b0f462`）完整下载并通过CRC/逐项XML核对：611项，失败/错误/跳过均0。界面任务116654832079仍在准备测试APK；独立8轮压力[38801427414](https://github.com/veryy/Solvit/actions/runs/38801427414)任务116651161014仍在模拟器执行，尚无最终完成次数。未合入产品修改、未升级版本或替换公开APK。


## 清理后的真实触摸压力完成240次

候选a8833ed0的[38801427414](https://github.com/veryy/Solvit/actions/runs/38801427414)成功。原始归档12005077143（1268273字节，SHA256 `84554ff27b7297f38d5ec76e9bf2d98f188d1b9c4bc43e2a49efc93ec91863b9`）完整下载/CRC核对8份iteration均OK (1 test)，用时98.210、101.149、97.370、96.613、98.243、100.725、102.636、96.793秒。原始日志恰好240个STARTUP_RECREATION_COMPLETED、8个30/30终项，零Fatal signal、零SnapshotStateObserver多线程异常、零removeObserver主线程错误。真实触摸、创建/绑定/关闭、30次Activity重建以及正常规则收尾全部保留。

仍记录15次Chromium内部destroyed-WebView警告，不能由本轮无崩溃推出其根因已经消失。旧正式APK也有独立240次通过但其他轮失败；当前结论限定为清理后的候选压力通过。候选38794154761默认dispatcher的同原生地址SIGSEGV仍为真实失败证据。完整应用38801340744/verify-ui仍在模拟器运行，尚未合入或发布新版本；ARM64真机和真实模型任务边界不变。


## 完整候选在回复分支用例挂起，尚未通过

[38801340744](https://github.com/veryy/Solvit/actions/runs/38801340744)终态失败，build成功但verify-ui/116654832079在常规Gradle测试12分钟上限以124退出。完整下载UI归档12005223531（2251329字节，SHA256 `9a57eb31d4e9e1612725faced512a46a4caaca529c9da8ba2ab608b29ae938ed`）并核对CRC/原始XML：Web3、终端25零失败；应用仅11项正常完成，第12个replyBranchCopiesToolTurnAndRoleAndWorkspaceWithoutChangingOriginal自20:40:48开始后没有结束，XML含该未完成项及负时间，不能把“12项、failures0”当作通过。未达到应用70下限，release未执行，不能用240次独立压力代替整体验收。

实际日志未提供挂起线程栈。排序假设为侧栏滚动等待、测试dispatcher排队任务不被自动推进、分支或存储调用阻塞；需实际栈区分，暂不修改生产分支逻辑。新增单场reply-branch-hang诊断：保留相同已构建候选APK与原测试，60秒仍在执行时发送一次SIGQUIT，120秒原单场上限不变，归档logcat及ART的/data/anr文件。仅对一次性CI模拟器使用adb root读取诊断文件，不更改产品权限。a45dc66e初轮38805573819因补齐ART文件归档被主动替代，不能算成功或产品失败；执行以7306d88e新轮为准。诊断结束后清理临时入口/计时器。


单场诊断38805645701初次在NDK28.2下载的ZipFile unknown archive失败，尚未编译/执行设备测试；只重跑相同代码的失败任务一次。attempt2任务116666797091成功，归档12007195343（599367字节，SHA256 `d37422c9c55ed8f6561bd6dccf17c015b84e9471e79c03767020ad61d2800ab1`）完整下载/CRC核对，实际OK (1 test)、9.552秒。单场未挂起，不证明完整状态下的问题已解决，没有因此修改产品分支逻辑。

53d0cf0c的[38807004179](https://github.com/veryy/Solvit/actions/runs/38807004179)重放完整失败轮此前实际完成的11项应用用例，再接原回复分支用例；使用同一生产APK，300秒序列上限、首错停止，TestRunner明确开始目标用例后20秒才发SIGQUIT，避免在前置用例尚未结束时采错栈。原常规12分钟上限与应用70项门禁不变。单场和序列诊断均不替代完整回归；实际结果待回收。


顺序诊断[38807004179](https://github.com/veryy/Solvit/actions/runs/38807004179)成功。归档12006073993（1758958字节，SHA256 `3de68eb9ff06f4de214d96e8a76473a6f3c4e6879fe0fe5ecdde058002d99b2f`）完整下载/CRC，实际OK (12 tests)、48.448秒，TestRunner有12次开始/12次结束。仅重放先前11项常规用例未复现，不能因此宣称挂起已修复；完整失败轮此前还做过覆盖升级和15场恢复检查并留下更多会话。

继续用b1ccb325/[38807773768](https://github.com/veryy/Solvit/actions/runs/38807773768)检验长历史条件：沿用已有branchHistoryAtEnd诊断，目标回复分支用例先创建12个独立会话、将真实侧栏滚到末尾并断言目标问题尚不可见，再执行原查找/滚动/点击及全部分支、角色、工作区和原对话保留断言。finally逐个删除新增会话。不以直接跳到目标索引代替被怀疑的滚动入口，不更改生产代码；若挂起由同一入口产生，原ART采集器提供栈。这个条件与完整恢复环境并非完全等同，结果不能冒称完整回归通过。


## 长历史对照确认滚动动作与位置检查之间缺少调度推进

b1ccb325/[38807773768](https://github.com/veryy/Solvit/actions/runs/38807773768)失败；归档12006229893（500598字节，SHA256 `3c454511aa1ce392c8b7344c4ea0a01e66458dc1e565b8d28c6a8e22ef8ba6ec`）完整下载/CRC，实际失败栈是selectBranchFixture中的performScrollToNode报找不到目标，侧栏语义VerticalScrollAxisRange(value=3517,maxValue=3517)，处在列表末尾。这轮快速断言失败，未到SIGQUIT时间，也没有ART栈；不能谎称取得挂起线程栈或已完全复现12分钟挂起。

实际读取官方ui-test1.8.3 Actions.kt：performScrollToNode在lazy列表末尾先调用scrollToIndex(0)，随后立即重取节点并检查是否在末尾；ScrollToIndex/ScrollBy经宿主节点的协程执行。结合此前排队侧栏关闭证据，待验证假设是StandardTestDispatcher使滚动操作仍在队列中，后续检查观察旧位置。3e2fccbf只在selectBranchFixture替换滚动驱动：先回到开头并推进一个UI帧，10秒有界等待目标显示；若仍不可见，每次滚动半个实际视口后推进UI帧，再检查。不直接跳到目标索引、不重试点击、不变更生产分支/存储逻辑；真实选择、工具回合复制、角色、工作区及原对话保留断言全部保留。同一长历史条件正在复验，完整流程挂起是否同源仍待回归。


## 同一长历史回归由失败到通过，临时抓栈已清理

3e2fccbf的[38808995127](https://github.com/veryy/Solvit/actions/runs/38808995127)成功；归档12006988560（630953字节，SHA256 `aeb537b49d605b91caece71d51277743fd8174df2efca3870f42358a6a1490ec`）完整下载/CRC，实际OK (1 test)、11.2秒，零Fatal signal/Snapshot多线程异常。同一APK、12个额外会话、真实列表先滚到末尾且确认目标不可见，再回找、真实点击、复制工具回合/角色/工作区、删除原对话后保留分支及Activity重建均通过。这个红绿结果证明显式推进解决了该长历史测试入口的旧位置检查问题，仍不能单独证明完整流程挂起同源。

819bdef9将12会话与末尾起点作为普通回复分支测试固定条件，保留10秒有界查找和全部原断言；移除reply-branch-hang/sequence诊断选择、SIGQUIT后台轮询、adb root和/data/anr归档。源码扫描确认临时solvit_stack/WORKSPACE_CREATE_DIAG不存在。完整[38809890451](https://github.com/veryy/Solvit/actions/runs/38809890451)已由清理后提交启动，包含原覆盖升级、15场恢复、70应用/3Web/25终端下限；尚未结束，候选仍未合入/发布。
