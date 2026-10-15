# Git克隆安装的进程中断恢复

承接已发布0.40.0，完整目标是持久记录、保留部分安装事实、插件HTML审阅/继续/放弃入口、并发修改与真实进程死亡验收，最后远程门禁和可下载APK。此文件记录实施过程，当前没有新的功能交付。

## 实际参考

核对JGit锁定f386af5的`CloneCommand.java`（call、verifyDirectories、cleanup）及`CloneCommandTest.java`的空目录、noCheckout、remote/branch断言。JGit异常/ShutdownHook清理不能覆盖Android强杀，而且递归删除目标孩子的上游策略会误删并发Shell文件，不移植该清理。继续使用现有JGit传输/工作树准备与校验，Android工作区根inode和已有Shell目录必须保留。

读取DSH d743267的`workspace-changes/src/capture.ts`及`tests/capture.spec.ts`：有界读取、未读内容不伪称相同、独立保留前后事实。读取Operit dbf71916的`examples/github/src/local/terminal.ts`：工具通过宿主终端能力，不能把一次命令正常退出当作Android进程死亡恢复。没有复制上游实现或新增外部库，JGit依赖与既有许可证保留。

## 当前缺口与实施约束

0.40.0从私有cache下载，准备工作树后向根目录逐个Files.copy，再移动.git；普通取消会清私有暂存，强杀留下的部分目标没有持久归属。根目录非空使下一次克隆拒绝，复制中的文件还可能不完整。不能对根目录递归回滚，不能自动重新联网或覆盖用户文件。

候选采用应用持久私有暂存与同步意图，区分DOWNLOADING、READY、INSTALLING、PUBLISHING、COMMITTED。完整工作树审阅清单在发布前保存。普通文件改为通过JNI不覆盖原子移动一次发布完整inode（最初硬链接候选被Android设备验证否决），继续安装核对清单中的同一文件身份和摘要，以及文件仍在暂存还是已移入工作区；新同名、变更和额外条目明确冲突，保留双方。目录与.git发布须拒绝替换，根inode保持。已发布.git用独立来源标记和身份识别；重启不自动重放，明确确认后只继续缺失步骤或关闭已发布记录，不重置用户后续修改。

暂存和已执行事实由宿主管理，业务界面仍为JS/HTML。关闭/放弃只清经登记的私有暂存和记录，绝不递归清工作区。未登记的旧cache克隆目录不按名字认领。已有打开描述符/硬链接不被锁住，摘要为观察；不承诺断电、磁盘损坏或任意同UID私有目录修改下的恢复。

须验证：下载中断/重新确认、逐文件发布后中断、.git移动后确认前中断、已发布后用户改动、同名/新文件/符号链接冲突、根目录替换、身份/清单损坏、取消/授权撤销、跨对话门禁、同工作区多页面竞争，以及旧Git/工作区/附件/终端回归。合同与真实外部force-stop分别记录，未运行不能写通过。

第一块原生内部协议候选已写入，尚未由现有clone入口调用：持久记录/有界清单、完整inode硬链接发布、已有来源标记的.git发布事实、显式继续及仅关闭私有暂存，构造与inventory不执行操作。新增9项真实临时文件系统合同，覆盖首次安装、部分链接后重建继续、并发额外文件、同inode编辑/新inode同内容/符号链接、.git实际移动后修改、根身份/清单损坏、未下载完成/孤立目录、双实例互斥。目录移动仍为JVM fixture，生产JNI、页面门禁和真实强杀尚未接入/验证。将git-android:testDebugUnitTest加入合同与正式构建命令，远程结果另记，不以这些源码称为功能完成。

