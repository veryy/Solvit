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

## 对话绑定的远程服务候选

复核固定JGit `TransportCommand.java:35–100`的默认CredentialsProvider继承行为和
`PushCommandTest.java:testPushWithLease`的实际引用审阅方式后，新增独立网络授权的
WorkspaceRemoteGitService/Session。既有bind入口默认拒绝网络；bindRemote额外接受
精确仓库端点的宿主授权。BoundWorkspaceGit复核当前聊天和工作区绑定，fetch只下载
对象/更新tracking引用，不改变HEAD、索引和文件。推送审阅捕获本地版本和真实远端HEAD，
推送前复核暂存区/分支，保持force=false及expected-old远端引用。

Android服务按仓库从Keystore凭据库借出短生命周期Provider，未配置时显式匿名，
JS/model不接收凭据。进度为独立StateFlow；取消/关闭先取消拥有的传输任务，再等待
仓库互斥锁，网络连接由原传输所有者回收。测试新增真实TLS后端的绑定授权、
加密凭据取用、文件保留、陈旧索引拒绝、推送后重新读取和关闭阻塞传输场景。
新增专用bound/bound-slow仓库与独立后端已进入等待状态的观察接口，避免用固定延迟
代替实际网络路径。许可证仍遵循既有JGit通知，新增桥接代码为本项目实现。

本阶段尚未远程验证；不是插件页面交付。后续保留克隆、pull/合并冲突、
原生安全凭据输入、HTML页面、AI写入以及真实GitHub账户读写与APK发布。

## 拉取审阅与合并恢复候选

再次阅读固定JGit CommitCommand.java:345–375/610–630及
PullCommandTest.java:testPullConflict：冲突应保留MERGE_HEAD、解决后的提交同时保留
当前HEAD与合并父提交，仅在引用写入成功后清理合并记录。原Bound提交只允许SAFE，
无法完成冲突恢复，因此Revision增加仓库状态和mergeHeads，审阅期间变化会拒绝提交；
MERGING_RESOLVED允许显式提交双父历史，未解决冲突仍拒绝。

新增reviewPull下载后捕获本地HEAD/索引/分支与工作区文件、目录、可执行位摘要；
用户审阅后merge复核状态和字节，保留JGit快进、非快进无自动提交与真实冲突结果。
当前要求先提交工作区变化，不自动stash或覆盖未提交内容；审阅最多100000项/64MiB，
单文件8MiB，符号链接暂拒绝。新增实际HTTPS后端完整链：下载远端变化→Shell改动使
旧审阅拒绝且保留字节→冲突→审阅暂存解决→双父提交→推送→独立仓库重新读取。
另有合并父提交被改动时拒绝原提交审阅的Android回归。源码尚待远程验证，
HTML页面、克隆、真实GitHub账户与正式APK仍未交付。

