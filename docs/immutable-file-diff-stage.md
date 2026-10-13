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

0.25.1正式38430642922的build实际368单元0失败/错误/跳过，插件、搜索成功；模拟器门禁失败，release被跳过，未发布。安全探针捕获首次角色发送attempts=0、请求=0，chat_send点击前bounds y1615..1752，点击后y840..977，键盘展开使按钮上移775px；没有执行到发送或Provider。修正测试先等真实IME可见且chat_composer.bottom <= decor.height - IME.bottom，再保持真实物理点击和原8秒回复/角色/新会话断言；不改生产角色请求、不换成语义触发、不增加回复等待来掩盖失败。保留探针，0.25.2独立标签验证修正，不覆盖失败标签。

0.25.2正式38432874699源码62870a5bfef29d8c8ae174b665dc7bc85489b0a9已发布，五作业全成功。实际下载报告368单元/49模拟器、均0失败/错误/跳过，固定签名和覆盖更新通过。APK artifact11831099268、ZIP20584024bytes，公开asset637670128，23,558,161bytes，SHA256 f198ceec6c284a1d749664871dc1cdc9fce2c72abc6e473b78b19c2598c920dc；公开8段下载与Actions逐字节相同。完整插件HTML差异及启动线程额外回归继续在feat/immutable-file-diff分支，不宣称存储阶段完成整项产品验收。
