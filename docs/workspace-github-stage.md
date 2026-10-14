# GitHub 工作区阶段：后续实施与验收

本文件记录下一阶段依据和完整验收范围，不表示功能已实现。0.29.1已正式交付本地Git、同目录文件/终端与AI只读工具；远程GitHub、AI暂存/提交继续实现。完整方向保留在[产品路线](product-roadmap.md)。

## 已阅读的实际参考

- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：`examples/github/src/github/api.ts`、`contents.ts`、`local/terminal.ts`。其GitHub Contents API用文件SHA做远程写入，终端复用session；不能用远程单文件API代替本地Git工作树/索引及真实push。
- JGit `7.8.0.202609011348-r` / `f386af5bb89c42eec92ee1547fc3201d743165cb`：`org.eclipse.jgit/.../api/TransportCommand.java`、`transport/TransportHttp.java`，实际`api/PushCommandTest.java`的远程引用更新与`PullCommandTest.java`的快进/双方修改冲突用例。传输可显式注入CredentialsProvider、timeout、transport callback；跨主机重定向会清除预认证，但宿主仍须校验后续凭据请求的目标。
- 现有宿主：`EncryptedPluginConfiguration.kt`的Android Keystore/AES-GCM、插件ID绑定，`WorkspaceGitService`的对话绑定、资源关闭及宿主审阅，`GitPageBridge`的确认句柄。不执行上游App或本地构建。沿用已包内JGit及许可证，不引入TypeScript/Cordis运行时。

## 实施顺序

首个实现检查点：`GitRemoteEndpoint` 对外地址不含密钥，严格匹配仓库、HTTPS和端口；
`RepositoryCredentials` 是单次传输宿主持有的 JGit CredentialsProvider，拒绝其他仓库、
重定向目标、URL内凭据和关闭TLS的交互，close清除内部及已交出的密码副本。
这部分尚未连接克隆/拉取/推送，不能算GitHub功能已交付。纯JVM URL输入/诊断回归及
实际Android JGit认证类型回归由GitHub Actions验证，结果如下。
参考固定JGit CredentialItem.java 的setValue复制/clear语义、CredentialsProvider.java、
TransportHttp.java和HttpConfig.java；沿用已有依赖，不复制上游实现。完整传输、加密
存储、页面、进度取消、冲突恢复与AI写入仍按下列范围继续。

