# 完整文件差异审阅阶段

0.25.0已正式发布，开始独立分支feat/immutable-file-diff。不是重写聊天或插件宿主，继续完整路线；真实模型、真机、角色偶发发送和完整任务仍待验收。

## 已阅读的真实上游

DSH固定d743267388641bc76f17c45ce8b4c231aed1d32c：deliverables/workspace-changes/src/capture.ts及tests/capture.spec.ts，保存写前/轮次后的完整文件、内容寻址去重、cap+1边界读取、absent/oversized/binary区分；超限双方不能视为相同。src/compare.ts及tests/compare.spec.ts采用diff structuredPatch、三行上下文与明确coarse超时降级，包含创建、删除、无终止换行和大规模替换回归。src/recorder.ts的capture/compared路径将diff来源固定为历史副本，不读取当前文件冒充历史。client/ui-deliverables/src/client/changes-diff.ts按session/seq/index获取比较，缺失与失败区分，失败可重试。package.json锁定参考当前依赖diff ^9.0.0；尚未为Solvit选定或安装此依赖。DSH MIT仅阅读，未复制其Node/Cordis运行时。

## 当前缺口与接续

Solvit FileObservations只保留最多6KiB文字片段；WorkspaceSnapshots每文件仅512-byte片段。现有审阅不是完整逐行diff；历史副本必须在写前/写后当时保存，不能通过重新打开文件补齐。

先用远程失败回归锁定超过片段上限后的变更、后续再次改写不影响历史、UTF-8与二进制/超限分类、不可变去重、会话隔离和回收。Android提供受限文件捕获与不可变内容读取服务；JS/TS插件承担差异组合和HTML展示，保留原生授权、路径边界、取消和明确预算。旧证据与SQLite数据兼容，缺失副本明确显示不可用。按已有阶段流程逐步提交、真实QuickJS→授权写入→SQLite重开→插件页面验收、正式APK；不在本地构建或运行Android/TS测试。

此阶段不将存储能力当作完整diff/UI交付；接续JS/TS差异计算和插件HTML审阅。

## 原生缺口复现

