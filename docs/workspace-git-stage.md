# 工作区 Git 与 GitHub

完整范围：同一工作区文件树/预览/编辑/终端/Changes，工作树与暂存区分别查看，选择暂存/取消暂存、提交/历史、克隆/拉取/推送及冲突处理；JS/TS插件页面与AI工具共用受宿主约束的服务，凭据加密且不传入页面/模型文本。当前仅开始底层Android兼容验证，尚无可用Git插件，不把历史执行快照当作Git状态。

先核对固定DSH d743267388641bc76f17c45ce8b4c231aed1d32c的packages/deliverables/workspace-changes/src/git.ts与tests/git.spec.ts：调用真实git、限制时间与输出、字面路径匹配、索引冲突与失败明确报告。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb的examples/github/src/local/terminal.ts实际通过Tools.System.terminal执行命令，github/contents.ts通过GitHub内容API带版本SHA更新文件；后者不能代替本地Git索引与整个项目提交。

Android基础系统不保证git可执行文件存在；不能仅给Shell拼接git命令就认为本地Git可用。核对JGit官方仓库v7.8.0.202609011348-r，实际提交f386af5bb89c42eec92ee1547fc3201d743165cb，pom.xml声明Java17，LICENSE为EDL1.0；阅读StatusCommand及StatusCommandTest、AddCommandTest中真实工作树与索引分别变化、删除后重建与目录前缀行为。先以独立git-android模块、明确JDK desugaring、API26与35真实模拟器验证init/status/diff/add/commit/log/reopen，不依赖系统git或模拟仓库。依赖构建全部远程；许可证随源码保留。只有实际兼容通过后才接绑定目录的宿主服务，严格限制.git/工作树路径与外部配置、关闭默认外部hooks、取消与权限回收，再实现插件页面和AI工具。Java库身份不证明Android兼容，当前结果待远程执行。

官方来源：https://github.com/eclipse-jgit/jgit/tree/v7.8.0.202609011348-r ，https://isomorphic-git.org/docs/en/statusMatrix.html 。isomorphic-git需要fs适配；现有QuickJS与页面不应暴露任意二进制文件读写来强行运行整个Git。JGit仍是候选，若实际平台失败必须根据日志修复或选成熟替代组件，不能削减本地Git/远程GitHub范围。


首次38463878364/2706156真实APK编译成功，API35仓库完整用例通过，API26实际NoSuchMethodError在IO.readSome→SilentFileInputStream.readNBytes，Git.init尚未完成。Google官方Java11 desugaring列表未提供readNBytes，直接启用普通desugaring不能证明支持；6.10.1同一IO实现仍调用该方法，降版本不会解决此根因。保持当前JGit7.8与minSDK26，使用构建时ASM转换仅在org/eclipse/jgit类中把readNBytes/readAllBytes虚调用替换到Android宿主流适配；不改Git索引/对象/提交算法，保留EDL许可，变化jar去除失效签名文件。适配遵守长度/EOF/不关闭流/异常传播，新增实际Android边界测试；原完整仓库用例不删，增加gc后的真实pack对象覆盖。全部构建转换与测试继续远程执行，当前修复待验证。官方API依据：https://developer.android.com/reference/java/io/InputStream ，https://developer.android.com/studio/write/java11-nio-support-table 。ASM9.8仅为构建依赖，不进入App运行时。


修复后的38464984225/dbb3828两台真实Android模拟器均成功。下载11848647132（API26 ZIP13,397字节）和11847429286（API35 ZIP13,424字节），核对CRC与实际JUnit XML：每台2项、无失败/错误/跳过，分别为流适配边界及真实Git仓库全过程。保留中文/emoji及字面特殊文件名、真实gc打包对象、索引和工作树不同内容、仅提交已暂存版本且未暂存内容保留、关闭重开与删除暂存断言。没有降低minSDK或更换旧库来绕过失败。该结果只证明独立Git模块兼容，绑定工作区的授权/路径隔离、Git插件页面、AI工具、远程克隆/拉取/推送与完整任务仍未实现；后续全部范围继续，0.28文件工作台发布分支保持独立。
