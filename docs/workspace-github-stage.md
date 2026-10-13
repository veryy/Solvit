# GitHub 工作区阶段：后续实施与验收

本文件记录下一阶段依据和完整验收范围，不表示功能已实现。当前0.29候选交付本地Git、同目录文件/终端与AI只读工具；远程GitHub、AI暂存/提交继续实现。完整方向保留在[产品路线](product-roadmap.md)。

## 已阅读的实际参考

- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：`examples/github/src/github/api.ts`、`contents.ts`、`local/terminal.ts`。其GitHub Contents API用文件SHA做远程写入，终端复用session；不能用远程单文件API代替本地Git工作树/索引及真实push。
- JGit `7.8.0.202609011348-r` / `f386af5bb89c42eec92ee1547fc3201d743165cb`：`org.eclipse.jgit/.../api/TransportCommand.java`、`transport/TransportHttp.java`，实际`api/PushCommandTest.java`的远程引用更新与`PullCommandTest.java`的快进/双方修改冲突用例。传输可显式注入CredentialsProvider、timeout、transport callback；跨主机重定向会清除预认证，但宿主仍须校验后续凭据请求的目标。
- 现有宿主：`EncryptedPluginConfiguration.kt`的Android Keystore/AES-GCM、插件ID绑定，`WorkspaceGitService`的对话绑定、资源关闭及宿主审阅，`GitPageBridge`的确认句柄。不执行上游App或本地构建。沿用已包内JGit及许可证，不引入TypeScript/Cordis运行时。

## 实施顺序

1. 共享远程Git服务：归属当前对话/目录，明确仓库URL、远程名和分支；页面、AI调用同一服务，停用/关闭收回会话与网络任务。
2. GitHub认证：宿主加密保存凭据，插件页面可配置并查看已配置状态；JS/模型拿凭据引用和遮蔽状态，传输时由宿主注入，错误/日志/URL不包含密钥。默认不继承系统全局凭据或任意仓库helper。
3. 真实克隆、fetch、pull、push：操作前展示准确目录/仓库/分支/当前HEAD；保留已有本地文件和提交，不自动强制推送或覆盖脏工作树。网络进度、取消、认证失败和非快进结果在插件页面可见。
4. 冲突与恢复：呈现冲突文件、原始状态及处理入口；用户能在同目录文件编辑器处理，重新检查后继续。分叉不能只显示完成，也不能以支持快进代替完整冲突验收。
5. AI写Git：工具申请与聊天内授权使用同一原生服务；宿主保存差异/索引审阅句柄，校验对话、目录、分支、HEAD、文件和索引未变，执行后回传真实提交ID/远程结果。凭据、路径和审阅版本不由模型自行指定。

## 完成证据

- 真正的原生Git双仓库/HTTP传输：克隆、分支、fetch、快进、分叉/冲突、正确push及非快进拒绝；不能用Mock或GitHub网页返回200代替写入。
- 实际Android页面：配置认证、取消、进度、失败详情、文件/Changes/终端同目录；关闭或停用期间操作停止、旧句柄失效、重开仍可用。
- 实际QuickJS→授权→服务→仓库查询/写入，以及未授权、切换对话、撤权、旧审阅和隐藏参数拒绝。
- 真实模型项目修改→执行→修复→验证→审阅提交与用户批准推送，确认本地和远程提交；此项仍需有效模型凭据及实际任务，不以机制测试替代。
- 全部构建/测试用GitHub Actions；固定APK签名、覆盖安装保留配置和数据、旧市场目录兼容、公开APK与构建产物相同。及时提交、发布可用阶段，继续保留记忆/手机等完整范围。