58a8c19增加新接口和6个行为回归，未实现底层。[契约38425271122](https://github.com/veryy/Solvit/actions/runs/38425271122)编译成功，两个WholeFileCaptureTest均在captureText缺失处失败。该执行在原生失败后停止，SQLite四个新用例尚未执行，不能记作全部复现。补入原生严格UTF-8全文及同SHA引用和共享256KiB单文件/2MiB快照验证，继续远程测试原生和SQLite默认未支持读取的链路。

原生补入全文后38425960838下载XML原生2项0失败，SQLite默认读取四项均失败，编译成功。4f5ba0d补入schema9：全文BLOB与复合事件引用分表、SHA去重/回收、分块读取及读锁内前后授权、256KiB单副本/32MiB单run/128MiB全局内容预算；预算不足保留观察元数据但不伪造全文引用。38427087997编译成功，SQLite新7项有6项通过，唯一失败在夹具text.take(40)切断emoji代理对损坏元数据JSON；全文读取断言先已通过，修正夹具按codepoint截取，原完整内容/隔离断言保持。补验运行时原有凭据过滤不能被新全文绕过，尚未将完整diff/UI称为交付。

4338ae3的[契约38428189815](https://github.com/veryy/Solvit/actions/runs/38428189815)已走过原生与SQLite，失败仅在新运行时immutableFullCopiesCannotBypassJournalCredentialFiltering：既有preview过滤未覆盖新captureText。修正将敏感全文副本及引用一起省略，保留原观察hash/分类与已脱敏片段；普通正文及SHA保持，不把脱敏文本伪装成原文件。原assertNull/普通副本/源对象不变断言保留，继续远程复测。

beb49dd的[契约38428975426](https://github.com/veryy/Solvit/actions/runs/38428975426)成功。下载artifact11829493645（ZIP67956bytes）并核对CRC及实际XML：285测试、0失败/错误/跳过，原生全文2、SQLite全文7、新凭据过滤回归均通过。准备0.25.1/build53存储阶段APK，完整HTML差异页继续实现；正式门禁结果以发布后的实际报告补录。

4d13c7f增加ChatService全文读取转发及ExecutionChangesPageBridge：宿主固定对话/执行，初次查询冻结所选baseline/observed，不接受脚本选择会话、执行或路径；只读4096 UTF-16单位分块，保证不切开代理对，逐次授权并复验SHA。五个桥接回归覆盖超片段正文、后来追加观察不改变旧页、创建/缺失区分、伪造参数、摘要不匹配、读后撤权/切换对话及取消。尚未接入聊天页面。

依赖选择为官方kpdecker/jsdiff 9.0.0，BSD-3-Clause，许可完整保存plugin-sdk/licenses/diff.txt。核对官方npm元数据及tarball SHA512完整性，不执行本地npm安装或构建，手工加入准确package-lock条目（该包无运行依赖），远程npm ci核验。TS采用structuredPatch三行上下文、75ms预算；超时明确显示coarse整段删除/添加，长行按1024 UTF-16单位安全拆分，16段一页，不丢弃尾部或终止换行标记。独立回归覆盖2000行尾部编辑、创建/删除/CRLF、4000行明确降级、Unicode/HTML文字与128KiB页面预算；仍待远程验证及插件HTML接入。

0.25.1正式38430642922的build实际368单元0失败/错误/跳过，插件、搜索成功；模拟器门禁失败，release被跳过，未发布。安全探针捕获首次角色发送attempts=0、请求=0，chat_send点击前bounds y1615..1752，点击后y840..977，键盘展开使按钮上移775px；没有执行到发送或Provider。修正测试先等真实IME可见且chat_composer.bottom <= decor.height - IME.bottom，再保持真实物理点击和原8秒回复/角色/新会话断言；不改生产角色请求、不换成语义触发、不增加回复等待来掩盖失败。保留探针，0.25.2独立标签验证修正，不覆盖失败标签。

只读桥接[38431058558](https://github.com/veryy/Solvit/actions/runs/38431058558)报告artifact11830946755、ZIP68876bytes已核对CRC/XML：290测试0失败/错误/跳过，ExecutionChangesPageBridgeTest五项均通过。独立diff计算[38431700792](https://github.com/veryy/Solvit/actions/runs/38431700792)远程98 SDK、2市场通过。TS页141a4e2在38432405884先复现两个类型错误：SDK query联合类型未声明changes/changeContent；5ee97fb补入声明，不以as any绕过。其[38432771825](https://github.com/veryy/Solvit/actions/runs/38432771825)实际日志102 SDK、2市场0失败/跳过，npm ci/typecheck/包一致性/公开搜索检查通过。页面测试包含分块全文尾部编辑、缺失和可重试错误、创建与HTML字面量、256KiB转义长行跨所有分页且每个视图不超过原生128KiB/文本16384单位边界。

TS changesPage与diff模块已提交，但尚未在manifest注册或从聊天打开。下一步新增插件HTML入口与自绘增删行/行号，把jsdiff完整许可纳入插件资源，宿主只打开固定run只读桥接；补真实QuickJS/SQLite重开和模拟器HTML任务证据，再发布完整差异页APK。当前0.25.2的发布范围只有捕获/持久化和角色自动化点击时序修正，不能把纯TS测试称作实际页面或真实Agent验证。

追加序列诊断38432919598首轮3项1失败，失败是javascriptPluginUsesPersistentPtyAndDisableClosesItsProcess启动时LazyLayout的Choreographer要求Looper，堆栈直接来自SolvitApplication.kt:99在Default worker发布ready状态；其余两项（包括角色）通过，但不能记作十轮通过。独立分支调整初始化仍在后台、成功/失败的ready UI转换在Dispatchers.Main.immediate发布；保留全部三项原测试并重跑同序列。此修正不在不可变0.25.2标签内；不能冒称该版本额外十轮成功。

0.25.2正式38432874699源码62870a5bfef29d8c8ae174b665dc7bc85489b0a9已发布，五作业全成功。实际下载报告368单元/49模拟器、均0失败/错误/跳过，固定签名和覆盖更新通过。APK artifact11831099268、ZIP20584024bytes，公开asset637670128，23,558,161bytes，SHA256 f198ceec6c284a1d749664871dc1cdc9fce2c72abc6e473b78b19c2598c920dc；公开8段下载与Actions逐字节相同。完整插件HTML差异及启动线程额外回归继续在feat/immutable-file-diff分支，不宣称存储阶段完成整项产品验收。

269b068的[追加序列38434374173](https://github.com/veryy/Solvit/actions/runs/38434374173)成功。下载artifact11833565064（ZIP952205bytes）CRC通过，逐份核对result-1..10.txt均OK (3 tests)，实际10轮×3=30项全部成功，包含原PTY、大消息/分支和角色真实触摸/请求断言。支持ready主线程边界对该已复现序列的修正，但不代表真实模型角色或真机验收；该源码仍在后续分支，尚未加入已发布0.25.2。

HTML接入阶段继续读取固定DSH packages/client/ui-primitives/src/DiffBlock.tsx及tests/diff-block.client.spec.tsx、ui-tool/tests/diff-card.client.spec.tsx：插件拥有本地化、路径标题、增删配色、空行/终止换行和完整展开行为。采用其任务语义，不复制React/Cordis实现；Solvit原有页桥提供验证后的TS状态与主题/生命周期，插件review/main.mjs使用textContent绘制行号和字面正文、摘要信息默认折叠，不执行文件里的HTML。API17/工作区1.3.0注册changes页面，聊天同一消息内的默认折叠执行记录打开宿主固定run桥接。保留原1.2.0/API16原始包以保证旧目录；未在本地构建，后续须远程生成包、核对提交产物、真正QuickJS/SQLite重开及Android HTML回归，再发布APK。

执行页声明context:execution（API17且workspace权限），普通插件详情仅列出management页，并拒绝泛化openPage调用execution页，避免没有选定执行时展示无效入口或脚本自选run。BuiltinWorkspaceManager只接受宿主固定桥接，而且在缓存页回调、正文/资源返回前后核验当前对话；不因TS已缓存全文就跳过所属对话检查。新增双方清单校验及API16旧包不变检查。

### API16 bundled workspace upgrade regression

Remote Android run 38440313853 reproduced a real upgrade gap: of 73 marketplace tests, only `originalApi16WorkspaceUpgradesWithoutLosingItsSavedRuleSwitchOrGrants` failed (line 119). The byte-identical 1.2.0 package was missing from the original-package SHA-256 allowlist. Added its verified hash `545abc7b667f31468777e378d4c7620709f9e2e03fca6f4c1b490e24bd4b0bbe`; modified packages remain outside the allowlist. The regression retains saved configuration, enabled state and grants, rejects opening execution pages without a run bridge, and checks modified/uninstalled packages are preserved. Remote green verification remains pending.

Actual Android HTML diagnostic run 38439268360 passed `OK (2 tests)`; artifact 11834641603 (798123 bytes) was downloaded and ZIP CRC checked. Dark diff and light long-line-tail screenshots were captured. This is historical snapshot/HTML integration evidence, not a real-model task.

升级修复后的契约[38441197089](https://github.com/veryy/Solvit/actions/runs/38441197089)源码fae6203成功。实际下载artifact11835721907（ZIP70504bytes）、CRC及XML核对294测试，0失败/错误/跳过；两项真实QuickJS/SQLite差异回归、API17页声明与原版API16升级/配置/授权/修改包/卸载边界通过。

API17市场已同步服务器及1.3.0包，备份`/opt/solvit-market/.backup-workspace-1.3.0-1791913675286`。服务端逐份比较API1–16完整目录保持一致；公网核对API1–17选择正确，API16仍为1.2.0、API17为1.3.0，两个下载包与仓库原始字节一致（1.3.0：66826bytes，SHA256 95531ef7faaf38df4f1d56954505c848243bdcb2e4a773ac588b74e471ff037b）。其余包没有替换。

追加页面诊断38441201474第一次在准备NDK时因远程ZipFile unknown archive失败，尚未编译/执行页面测试；已重试该失败作业，不能算页面回归失败或通过。英文页面测试现同时设置真实LocalUiLanguage，额外检查副本详情为Saved copies，并滚动到长行尾部后截图；不是生产语言行为修复。0.26.0/build55候选ee713fa完整远程门禁待结果。

候选38441459467：build成功，完整UI门禁唯一失败为actualAndroidTsProjectRulesRefreshWithoutChangingRoleOrCreatingUnboundWorkspace保留旧版本1.2.0断言，实际内置1.3.0；不修改角色/项目规则/无绑定工作区断言。修正版本夹具后重跑全门禁。追加HTML诊断38441201474重试已成功，待下载新语言/尾部截图核对。
