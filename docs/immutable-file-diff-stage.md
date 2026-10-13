# 完整文件差异审阅阶段

0.25.0已正式发布，开始独立分支feat/immutable-file-diff。不是重写聊天或插件宿主，继续完整路线；真实模型、真机、角色偶发发送和完整任务仍待验收。

## 已阅读的真实上游

DSH固定d743267388641bc76f17c45ce8b4c231aed1d32c：deliverables/workspace-changes/src/capture.ts及tests/capture.spec.ts，保存写前/轮次后的完整文件、内容寻址去重、cap+1边界读取、absent/oversized/binary区分；超限双方不能视为相同。src/compare.ts及tests/compare.spec.ts采用diff structuredPatch、三行上下文与明确coarse超时降级，包含创建、删除、无终止换行和大规模替换回归。src/recorder.ts的capture/compared路径将diff来源固定为历史副本，不读取当前文件冒充历史。client/ui-deliverables/src/client/changes-diff.ts按session/seq/index获取比较，缺失与失败区分，失败可重试。package.json锁定参考当前依赖diff ^9.0.0；尚未为Solvit选定或安装此依赖。DSH MIT仅阅读，未复制其Node/Cordis运行时。

## 当前缺口与接续

Solvit FileObservations只保留最多6KiB文字片段；WorkspaceSnapshots每文件仅512-byte片段。现有审阅不是完整逐行diff；历史副本必须在写前/写后当时保存，不能通过重新打开文件补齐。

先用远程失败回归锁定超过片段上限后的变更、后续再次改写不影响历史、UTF-8与二进制/超限分类、不可变去重、会话隔离和回收。Android提供受限文件捕获与不可变内容读取服务；JS/TS插件承担差异组合和HTML展示，保留原生授权、路径边界、取消和明确预算。旧证据与SQLite数据兼容，缺失副本明确显示不可用。按已有阶段流程逐步提交、真实QuickJS→授权写入→SQLite重开→插件页面验收、正式APK；不在本地构建或运行Android/TS测试。

当前只有源码研究和阶段设计，未实现或验证完整diff，未增加新版本号。

## 原生缺口复现

58a8c19增加新接口和6个行为回归，未实现底层。[契约38425271122](https://github.com/veryy/Solvit/actions/runs/38425271122)编译成功，两个WholeFileCaptureTest均在captureText缺失处失败。该执行在原生失败后停止，SQLite四个新用例尚未执行，不能记作全部复现。补入原生严格UTF-8全文及同SHA引用和共享256KiB单文件/2MiB快照验证，继续远程测试原生和SQLite默认未支持读取的链路。
