# 工作区 Git 与 GitHub

完整范围：同一工作区文件树/预览/编辑/终端/Changes，工作树与暂存区分别查看，选择暂存/取消暂存、提交/历史、克隆/拉取/推送及冲突处理；JS/TS插件页面与AI工具共用受宿主约束的服务，凭据加密且不传入页面/模型文本。当前仅开始底层Android兼容验证，尚无可用Git插件，不把历史执行快照当作Git状态。

先核对固定DSH d743267388641bc76f17c45ce8b4c231aed1d32c的packages/deliverables/workspace-changes/src/git.ts与tests/git.spec.ts：调用真实git、限制时间与输出、字面路径匹配、索引冲突与失败明确报告。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb的examples/github/src/local/terminal.ts实际通过Tools.System.terminal执行命令，github/contents.ts通过GitHub内容API带版本SHA更新文件；后者不能代替本地Git索引与整个项目提交。

Android基础系统不保证git可执行文件存在；不能仅给Shell拼接git命令就认为本地Git可用。核对JGit官方仓库v7.8.0.202609011348-r，实际提交f386af5bb89c42eec92ee1547fc3201d743165cb，pom.xml声明Java17，LICENSE为EDL1.0；阅读StatusCommand及StatusCommandTest、AddCommandTest中真实工作树与索引分别变化、删除后重建与目录前缀行为。先以独立git-android模块、明确JDK desugaring、API26与35真实模拟器验证init/status/diff/add/commit/log/reopen，不依赖系统git或模拟仓库。依赖构建全部远程；许可证随源码保留。只有实际兼容通过后才接绑定目录的宿主服务，严格限制.git/工作树路径与外部配置、关闭默认外部hooks、取消与权限回收，再实现插件页面和AI工具。Java库身份不证明Android兼容，当前结果待远程执行。

官方来源：https://github.com/eclipse-jgit/jgit/tree/v7.8.0.202609011348-r ，https://isomorphic-git.org/docs/en/statusMatrix.html 。isomorphic-git需要fs适配；现有QuickJS与页面不应暴露任意二进制文件读写来强行运行整个Git。JGit仍是候选，若实际平台失败必须根据日志修复或选成熟替代组件，不能削减本地Git/远程GitHub范围。


首次38463878364/2706156真实APK编译成功，API35仓库完整用例通过，API26实际NoSuchMethodError在IO.readSome→SilentFileInputStream.readNBytes，Git.init尚未完成。Google官方Java11 desugaring列表未提供readNBytes，直接启用普通desugaring不能证明支持；6.10.1同一IO实现仍调用该方法，降版本不会解决此根因。保持当前JGit7.8与minSDK26，使用构建时ASM转换仅在org/eclipse/jgit类中把readNBytes/readAllBytes虚调用替换到Android宿主流适配；不改Git索引/对象/提交算法，保留EDL许可，变化jar去除失效签名文件。适配遵守长度/EOF/不关闭流/异常传播，新增实际Android边界测试；原完整仓库用例不删，增加gc后的真实pack对象覆盖。全部构建转换与测试继续远程执行，当前修复待验证。官方API依据：https://developer.android.com/reference/java/io/InputStream ，https://developer.android.com/studio/write/java11-nio-support-table 。ASM9.8仅为构建依赖，不进入App运行时。


修复后的38464984225/dbb3828两台真实Android模拟器均成功。下载11848647132（API26 ZIP13,397字节）和11847429286（API35 ZIP13,424字节），核对CRC与实际JUnit XML：每台2项、无失败/错误/跳过，分别为流适配边界及真实Git仓库全过程。保留中文/emoji及字面特殊文件名、真实gc打包对象、索引和工作树不同内容、仅提交已暂存版本且未暂存内容保留、关闭重开与删除暂存断言。没有降低minSDK或更换旧库来绕过失败。该结果只证明独立Git模块兼容，绑定工作区的授权/路径隔离、Git插件页面、AI工具、远程克隆/拉取/推送与完整任务仍未实现；后续全部范围继续，0.28文件工作台发布分支保持独立。


继续实现原生BoundWorkspaceGit只读能力：只接受宿主已绑定的WorkspaceService与捕获对话，不接受JS目录，调用前、原生目录锁内和返回前复核活动对话/绑定/授权；关闭后拒绝。真实工作树和暂存区分别汇总、分页、显示冲突及提交历史；无仓库明确失败且不得自动init。只打开根目录内实体.git，拒绝外部commondir/alternates、元数据符号链接、外部include配置与重定向worktree。新增实际AndroidWorkspaces+真实JGit索引回归覆盖撤权、切换对话、重绑定、关闭、无仓库、外部配置与链接。该原生服务尚未接JS页面或AI，不宣称Git插件可用；暂存/提交/远程功能随后继续。全部实际测试待新的远程结果。


