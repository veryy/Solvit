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

## 标志通过但截图未通过，继续定位

218c09f7完整构建已通过628单元/Lint，生成同固定签名version101 APK（SHA256 4d63178f6995d57c2da757159ecf7db6d348e623c200e810c7cfdfff90eec03c）。定向39080590481以原设备断言在10.856秒通过，五张PNG及归档12119050608的大小/摘要/CRC已核对；测试文件与旧APK红测逐字节相同。

实际查看全部五张截图后发现不满足视觉验收：系统浅/应用深及重建的上下条带仍浅，白色状态图标因此不可读；跟随系统深色的条带才与页面一致。因此未交付此APK，也未将原标志绿色当作最终修复。旧断言验证窗口标志，没有验证绘制背景；App提交一帧亦不能单独保证系统UI绘制完成。

下一提交仅加强设备回归：等实际UiAutomation空闲后截图，读取真实系统栏insets，在实际PNG无文字左边距采样正文/状态栏/导航栏明暗，并在失败中报告Decor、android.R.id.content及首子View的实际屏幕位置/尺寸/padding、systemUiVisibility与fitInsetsTypes。保留全部旧标志/持久化/设置操作/重建断言，不改生产布局或添加延时重试。用同一218c09f7 APK区分系统UI过渡与窗口绘制范围问题；完整首候选流程继续保留，当前仍未交付101。

61e77fb4同生产APK诊断39081051928实际失败，归档12119390580（591924字节）的大小/摘要/CRC及原始runner已核对。空闲同步后正文/状态/导航背景亮度分别0.00647/0.95597/0.95597；Decor为[0,0]/1080×1920，但content与Compose首子View为[0,63]/1080×1731，未覆盖上下系统栏。实际PNG仍为深色正文、浅色条带，证明此场景不是只需等待系统UI的截图问题。后续主题场景未达到，不能记作全部失败或通过。

下一单变量候选仅在根主题SideEffect重新应用WindowCompat.setDecorFitsSystemWindows(false)，保留现有颜色、图标、Scaffold、IME及聊天padding。预测：若主题切换/设置返回期间根窗口布局契约丢失是必要条件，相同真实像素断言与场景应通过；若仍失败，保留结果继续定位，不叠加背景或边距修补。额外阅读AndroidX core1.13.1官方源码WindowCompat.java的API30委托及WindowInsetsControllerCompat.java的外观设置，只作源码参考、不复制；实际依赖解析与运行以远程为准。尚不宣称重新应用已修好问题。

4374123a重新应用候选的同包定向39082051408仍以完全相同的亮度与content范围失败；归档12119282596（575164字节）大小/摘要/CRC、原始runner已核对。单次根SideEffect重新应用不足以修复，不交付。其完整远程仍在运行；此前218完整流程由新生产候选触发的并发策略取消，不能计为全套通过。

继续在同一437生产APK上做明确标记的布局诊断，不立即叠加生产修改：分别记录返回后的原状、晚调用fit=false、再调用外观helper、最后再次fit=false，以及旧版layout标志的窗口范围和实际PNG。优先区分外观兼容API修改legacy systemUiVisibility的冲突（WindowCompat文档明确警告此混用）与设置返回后晚期重置；若late调用仍无效，再检查原生窗口/内容契约。该显式probe参数完成后必定失败，不能用其变更过的窗口替代普通root-theme验收；默认回归的操作与像素断言保留。生产源码不变。

同包诊断39082466688已完成全部五步并按设计以“diagnostic only, not acceptance”失败。归档12119268241（958610字节）大小/摘要/CRC与原始runner已核对：原状、late fit=false、之后外观helper、再次fit=false的content均[0,63]/1080×1731、flags0；唯独加入LAYOUT_STABLE/FULLSCREEN/HIDE_NAVIGATION后变为[0,0]/1080×1920、flags1792。实际查看前后PNG，正文布局与浮动输入保持，系统栏白色背景消失。证明这一API35/平台Material窗口组合中仅现代fit调用不足以传播全屏布局；没有证据支持外观helper本身重置布局或只需晚调用的猜测。探针不是正常验收，导航图标最终可读性仍需生产场景实际复核。