源6211100的[原生Git检查38488994347](https://github.com/veryy/Solvit/actions/runs/38488994347)
已成功，API26产物11860642936及API35产物11860558252均实际下载、检查ZIP CRC/XML：
各8项、无失败/错误/跳过，新增RepositoryCredentialsTest两项均执行。
这只证明真实JGit认证类型的仓库限制、拒绝交互及关闭清理，尚未证明HTTP传输或GitHub写入。
工作流的core:test执行通过；完整App分支门禁另行核对。

下一检查点加入原生GitCredentialVault：Android Keystore AES-GCM、每仓库AAD绑定、
存储键为仓库地址摘要，宿主仅公开是否配置；borrow为每次传输创建独立凭据提供器。
加解密临时字节/字符缓冲在finally清理，错误不包含密钥、密文或原输入异常。
实际Keystore重开、移除保持其他仓库，以及跨仓库密文复制拒绝回归已通过下面的远程检查。
尚未接到页面和传输，不表示克隆/推送已可用。

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


## 加密存储检查结果

源0188d82的[实际Android检查38490062790](https://github.com/veryy/Solvit/actions/runs/38490062790)
成功。API26产物11860954662与API35产物11861691375均下载、检查ZIP CRC/XML：
各10项、零失败/错误/跳过，两项GitCredentialVaultTest与两项RepositoryCredentialsTest
均执行通过。真实Android Keystore写入/重开/移除、密文跨仓库AAD拒绝已验证。
仍未接通HTTP克隆/拉取/推送或插件页面；不以凭据测试当作远程Git完成。

## 实际HTTP传输检查点

新增RepositoryRemoteTransport的fetch/push原语、IO拥有的HTTPS连接工厂与隔离配置。
只用批准仓库的smart HTTPS路径，证书与主机名校验保持开启，重定向关闭，
不继承仓库/全局认证头、Cookie文件、URL改写或credential helper。读写总量各限制128MiB。
推送只采用宿主提供的本地HEAD和远端审阅HEAD，非force，返回实际远程引用状态。
循环复核授权，取消立即断开本次连接，关闭传输和凭据；交付前取消也清理凭据。

已再次阅读固定JGit HttpConnection.java、Transport.java、RemoteRefUpdate.java、
FileRepository.java、HttpAuthMethod.java与实际PushCommandTest/PullCommandTest。
Android实现依赖已包内JGit，自行实现HttpConnection适配，不复制上游代码。
CI夹具运行真正git http-backend，临时TLS证书仅用于原生测试；插件无跳过TLS设置。
原生测试覆盖HTTP下载/提交/重新读取/非快进拒绝、双仓库冲突与双父提交、认证/重定向失败、
实际TLS等待期间取消及工作区文件保留。首次结果及修复记录如下，两个API尚未全部通过；当前原语尚未接到
BoundWorkspaceGit、插件页面或AI工具，克隆与pull的工作区安装/审阅流程继续实现。

首轮源5f839c8的38491692812，API26实际产物11861798360检查CRC/XML：
14项中4项HTTP场景失败，控制请求底层socket failed: EACCES。
git-android独立目标APK清单没有INTERNET权限，主App的权限不会继承到独立模块测试。
补模块清单权限后重跑原测试；不能将此轮网络未开始的失败当作Git协议已复现或通过。

补权限源e601d1f的38492134732，API35全成功；API26实际HTTP已开始，认证/取消
场景通过，两个需要checkout的场景失败：DirCacheCheckout.getContent:1504调用
InputStream.transferTo，在Android26抛NoSuchMethodError。固定JGit另一checkout调用
位于1568，同型PatchApplier:790也使用此方法。已有ASM兼容转换新增该签名到
AndroidInputStreams.transferTo，读取短块/零字节、写错误及不关闭任一流增加回归。
保持原始HTTP双仓库/冲突/非快进场景及两个API级别，修复结果待远程核对。


源e601d1f报告已实际下载并核对ZIP CRC/XML：API35产物11862033525的14项全通过，
四项RepositoryRemoteTransportTest均执行成功，包括真实HTTP引用更新/非快进拒绝、
冲突双父提交、认证/重定向与TLS取消。API26产物11862098565为14项/2失败：
仅checkout缺transferTo，认证与TLS取消均通过。修复源c358e95的38492680947正在
复验原HTTP场景与新增流语义回归，不以API35通过代替Android26兼容验收。


## HTTP与Android兼容检查结果

修复源c358e95的[38492680947](https://github.com/veryy/Solvit/actions/runs/38492680947)
两个API任务均成功。实际下载API26产物11862084663与API35产物11862775629，
检查ZIP CRC/XML：各15项、零失败/错误/跳过；四项实际HTTP场景及transferTo流回归均执行。
真正Git后端已验证下载、对象写入、推送后重新读取、非快进拒绝、冲突双父提交、
认证/重定向失败和等待TLS响应时取消；这仍是原生传输层，不是完整插件页面或真实GitHub账号验证。

接下来继续BoundWorkspaceGit对话/目录绑定、原生审阅与取消、加密凭据接入，
再接插件HTML页面和AI工具。克隆须保留工作目录及并发Shell写入；已阅读固定
CloneCommand.java:867–899，默认失败cleanup会deleteChildren已有目录，不能直接
把此流程用于聊天共享目录。下载/安装阶段需明确资源归属、复核目标及失败保留行为。
完整GitHub真实账号推送、克隆、pull/冲突恢复、页面及AI写入仍按原范围验证。