绑定源ff445de的[38494028405](https://github.com/veryy/Solvit/actions/runs/38494028405)
Android26/35任务均成功。实际下载产物11862597585（35027字节）及11862447691
（38223字节）检查ZIP CRC/XML：各17项，零失败/错误/跳过；新增绑定授权、凭据、
陈旧索引、真实推送重新读取与关闭阻塞传输两项均实际执行。此证据仅对应ff445de，
后续5c86b28的拉取/合并和新增双父提交场景尚待运行，不能继承本次通过结论。

## 远程Git页面桥候选

复核固定DSH的preset/agent-preset/skills/cordis-plugin-development/references/ui-plugin.md：
插件保留业务布局，宿主主题/资源生命周期不由页面替换。沿用本项目已验证的
ScriptPageView显示控件→ScriptPageHostAction结构校验→单次消费→GitPageBridge
宿主审阅机制，新增fetch/reviewPush/reviewPull/push/merge/cancelTransfer控件；
URL只接受无凭据HTTPS端点，推送/合并只接受随机审阅ID，HEAD/索引/远端版本不由JS传入。
读查询不发起网络；控件调用中明确网络授权上下文，取消与进度读取绕过桥操作锁，
关闭先标记关闭并终止原生会话，再清理页面审阅。新增桥单次推送/伪造版本拒绝与
运行时API20/network显式声明回归，旧API19只能继续本地Git操作。

API20解析与TS类型为候选协议；当前市场仍请求API19、内置Git仍1.1.1/API19，
没有更新市场或替换发布APK。HTML远程按钮、宿主原生安全凭据输入尚未连接。
当前ScriptPageSession的dispatch仍串行，长操作的页面进度/取消还需独立生命周期任务
或宿主控制通道，不能仅凭桥锁之外的查询宣称实际页面取消已经可用。原生绑定服务
进度/关闭已有远程证据，新页面桥与运行时检查尚待远程构建。

拉取源1e539f9的38494644616：API35任务115627530745成功，实际下载产物11863567549
（37787字节）核对ZIP CRC/XML，19项零失败/错误/跳过；新boundPull真实HTTP链和
双父/陈旧mergeHead提交测试均执行。API26任务115627530935失败在模拟器系统镜像
下载：`Error on ZipFile unknown archive`，随后5554连接拒绝；测试未启动，无XML产物。
不能把镜像安装失败当作语义失败，也不能用API35替代API26验收。日志保留于本地
solvit-pull-api26-failure.log。本次诊断先区分下载不完整、缓存损坏与源镜像异常；
工作流增加显式系统镜像安装的最多三次重试（失败仍失败），原API26与测试断言保留。
下一运行同时验证页面桥新增代码，不隐藏或改写上述失败结果。

## 后台页面任务与HTML远程工作台候选

startRemote只接受已显示且确认的原生控件，内部再次校验fetch/reviewPush/reviewPull/
push/merge的结构，并创建页面拥有的Supervisor任务；立即返回随机操作ID，不把网络
放在QuickJS串行dispatch中等待。remoteOperation按绑定授权读取RUNNING/SUCCEEDED/
FAILED/CANCELLED及实际原生进度，操作结果的审阅ID继续只对应宿主保留的事实。
只允许一个活动任务，取消先取消任务，关闭取消并等待资源清理。新增控制的API20/
network检查保留。页面桥测试以实际进入的挂起点检查开始后可查询、重复开始拒绝、
取消结算及单次推送；没有用固定等待假设任务已启动。

Git1.2.0/API20源码HTML页面增加HTTPS地址/分支、下载、拉取与推送审阅和实际结果。
确认开始后每750ms触发只读进度事件，切换状态和pagehide清理定时器，取消仍经确认
控件；审阅后推送或应用合并只发送reviewId，非快进结果不显示为成功完成。
6语言文案同步，原文件树/CodeMirror/Changes保持同目录。SDK测试覆盖显示控件、
未确认不执行、启动/进度、宿主审阅ID与非快进结果。API19的Git1.1.1原始字节保存在
legacy，市场新增API20识别与旧包SHA核对回归；尚未部署远程市场。

本阶段包须由GitHub Actions生成再提交，当前源码与旧包暂不一致，不代表已发布。
原生安全凭据输入、克隆、实际页面模拟器与真实GitHub账号读写仍须继续完成。

c33d9d2的38495461515两个API任务已成功，实际下载API26产物11863741572
（37218字节）和API35产物11862419930（37573字节），检查ZIP CRC/XML各19项
零失败/错误/跳过；boundPull真实HTTP及合并恢复均执行。原API26镜像下载失败保留。
这对应页面异步任务之前的原生服务，不作为新HTML交互通过证据。

异步页面源b118e86的生成38496114351在TS类型检查明确失败：remoteOperation尚未
加入PluginContext.gitPage.query类型。实际打包步骤已通过；按日志补齐两项API20
只读查询类型，保留原类型门禁。生成任务的完整测试/包一致性仍待后续核对。

修复源f677024的[远程生成38496262208](https://github.com/veryy/Solvit/actions/runs/38496262208)
成功：打包、TS类型、SDK/市场测试、公网搜索、HTTPS MCP与Skills检查全部成功。
取回产物11863732277（822745字节）检查ZIP CRC，比较所有生成包，仅Git包变化；
Git1.2.0/API20、network声明与轮询资源已核对。生成原字节723797，SHA-256
`d24e188efd847661069d6ba972de110abd61f76b8bdffbf1bbb6f213ce04a36f`，同时提交市场目录
与APK内资产。API19原Git1.1.1保持SHA `7babc02c3e0feab6bf717bcc8de306dbd9f4896d20443217ab807858454378dd`。
生成模式允许更新包，不替代正常源/包一致性门禁；完整Android与页面模拟器尚待核对。
远程市场未部署，APK未发布，原生凭据输入及克隆仍未完成。

## 私有暂存克隆候选

复核固定JGit CloneCommand.java:867–905失败cleanup会删除原目录的孩子，
CloneCommandTest.java:testCloneRepository核对实际工作树/config结果。因此不直接
将上游CloneCommand用于聊天共享目录：ownTransfer允许尚未有.git的绑定目录，
先在应用cache的独立随机目录初始化/下载/检查对象树/checkout，随后复核绑定、
目标为空及目录dev/inode，复制时不使用REPLACE_EXISTING，最后移动.git元数据。
工作目录本身不重命名，Shell既有cwd继续指向同一目录。显式配置只保存无密钥HTTPS
origin，认证仍从每次借出的Keystore Provider读取。

checkout前拒绝符号链接、子模块、特殊路径，工作树最多100000文件/256MiB，单文件
32MiB；下载沿用128MiB传输限制。取消/失败只递归清理本操作的私有暂存目录，
不递归删除工作区。若安装开始后取消或碰撞，已复制工作区文件保留，尚未实现自动
恢复/回滚；后续应补实际页面提示和恢复路径，不能宣称原子安装。该限制不允许
通过删除并发Shell文件来简化。HTML增加“克隆到空工作目录”的确认任务入口。

新增实际TLS后端回归：已有文件拒绝；克隆成功后HEAD/config/工作树与原目录inode、
预先启动Shell读取一致；后端明确阻塞时真实Shell写入使安装拒绝且字节保留；
明确阻塞时取消保护并发文件且私有对象清理。测试使用独立clone/clone-gated/
clone-cancel仓库和观测/释放接口，未用固定延迟断言进入网络。许可证沿用JGit通知，
克隆适配为本项目代码。本阶段尚未远程验证，包须远程重新生成；原生凭据输入、
真实GitHub验证和APK发布仍未完成。

克隆源18eec53的远程生成38497224530已成功，取回产物11863599455（822903字节）
核对ZIP CRC与所有包，仅Git变化；同时提交市场与APK内的生成原字节724381，SHA-256
`b147bdb1d5300e87e22a6fbb81aae4a63292da3de5e3299c02e7a20ab290d7cb`。
其完整原生clone检查38497166043仍在运行，不把生成/SDK通过作为Android克隆通过。