额外阅读AOSP android-15.0.0_r1的DecorView.java 1160–1240（系统栏消费/内容边距）、PhoneWindow.java 159–167、2499–2523、4107–4126（默认内容insets与fit调用）、Window.java 1562–1567，以及AndroidX core1.17.0 WindowCompat.java的API30/API35分支；Operit ui/main/MainActivity.kt 182–183显式设置原生背景防主题露底。源码只阅读，没有复制或增加依赖；AOSP/AndroidX均保留上游Apache许可边界。没有沿用Operit固定黑色背景来掩盖浅色主题或窗口范围问题。

生产修正只追加上述实测有效的三个兼容layout标志，保留现代fit契约，随后继续应用共享颜色/图标策略。标志控制绘制范围，不隐藏状态栏或导航栏；未修改Scaffold/聊天padding/IME。移除临时probe代码、参数和入口，恢复与61像素红测逐字节相同的正式设备测试。下一完整远程与同包定向必须重新通过，实际导航图标、深浅主题、重建及原有键盘/设备回归均继续检查，当前仍不交付101。

0b1190cf生产全套已开始后，继续只加强同包截图验收：探针末帧导航按钮仍偏暗，背景修复不自动证明图标可读。因此在原全部断言之后增加实际导航区域至少16个采样点相对背景达到3:1对比的检查（采样步长2，真实可见导航栏模拟器预设，兼容细手势条），失败元数据补充windowFlags。不改模型、窗口或原有断言，不增加测试方法；正常全套0b与加强定向的测试差异必须明确记录。只有实际生产APK上的对比检查及人工截图都通过，才能交付。

加强定向39083410212实际在导航对比失败：背景、图标请求标志已通过，但可读采样0；content已完整[0,0]/1080×1920、uiFlags1792、windowFlags0x80810100（DRAWS_SYSTEM_BAR_BACKGROUNDS已设置，无半透明导航/状态标志）。归档12120225187（561573字节）大小/摘要/CRC与原始runner核对，实际PNG已查看，深色导航按钮确实难读。后续主题场景未达到；完整0b尚在执行，即使旧背景检查通过也不交付。

读取AOSP15r1 DisplayPolicy.java 2380–2410、2452–2498（输入法/候选窗口决定导航外观）、LightBarController.java 206–244、425–450（实际深色图标与scrim/输入法控制）。继续同包显式诊断：限定输出窗口/系统UI外观字段，不输出整个系统dump或内容；比较原状、系统UI更长空闲、直接平台外观API、不透明导航背景、外观位切换、输入焦点退出。每步保存实际PNG与受限字段，结束明确失败，不能计作正式验收。生产源码暂不变，普通root-theme的背景与导航对比断言均保留。

cf638118的诊断39083864732实际完成六步后明确失败，归档12119228928（1095622字节）的大小/摘要/CRC、runner及过滤字段已核对。六张导航区域像素直方图完全一致；已实际查看空闲后与退出输入焦点PNG。WMS焦点/全屏/导航候选均为同一MainActivity，SystemUI各副本mNavigationLight/mHasLightNavigationBar/mForceLightForScrim/mForceDarkForScrim/mNavbarColorManagedByIme均false，应用requested0；输入法接管、scrim强制、单纯延后、直接外观API与不透明颜色均不符合结果。原同帧true→false操作可能被WMS合并，不能当作分别绘制过两种外观。

下一同包诊断补充实际导航tint转换状态与导航模式字段，拆开明暗请求为两次独立提交帧，并比较恢复系统动画及真实Activity重建，finally恢复原动画配置。阅读AOSP15r1 NavigationBarTransitions.java 183–201、232–243与LightBarTransitionsController.java 160–212、243–289、293–310：请求外观、导航模式与最终tint状态存在独立边界。当前不提前归因为应用或测试环境，也不以增加等待替代原实际图标断言；生产代码仍不改。

