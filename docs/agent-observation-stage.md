# API15：JS Agent 读取步骤事实（开发中）

0.23.4 已正式发布；这一阶段为独立开发分支，尚未编译/测试通过，没有0.24.0 APK或正式Release。
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
远程生成包、完整测试、Lint、APK、市场部署与真实模型验收仍待完成。
终端控制区偏拥挤及插件页面交互继续保留为后续产品改善事项，不把这次接口增加称为体验已解决。