内部协议提交`ed454f1f`的[远程合同运行38709002756](https://github.com/veryy/Solvit/actions/runs/38709002756)已成功，新增9个协议用例执行通过。

第二块候选已将真实clone入口接入noBackup持久暂存，保留既有JGit传输/对象限制/分支配置；普通下载失败只回收DOWNLOADING，准备好或已安装的记录保留。新增只读审阅与工作区绑定的显式离线继续、重新联网下载、只清私有暂存接口。审阅不暴露私有路径；继续前重新核对事实、文件和授权；已发布事实仅确认和清理，不重置后续工作树。生产.git发布通过已有workspace JNI共享的renameat2(RENAME_NOREPLACE)，原备份文件转移继续限定普通文件，新入口限定目录。没有替换移动的fallback。

新增2个内部审阅用例与4个Android真实inode/服务测试，后者覆盖同名目录/文件/链接拒绝替换、实际JGit仓库离线继续及Shell cwd、编辑/撤权/跨对话隔离、实际.git移动后的后续编辑。并发Shell写入的原传输用例改为检查READY记录和显式放弃后仍保留Shell文件。尚未运行这批新增用例，生产适配仍待远程编译与API26/35验证；HTML审阅入口、API/SDK/市场升级、外部force-stop和最终APK尚未交付。不能以当前宿主接口称为完整恢复功能。

`fa0d51e9`的[宿主合同38710759092](https://github.com/veryy/Solvit/actions/runs/38710759092)通过，实际XML核对468项、0失败/错误，其中CloneInstallTransactions为11项。Android双版本[38710758455](https://github.com/veryy/Solvit/actions/runs/38710758455)在测试源码编译阶段失败：Fixture普通初始化/清理调用forChat/location/unbind挂起函数，尚未进入设备测试。`9c1992e1`将准备和清理改为挂起调用；没有把编译失败记作设备通过。

第三块候选补GitPageBridge的安全清单/审阅查询、宿主保留事实的一次性reviewId、显式继续/重新下载/放弃及页面关闭清理。ScriptPageExecutionContext对直接和startRemote内嵌的恢复动作要求API29；离线动作不要求网络授权，重新下载要求声明且经过原生网络授权。新增2项页面宿主合同，检查旧API拒绝、嵌套门禁、防伪造工作区/提交事实、过期/复用handle与离线动作。宿主确认使用固定中文解释，避免仅显示内部JSON。当前包解析器上限仍为28，API29尚未正式开启；待HTML/SDK与兼容包整批完成后启用、远程生成并发布，旧插件不会提前获得这些能力。

页面宿主候选`af9c8da7`的[合同38711525341](https://github.com/veryy/Solvit/actions/runs/38711525341)成功；实际完整下载归档并核对CRC/XML为470项、零失败/错误，其中11个克隆事务、6个Git页面会话、4个Git页面桥。Android[38711525423](https://github.com/veryy/Solvit/actions/runs/38711525423)两路测试APK均成功编译，但各27设备用例中7个克隆相关失败。实际下载两路完整归档与任务日志：根检查通过、base目录检查在begin第115行失败，独立目录适配器在parent canonical要求失败；并发传输用例因克隆在下载前失败而等待超时。

原因是Android Context的/data/user/0路径别名没有在宿主边界规范化，严格canonical目录检查将私有暂存误判变化。修复宿主noBackup路径/cloneStore与直接设备fixture路径为canonicalFile；不放宽事务中目录、inode、symlink或JNI不覆盖检查，不增加传输等待时间。保持原27个实际用例重跑，尚未得到修复后设备结果。

路径修复后的[38712390857](https://github.com/veryy/Solvit/actions/runs/38712390857)仍未通过：两路实际日志都剩3个createLink的AccessDeniedException，其余用例已通过。独立目录移动用例通过，原路径别名失败不再出现。[AOSP app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/b5b796adcd99dce1822184e4859d91f3d1da2153/private/app_neverallows.te#84)明确禁止untrusted apps创建硬链接，确认原JVM方案不可直接用于Android，不申请root或关闭SELinux。

修复为私有完整文件经JNI renameat2(RENAME_NOREPLACE)原子移入目标；先持久INSTALLING，移动后源缺失只在目标同inode/模式/摘要都匹配时接受，缺失不重建，冲突不覆盖。文件和.git均无copy/unlink fallback。私有清理不再依赖工作区硬链接。原JVM协议fixture同步改为完整文件移动，额外增加已移动文件被删除时拒绝恢复，以及Android真实文件移动同名/符号链接拒绝覆盖与inode保留。保留已发布后的用户变更和逆序私有清理约束；修复后尚待远程验证。

HTML接入候选：Git TS页面启动先查恢复清单，即使status因缺少.git失败也保留入口；每页5条、单独审阅，再生成仅含host reviewId的原生确认。继续/重新下载走既有异步任务进度和停止，放弃后留在恢复列表，不因无.git掩盖成功；失败/取消返回记录清单并要求新审阅。未知目录只报告，初始化入口在已知存在未处理记录时隐藏。主按钮说明支持现有六种语言，底层phase/status仍作为事实显示。新增3个TS页面合同，覆盖无.git启动、明确确认、防复用、冲突放弃、分页与下载失败返回；未本地运行。

继续阅读DSH锁定版本ui-settings-shell的apply.client.spec.ts服务到达/移除/清理测试，以及Operit github/local/fileApply.ts。沿用页面生命周期和显式操作，拒绝移植其delete-before-write覆盖策略。没有复制上游代码或新增第三方库。API29 SDK/解析器/市场目录与Git1.3.0源码同步升级；保留1.2.7完整原始791303字节、SHA96811c76f4b14800829de63ab1cc848d82892915472dcee0a3ea4ad29348b28c到legacy及目录顺序，API1..28继续选旧包。增加旧Git1.2.7设置/权限/用户改码/卸载回归和市场API29/旧字节断言。当前已提交内置asset仍为旧包；须由远程生成并下载审阅、同步，再跑宿主/UI及外部强杀，未完成新APK交付。

完整文件移动候选`4d39a948`的[38713572079](https://github.com/veryy/Solvit/actions/runs/38713572079)API26已28/28通过，API35为27/28通过，唯一失败为并发Shell克隆审阅时读取微小manifest仍分配33554452字节导致OOM。原子文件/目录移动、重建离线恢复和已发布后变更用例在两路均通过。修复readText先检查NOFOLLOW实际文件大小上限，再只分配实际大小+1并核对读取前后身份和长度，保留32MiB上限，不扩大堆或修改原设备断言。

Git1.3.0首轮[插件生成38714066212](https://github.com/veryy/Solvit/actions/runs/38714066212)typecheck通过，页面合同仅冲突放弃的最后一项失败：成功提示复用了git_error ID。修复为成功通知git_notice与错误状态区分，原断言保留。生成物未直接作为通过包或公开发布，须重生成并核对。

第二轮TS合同38714538856在旧推送拒绝用例失败：任务执行结束SUCCEEDED不代表Git结果成功，REJECTED_NONFASTFORWARD必须仍显示git_error。修复只对已核对JGit RemoteRefUpdate.Status/MergeResult.MergeStatus的成功状态（或无状态的完成操作）显示成功通知，未知/冲突/拒绝仍为错误；不修改旧拒绝断言。

[38714538919](https://github.com/veryy/Solvit/actions/runs/38714538919)在`ff225983`上API26和35两路均成功，各28个实际Android用例通过。TS第三轮38714988797的133项合同已通过，随后市场测试因新增previousGit与既有1.2.1变量重名而语法失败，改名previousGit127保留全部旧字节断言，仍须重新跑市场与公网门禁。

外部进程死亡候选已接入原生产clone的宿主事实观察器（仅阶段名，不传路径或重放命令）：FILE_PUBLISHED在实际文件移动后、METADATA_PUBLISHED在实际.git移动后、COMMITTED在意图持久后、PRIVATE_ENTRY_REMOVED在实际私有删除后。新Android应用测试通过生产HTTPS/JGit/凭据库准备，独立adb进程记录PID并force-stop，再启动新进程操作真实Git插件HTML，先取消确认验证零重放，再明确继续/重新下载/关闭私有暂存。DOWNLOADING由实际TLS backend收到并阻塞请求后发标记，不以准备意图代替下载；另覆盖FILE、Shell CONFLICT、METADATA、COMMITTED和CLEANUP共6个窗口。验证根inode、共享Shell文件、已发布后删除/编辑、网络请求计数和无自动重试。TLS证书仅放测试APK，私钥留CI runner，不改系统信任/主机名校验。正式Android门禁保留旧9个强杀和常规套件，并加入这6个；测试源码与脚本尚未远程编译或运行。

候选`ec2147d4`的[插件远程生成38716258761](https://github.com/veryy/Solvit/actions/runs/38716258761)全部成功：typecheck、133项TS合同、5项市场测试以及免配置搜索/HTTPS MCP/Skills公网检查通过。完整下载862194字节归档，CRC和SHA256 `03fd556696a9335b0535360acb8c48081454dc22c60f9bf31f1d8b3b2213e121`与Actions一致，15个包中仅Git变化；同步Git1.3.0到市场当前包与APK内置asset，其余14包保持原始字节。Git1.2.7 legacy仍保留。API26/35的38714538919两份设备归档亦已完整下载核对CRC/XML，两路分别28项、零失败/错误/跳过。外部6场强杀运行38716282303尚在构建，不能记为通过；API29市场仅准备私有兼容验证，未部署。