39084383238全部九步完成并明确以probe失败结束，归档12120181466（1446630字节）摘要/大小/CRC、runner及字段已核对。原状实际导航模式0，mDarkIntensity/mNextDarkIntensity0，transitionPending/tintChangePending/deferring均false；分帧light请求的intensity实际变1，再dark实际回0，但像素仍深灰。开启动画本身不改变像素；随后真实Activity重建的PNG才出现白色可读按钮，且requested0、SystemUI导航外观/IME管理字段仍false。已实际查看该帧：IME提示/返回按钮仍有过渡，不能把它直接当作正常场景通过。旧完整0b在动画关闭时的重建PNG依旧灰色，说明有环境/重建交互线索，但尚不能单独归因。

0b完整远程39082807604已成功：628单元/Lint、118设备（89/3/26）、15独立PID恢复、覆盖升级、150%聊天和实际stdio强杀均从原始归档12120690165核对。其测试尚无实际导航对比，因此不替代020失败，不交付。APK59666111字节SHA256 baf8992fae481af0c9b15b59155879864f7f7c5badbad83cae20c33339bbb6ce，固定签名与14内置包/目录已核对。

下一同包验证移除全部临时probe及入口，恢复020的完整原操作与全部背景/图标/导航像素断言；只在该用例开始时把系统三项动画scale设1，finally恢复原值，仍执行原本的初始化重建、真实设置切换、返回与重建，不插入额外清焦点、外观切换或窗口修改。不提前声称正常动画解决，更不改生产代码绕过SystemUI。若全场景仍失败，继续记录；若通过，也必须明确与关闭动画时失败的范围，进一步判断真实限制，不能泛化至所有设备与动画配置。


39085059302（2b7c10c3）正常动画的原完整路径仍在首个深色场景失败：实际导航对比采样0。归档12120487267（580324字节）大小/摘要/CRC及runner核对，实际PNG已查看；背景与请求状态通过，后续四种模式未执行，不能计作通过。开启动画不是修复，移除这一无效测试设置。

下一同包诊断只增加绘制端的过滤状态，不改变操作、生产代码或像素断言。阅读AOSP android-15.0.0_r1 Launcher3 quickstep/src/com/android/launcher3/taskbar/NavbarButtonsViewController.java 231–235、674–695、1021–1042及TaskbarManager.java 522–524：统一导航可由Launcher绘制，除SystemUI intensity外还有基于系统主题的Taskbar背景颜色覆盖。此前仅检查SystemUI未覆盖这一边界。若实际Taskbar存在且覆盖值非零，系统主题色固定现象与此相符；若不存在则否定此假设，继续窗口/绘制对照。上游Apache-2.0代码只阅读，未复制或新增依赖。尚未证明实际镜像启用此路径，不提前宣布根因。


39085794967（be7b896d）归档12120352462（565059字节）摘要/大小/CRC/runner及过滤日志已核对：实际存在Launcher TaskbarActivityContext/NavbarButtonsViewController，darkIntensity0，但背景颜色override1、multiplier1、slideIn0；SystemUI请求仍正确。此证据说明此前检查的SystemUI并不是唯一最终绘制端，不能再通过反复设置Window标志定位该灰色。阅读同版TaskbarDragLayerController.java 95–108、173–181、228–230：默认背景值及覆盖独立于应用请求；TaskbarBaseTestCase.kt仅mock控制器，无实际导航像素验收。

下一次只做显式Home→恢复同一Activity对照，记录前后Launcher覆盖值与PNG，最后明确失败为probe而不当作通过。用于区分模拟器启动/前后台初始化与持续的系统绘制契约，不改APK、不改原始像素要求，也不在产品中加入跳Home的规避操作。原完整场景验证仍须保留。


39086174474（3d227a27）的Home对照未完成：am start新建标准Activity，原测试Activity焦点等待超时，不能据此判断前后台对照结果。归档12120507774（593514字节）摘要/大小/CRC/runner核对，仅原截图，无返回后截图。修正诊断使用原taskId与am task focus（实际AOSP15 ActivityManagerShellCommand.java runTask/runTaskFocus调用setFocusedTask），恢复同一任务，不改变产品启动模式。另在返回后独立记录启用系统导航对比保护的像素/Launcher状态；默认保护是Android官方edge-to-edge文档支持的三键可读性机制。依次保留每步截图，只作区分且最终明确probe失败，不将修改窗口后的结果当作原场景通过。
