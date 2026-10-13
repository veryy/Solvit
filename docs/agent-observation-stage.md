# API15：JS Agent 读取步骤事实（开发中）

0.23.4 已正式发布；API15已正式发布[0.24.0](https://github.com/veryy/Solvit/releases/tag/v0.24.0)，[正式标签门禁38382321416](https://github.com/veryy/Solvit/actions/runs/38382321416)全部成功。
build50，保留签名和数据迁移。没有运行本地Android或TS构建/测试。

此前API14只提供步骤命令和工具数量。API15增加 `agent.read` 权限、`observe()` 及 `decline(index)`：
读取当前用户请求、角色名称、模型正文/思考、拟调用工具参数与真实结算结果；插件可据此拒绝某个拟调用。
读取返回独立JSON副本，文字带截断标记，工具列表最多16项并带 `toolsTruncated`；完整工具协议与结算继续留在宿主。
拒绝不会触发审批、执行或TOOL_STARTED；拒绝事实按原顺序进入下一模型步骤。
API14或没有声明/授予agent.read的策略没有观察/拒绝桥接；模型配置、原生Provider签名不进入快照。

参考锁定DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：
`packages/core/agent-loop/src/agent.ts` 407–465的当前步骤与上下文，
`tests/loop.spec.ts` 80–120的流与提交结果，
`src/tool-calls.ts` 145–163及`tests/tool-calls.spec.ts` 885–947的原顺序提交和禁止调用不执行。
Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `ConversationService.kt` 480–516用于核对角色来源。
采用这些行为契约，用Kotlin宿主状态与QuickJS异步桥适配；没有复制上游代码或引入Cordis运行时。

新增受限参数审核示例：遇到截断参数即拒绝，而不是把不完整JSON猜成可执行指令。
回归覆盖独立快照、Provider原始签名保留、拒绝不执行/不审批、完整有序结算、较大工具批次的快照上限、旧API无观察桥和打包示例。
远程生成包与SDK（86项）、市场（2项）测试已通过；完整Android候选[38380638841](https://github.com/veryy/Solvit/actions/runs/38380638841)通过，正式标签门禁已全成功，真实模型验收仍待完成。
终端控制区偏拥挤及插件页面交互继续保留为后续产品改善事项，不把这次接口增加称为体验已解决。

阶段诊断：[38379777993](https://github.com/veryy/Solvit/actions/runs/38379777993)的真实Marketplace→QuickJS复现2个用例中1个失败：启用时拒绝缺少读取权限，但模型步骤中撤销读取权限后，策略仍成功读取并结束。XML明确记录“应抛出异常却正常完成”。先补复现再修改；桥接此前只看manifest声明，市场仅在启用时核验全部授权。
修复把实时授权回调接入独立策略，每个模型/工具/观察命令执行前、返回后都检查当前包身份、启用状态与完整授权，拒绝已撤销的访问。原停用/卸载取消与工具授权继续执行。修复后同一用例[38380789495](https://github.com/veryy/Solvit/actions/runs/38380789495)通过，下载的XML为2项、0失败/错误/跳过；完整Android候选也通过，原失败证据保留。

线上API15市场已部署工具参数审核Agent 1.0.0。HTTPS逐项核验API1–14目录与部署前一致；API15等于原API14目录加新策略。实际下载2825 bytes，与提交包逐字节一致，SHA-256 `528d480b937ea497c771e7cfcfc626e071bbce38a7433a0624151256a393ca4c`。服务备份 `/opt/solvit-market/.backup-api15-1791886507989`。这不是语义意图分类器：示例只拒绝被截断的参数，不保证模型不会提出错误工具调用。

公开Release APK已实际下载，与正式[solvit-debug-apk产物](https://github.com/veryy/Solvit/actions/runs/38382321416/artifacts/11804157399)逐字节一致：23,538,773 bytes；SHA-256 `eba4346fa51588f72dd29a0d8d4e1c19699935889791a4e7fe9cb00ac477058a`。正式单元XML为340项、0失败/错误/跳过；正式模拟器XML为48项、0失败/错误/跳过，覆盖升级、实际HTML页面与PTY检查均通过。