读取JGit同一固定版本DiffCommand.java与DiffCommandTest.java的HEAD/DirCacheIterator/FileTreeIterator及过滤语义后，增加原生diff(path,staged)：真实HEAD→index或index→worktree，字面文件名过滤、首次提交无HEAD采用空树、二进制阈值256KiB、输出超限明确失败。返回HEAD与索引SHA及未暂存文件SHA，读取前后版本变化拒绝返回混合差异；禁止.git路径、跨目录、目录及符号链接读取。版本摘要最多8MiB，超限明确失败。实际Android用例增加暂存/未暂存内容严格不同、读取不改索引或工作文件、外部路径/元数据/链接拒绝。当前原生服务仍未接插件UI/AI，远程结果待确认；暂存/提交与远程GitHub范围保持。


继续读取同版本AddCommand.java/Test、ResetCommand.java/Test及CommitCommand.java/Test：字面单路径暂存，reset -- path只恢复索引不改工作文件，commit默认只提交索引；setNoVerify只绕过pre-commit/commit-msg，post-commit仍调用，因此宿主FS明确返回hook不存在并拒绝runInShell，不能仅靠noVerify声称不执行hooks。新增stage/unstage/commit：独立写授权默认拒绝、审阅的HEAD/index/工作文件摘要在授权前后复核、取消暂存不改工作文件、提交不隐式暂存/不amend/不签名/不允许空提交，未完成合并状态拒绝。拒绝自动过滤器配置；该阶段不支持LFS过滤器。实际Android新增未授权不改索引、外部变更拒绝、只暂存单文件、取消暂存保留文件、旧审阅拒绝、提交仅索引且保留其他未暂存内容、三种恶意hooks不执行、删除暂存/取消暂存回归。源码及时提交，远程验证待结果；页面/AI路由与远程GitHub仍待接入。


写入审阅边界进一步改为JGit已有DirCacheBuilder/ObjectInserter/RefUpdate底层接口：暂存冻结并核对审阅字节，仅替换一个索引路径；取消暂存从HEAD恢复单个索引条目，工作文件保持；提交在索引锁内写当前审阅树、以expectedOldObjectId更新HEAD，拒绝空变化与冲突，不通过CommitCommand自动hooks。获取索引锁后以及授权回调后重查审阅的HEAD/index摘要，避免check→稍后JGit获取锁期间索引改变。使用现有JGit对象与锁实现，未自行实现Git对象/差异算法。暂存保存原始字节，不运行自动过滤器/换行转换；后续页面必须说明不支持LFS过滤器。新实现在远程测试中，不能只凭锁接口声明并发验收完成；原回归保留。


审阅版本继续包含当前分支及可执行位：只比较文件SHA不能发现chmod变化，只比较HEAD SHA不能发现切换到相同提交的另一个分支。暂存记录审阅的可执行位，变更时拒绝；提交指向审阅的分支名并以原HEAD对象CAS更新，拒绝没有文件的初始空提交。增加真实chmod后暂存拒绝且索引不变断言，未删除原写入回归。正式0.28构建已通过build/插件/公网搜索，完整UI仍运行，Git分支保持独立。


实际差异服务38467615726/62f5e73成功，取回API26的11851035021和API35的11850865223（各ZIP15,709字节），CRC与实际XML均每台3项无失败/错误/跳过。实际写入第一版38468188677/e6e9036也成功：取回API26 11851495005（16,823字节）与API35 11851415239（16,678字节），实际每台4项无失败/错误/跳过，包括新增真实暂存/取消暂存/仅提交索引/保留工作文件/禁用hooks。该证据对应旧版本，不能代替后来9d28d79/0414c3d索引锁、分支及可执行位改动的验证，最新2cdae7f矩阵继续运行。追加同分支只保留最新Git矩阵的concurrency，取消已被新源码替代的旧矩阵以免堆积；正式0.28发布作业保持原run不重启。


增加显式initialize：通过捕获对话的宿主WorkspaceService.location获取根目录、独立写授权、原生锁内再次确认.git不存在，只初始化main分支，已有元数据包括悬空链接不能覆盖；reads仍不自动建仓。新增真实新工作区未授权不建仓、已有文本保留、初始空提交拒绝、无HEAD新增文件diff/暂存/取消暂存、只提交选中文件且其余未跟踪文件保留回归。凭据/网络/JS页面未开放，全部新测试仍待远程结果；同分支旧矩阵由新源码替代，0.28正式发布作业保持不变。


共享服务阶段：进一步阅读固定DSH的packages/workspace/workspace/src/index.ts及tests/workspace.spec.ts的服务注入、对话工作区归属与生命周期组合，以及deliverables/workspace-changes服务的有界查询设计。core新增WorkspaceGitService/Session与类型键，不依赖JGit或Android；原生BoundWorkspaceGit实现该接口，原路径/审阅/授权检查保留。WorkspaceGitPlugin声明WorkspaceService依赖，注册服务并拥有所有绑定会话；主动关闭移除会话，停用使旧服务和旧会话失效，重新启用生成新服务并读取同一仓库。页面与AI适配器后续共用接口，必须在宿主持有review值，JS只拿句柄，不接受JS伪造patch/版本。新增实际Android注册、独立读写授权、提交、关闭、停用及重新启用的测试；Git兼容workflow同时监测core修改。当前源码尚未接入App/JS页面/AI工具，新增生命周期测试待远程构建，不声称Git已经可在APK中使用。
