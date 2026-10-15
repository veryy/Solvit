# 无需 root 的 Linux 工作环境

2026-10-14 用户新增要求：在手机上安装 Python、Node.js 等开发工具，在没有 root 的情况下让 AI 与工作区终端执行脚本。现有 Android PTY 只运行系统 shell，没有 Linux 发行版，也不提供 Node/Python；不能把终端界面当作该需求已经完成。当前状态：已在 0.31.1 正式发布。生产 Ubuntu、Python3.12.3、Node24.21.0/npm11.19.0、Java17、工作区命令与 PTY 在 API35 Android 模拟器实测通过；JS/HTML 插件页面、固定签名及覆盖升级门禁通过。[发布与下载](release-0.31.1.md)。本地stdio MCP已随[0.42.3](release-0.42.3.md)发布并完成确定性Agent/实际Ubuntu验证；ARM64真机、真实模型完整开发任务及第三方MCP兼容仍未验收。下文按时间保留研究和失败修复记录，其中候选状态不代表最新发布状态。

## 实际 upstream 链路

- Operit dbf71916 的 `.gitmodules` 锁定 OperitTerminalCore 045868988e4091d613cb34b2941057703899b96e；`core/tools/system/Terminal.kt` 调用其 `TerminalManager.initializeEnvironment`、创建会话、流式命令、隐藏命令与中断。先订阅事件，再发命令，避免快命令丢失输出。
- OperitTerminalCore `TerminalManager.kt`：Ubuntu Noble ARM64 rootfs 放在资产中，初始化后解包到私有目录；原生 PRoot/loader/busybox 以 APK jniLibs 形式分发，通过 nativeLibraryDir 启动；普通身份默认 PRoot，chroot 另需 root。PRoot 使用文件系统路径映射、环境变量与伪 root 身份执行 Ubuntu `/bin/bash`。
- `ui/SetupScreen.kt`：列出 Python/venv/pip、Node.js、Java、Rust、Go，安装前探测状态，安装命令经同一个终端会话；关闭检查页面时回收检查会话。Node 安装使用外部发行源，Python 用 apt 包，Java 示例为 openjdk-17-jdk。
- `provider/filesystem/LocalFileSystemProvider.kt` 与 Operit 的 `PathMapperLinuxEnvAndroidTest.kt`、`PathMapperLinuxPathAndroidTest.kt` 用于核对 Android/Linux 双向路径映射。该锁定 terminal-core 没有自带测试目录，不据此宣称其边界已验收。
- PRoot Android fork 固定 b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087，实际读取 `src/GNUmakefile`、`src/arch.h` 的 loader 与编译配置。Termux 官方包脚本采用外置 loader、talloc，另可开启 android-shmem。

参考源码是 LGPL-3.0；目前只研究行为，没有复制其 Kotlin 或预编译二进制。PRoot 是 GPL-2.0-or-later 独立可执行程序，talloc 另须保留自身许可和可重现源码。交付前锁定源码、哈希、构建参数、ABI 和许可证；不直接打包不明来源的预编译 `.so`。Android API29+ 禁止普通应用直接执行可写目录文件，宿主 PRoot/loader 应由 APK 安装到 nativeLibraryDir；rootfs 的 guest ELF 则经 PRoot loader 执行。不能通过降低 target SDK 回避兼容问题。

## Solvit 接入范围与顺序

1. GitHub Actions 编译和验证 Android PRoot/loader；ARM64 是手机首要目标，x86_64 用于真实 Android 模拟器验收。Android 系统 shell 继续可用，不把不支持的 ABI 显示为已安装。
2. 原生宿主负责可信下载、哈希、空间预检、取消、临时目录安装与原子启用；失败不能留下“已安装”状态，取消不破坏旧环境。发行版安装按需下载，避免将几十 MB 的 rootfs 塞进所有 APK。
3. Linux 环境插件的 HTML/JS 页面显示安装/版本/容量/进度/详细错误，以及 Python、Node/npm、Java 等软件安装与状态。执行器、存储和权限由宿主服务提供，JS 不直接拿到任意宿主进程或私有路径。
4. 工作区映射到 `/workspace`，AI 有限命令和人类 xterm PTY 使用同一发行版与目录；每个 Shell 独立会话，关闭/停用插件时清理子进程和会话。安装软件也使用该环境，不创建一个与工作区不通的假终端。
5. AI 调用遵守原有对话内授权、角色、工作区归属、取消、输出限额和执行日志。Python/Node 是 Linux 环境能力，不会让 QuickJS 自动变成 Node；本地 stdio MCP 后续通过明确的环境进程接口接入。

这里提供 Linux 用户空间，仍使用 Android 内核与 Solvit 应用身份。可以执行脚本、安装用户空间软件、处理已授权文件，但不赋予 root、其他应用私有数据访问或未授权的系统操作；也不承诺 Docker、内核模块和完整系统服务可用。

## 必须有的交付证明

- Android API35 无 root 下实际启动 PRoot/guest bash，写入 `/workspace` 并由文件管理看到相同文件；反向编辑也可见。
- 实际安装和运行 Python 与 Node；保留准确版本、脚本结果、退出码、流式进度与失败日志，不用菜单存在或 Linux 桌面测试替代 Android 证明。
- 安装取消/失败重试、原有环境保留、路径空格、恶意归档越界与符号链接、跨工作区调用、拒绝权限与进程回收。
- 单元、模拟器、Lint、固定签名、覆盖升级和 APK 下载入口齐全。ARM64 真机、真实模型任务与 OEM 后台限制如果未验收，发布说明明确列出。

屏幕操控继续暂缓。环境接入不能挤掉已有角色、流式输出、附件、Git 和权限队列的回归门禁。

## 首个远程兼容性探针

`Linux environment compatibility` 工作流从锁定源码交叉编译 ARM64/x86_64 PRoot 与外置 loader，静态链接经过 SHA256 校验的 talloc 2.5.0，输出源码、许可和构建输入。`linux-probe` 仅在 `-PlinuxProbe=true` 时加入构建，未依赖到应用、没有产品入口。API35 模拟器用普通应用 UID 启动 APK 安装的 PRoot，核对伪 uid、带空格工作目录映射与双向文件可见。此探针使用 Android shell，不能替代 Ubuntu/Python/Node 安装运行验收；结果尚待实际工作流证明。未发布 Linux 功能 APK。

第一轮38525788048实际失败在ARM64的NDK Clang编译：上游 ashmem_memfd.c 调用 strcmp/memset 却没有包含 string.h。talloc配置和静态编译已成功；失败尚未进入模拟器。修复仅给该文件加入标准声明头，不关闭编译错误或改变运行行为，补丁随构建输入上传，再按原探针验收。

## 私有 rootfs 安装基础

新增 linux-runtime 的 RootfsArchive：先流式 SHA256 检查，再仅写入全新私有 staging，限制压缩/展开容量与条目数、拒绝路径越界/重复项，延后创建 guest 符号链接，失败清理 staging 而保留现有安装。不复制宿主所有权/setuid，dev 设备节点由运行时绑定提供。新增4个边界单元用例，远程工作流负责运行，尚未验收。使用 Apache Commons Compress 1.28.0（Apache-2.0）和 XZ for Java 1.10（0BSD），只调用 API；发布前补齐随 APK 分发的通知。

已下载静态核对官方 proot-distro v4.17.3 的 Ubuntu Noble x86_64 归档：27,629,072 字节，SHA256 1680d024bf47d2414a36759af528ca3569a73b70682daa3d9693ba63157cb5a8，5185 条目/105,463,427 展开文件字节，单一 ubuntu-noble-x86_64 前缀。该核对不等于安装或执行证明。

第二轮38526158279进入 PRoot 链接，实际失败是静态 talloc 包含未链接的 rep_memset_explicit（其 libreplace 实现）。将同一已校验源码构建的 replace.c 对象纳入静态归档，不编写替代函数。另静态核对 Ubuntu 的 etc/os-release 是 guest 符号链接，安装完成检查接受 usr/lib/os-release 的真实文件，并使用 API26 已提供的 Paths.get，避免依赖较新 Path.of。

## 首个 Android 执行证明

源5bb4860，[远程38526852717](https://github.com/veryy/Solvit/actions/runs/38526852717)通过：ARM64/x86_64从源码编译，实际artifact11876157015的ZIP CRC与XML核对为4安装边界单元和1 API35 Android PRoot用例，零失败/错误/跳过。普通应用 UID、guest伪root、带空格工作目录与双向文件可见通过。此时尚未安装Ubuntu/Python/Node。

下一轮新增UbuntuProbeTest，使用官方校验归档与同一个RootfsArchive，在API35应用私有目录安装Ubuntu，普通UID启动guest bash，经apt安装Python/venv、Node/npm并实际读写同一个工作区。rootfs只进入诊断APK，不进入Solvit产品APK；记录精确版本，不宣称发行版自带Node版本支持所有现代MCP。等待实际结果，仍未提供产品环境入口。

## Ubuntu 首轮实测失败与收窄修复

38527701091/a8d7eb2 已实际解包并启动 Ubuntu 24.04 guest bash，工作区从宿主读取检查通过；apt 下载30.5MB签名索引后以100退出，所有重命名返回 ENOSYS，尚未到Python/Node安装。锁定PRoot `tracee/seccomp.c` 中 PR_rename 会转成 PR_renameat；`tracee/event.c` 中 PROOT_NO_SECCOMP 控制过滤/适配路径。上游官方问题 https://github.com/termux/proot/issues/390 记录同类 x86_64 Android、仅在 PROOT_NO_SECCOMP=1 出现的失败。下一轮只清除 Ubuntu 启动中人为加入的该覆盖，让PRoot正常启用其适配；保持原下载、apt安装、脚本、venv断言和8分钟上限。此处不是关闭Android安全限制，不改变应用UID。是否修复以原完整Ubuntu测试为准。

38528429036/7b76f2f证明重命名问题已消失：apt索引成功并下载Python/Node/npm安装包，随后dpkg在新建备份硬链接时 Permission denied（usr/bin/clear、var/lib/dpkg/status-old）。下一轮采用锁定Operit TerminalManager已经探测并使用的 --link2symlink，启用上游硬链接模拟，PROOT_L2S_DIR明确位于该rootfs的私有.l2s目录，其他脚本/断言保持。宿主UID和权限不改变。完整Ubuntu安装尚未通过；原阶段Android shell映射仍有效。产品环境的目录在首次apt初始化前必须固定，避免重命名破坏.l2s的绝对路径。

## Ubuntu 实际安装与执行通过

源f1d871f，[38529444261](https://github.com/veryy/Solvit/actions/runs/38529444261)通过。实际artifact11877745788（32,449,542字节）ZIP CRC/XML核对4单元、2 Android，零失败/错误/跳过；Ubuntu用例耗时106.972秒，apt安装、Python/Node脚本写文件、npm版本调用和venv均实际成功。ARM64仅源码编译，尚未真机执行。

该轮版本导出不合格：Gradle完成连接测试会卸载诊断应用，随后adb run-as读到unknown package；不能把该导出文件当作版本证据。原安装执行通过不受影响，但精确版本暂不报告。修复为测试成功后向设备日志输出单行RUNTIME_VERSIONS，工作流从已收集的logcat严格解析并校验Python/Node/npm版本；缺少或格式错误就失败。下一轮重新核对真实报告。产品安装界面、软件管理、AI与PTY绑定仍未交付，公开版本保持0.30.2。

## 精确版本证据完成

024b427/38531440527 实际失败是 JUnit 方法返回了 Log.i 的 Int，而非 void；修复只显式声明 Unit，未删除原安装/脚本断言。

源 cfd0102，[38532078640](https://github.com/veryy/Solvit/actions/runs/38532078640) 全部通过：4 个安装边界单元用例、2 个 API35 Android 用例，零失败/错误/跳过。artifact11878393901 的报告成员已实际下载，逐个核对 ZIP 成员 CRC、XML 与设备 logcat；本轮未下载完整诊断 APK 归档，不宣称整体 ZIP 校验。设备日志和版本文件一致：Python 3.12.3、Node v18.19.1、npm 9.2.0。Python/Node 写工作区文件、venv、普通 Android UID 实测通过。ARM64 仅编译通过，尚未真机执行。

公开版本仍为 0.30.2；此证明属于底层兼容性，不代表产品的环境安装、取消恢复、软件管理或 AI/PTY 环境绑定已交付。下一步将同一启动配置提取为共享运行时，并接入按需安装和插件页面。

共享运行时新增 LinuxLaunch 与 RootfsInstallation：命令/交互终端复用同一工作区映射和 PRoot 环境变量；安装目录在初始化前就固定，完成后只原子切换 active 指针。失败、取消保留旧环境；下载、校验、解包、初始化状态经 Flow 提供，私有路径只给可信宿主。新增4个事务/启动边界用例，Ubuntu Android探针改用这两个生产模块，仍执行完整 apt/Python/Node/venv 断言；等待本轮远程结果。产品接入尚未完成。

5904168 的共享启动/事务安装已经由 [38535852875](https://github.com/veryy/Solvit/actions/runs/38535852875) 实际通过，Android 上仍完成完整 apt/Python/Node/venv 与工作区断言，并核对 active 指针指向初始化时的固定目录；版本日志保持 Python 3.12.3、Node 18.19.1、npm 9.2.0。新增取消/失败保留旧环境、重建管理器识别安装、下载中断和启动映射测试通过任务门禁；报告成员的精确数量待归档核对。

宿主接入第一块新增独立 linux-android 原生 provider：可信固定目录下载、HTTPS/容量限制、协程取消、Android 当前网络 DNS、apt 日志和 Python/Node/Java 软件包组。Linux 命令使用既有 ShellJobEngine 的输出限额、超时及进程组 watchdog，而非另建不受管的命令执行器；宿主运行计划只用于 APP 身份。该 provider 还未注册到正式应用或开放给 JS，下一轮检查其编译及进程清理回归。

环境管理作为独立 dev.solvit.environment JS/HTML 插件接入，包含安装阶段/下载容量、可停止的 apt 日志、Python/pip、Node/npm、Java17 安装入口和当前工作区 Ubuntu Shell 创建。管理动作只能来自当前页面显示并确认的原生控件，JS 不能提供 rootfs URL、哈希、命令或宿主私有路径。新增 API21 的 environment.linux 声明，必须同时声明 workspace/shell/network；旧 API1..20 仍保留，市场不向旧宿主提供这个插件。软件版本及实际可用性以设备执行日志为准，Java尚未实测。

5678e59修复了宿主cancel方法推导成Unit?的编译错误；9ddb339的生产探针实际失败于缺少core显式编译依赖，8072aa0补齐后重新跑原生产HTTPS下载/DNS/apt/PTY断言。既有 f1d871f/cfd0102 的底层安装结论不等同于这轮产品provider通过。

8072aa0 的 [38537813294](https://github.com/veryy/Solvit/actions/runs/38537813294) 生产路径已通过：3 个真实 API35 Android 用例，生产 HTTPS 下载、网络 DNS、受 ShellJobEngine 管理的 apt 安装、Python/Node 文件写入、Ubuntu 交互 PTY、TTY 与工作区可见均通过；版本保持 Python 3.12.3、Node 18.19.1、npm 9.2.0。本轮日志已实际下载；报告成员精确计数待归档核对。

7958dbe 的远程 JS 生成任务 [38538716444](https://github.com/veryy/Solvit/actions/runs/38538716444) 已通过 typecheck、SDK/市场及公开搜索检查。实际下载 artifact11881048160，ZIP CRC 和 environment API21/服务声明核对后写入 APK/市场的生成包。应用候选0.31.0/build63接入 Linux 插件、工作区 Linux 页签及按 Shell 选择运行环境；native binaries 不进入 Git，由锁定源码远程编译并随 APK 提供对应源码包。该候选尚未发布，完整回归/界面/许可/正式 APK 门禁仍需通过。

ARM64官方归档已下载并做静态内容核对：25,545,644字节，SHA256 81ac0fb0d16ded12ab11cede62f67b875ff56f9fa1aa9eb786415c3ec5c477d2，5180条目、126,240,490文件字节，单一ubuntu-noble-aarch64前缀，未发现硬链接。本机只读取归档元数据，没有运行Linux程序；ARM64运行仍未验收。

候选APK实际检查发现Commons Compress引入的Maven Codec 1.19.0与JGit转换配置中的文件依赖Codec 1.22.1重复；6feeec5仅将已有1.22.1放回正常依赖解析图，保留JGit原引擎及Android字节码适配。另显式声明环境操作Unit返回类型，避免Job.start的Boolean影响接口。远程Java扩展探针还发现测试局部变量java遮蔽java.io包，改名javaResult，不删除Java安装/版本断言。最终以原完整门禁为准。

8d9154c 的生产下载/Java17/PTY/已授权 AI 工作区路径由 [38543491385](https://github.com/veryy/Solvit/actions/runs/38543491385) 实际通过。该轮生产 Node 仍是 apt 的18，不据此宣称现代 Node 工具兼容。Operit 锁定 SetupScreen 明确用 NodeSource setup_24.x；Solvit 改用 Node 官方 v24.21.0 LTS 的 ARM64/x64 固定归档和 SHASUMS256 哈希，宿主先 HTTPS 下载/校验，再由 guest 解包到版本目录并实际执行，成功后公开 node/npm/npx 链接。无需执行可变第三方安装脚本。Node 文件及 LICENSE 保留在 guest 目录，不进入 APK。参考 Node v24.21.0 BUILDING.md（Linux glibc/libstdc++ 支持）和官方发布说明；生产精确版本增加独立设备日志门禁，旧 apt 兼容探针继续保留。新版尚待远程执行，不提前声称通过。

线上市场已经部署 API21 加法支持及 environment1.0.0：HTTPS 实际下载17,745字节，SHA256 4d5d07cc8c8ed309ccc918b05cf3517a82c76bc80d3febce4506958d4c6f01b7，与提交的生成包一致；API20目录仍不含新环境插件，API21包含。使用部署前服务源码、保留的旧包目录和新服务逐个比较 API1..20 的目录选择结果，全部一致。随后客户端才切换 hostApi21。未删除或替换历史版本包。

03823a7 的候选整包 build 任务已成功（单元/Lint/APK/固定签名），界面任务因下一次功能提交而取消，不能当作全应用验证通过。实际下载原生 artifact11883907390，ZIP CRC 通过；ARM64/x86_64 的 PRoot 与 loader 所有 PT_LOAD 的 ELF alignment 为16,384，offset/vaddr同余验证通过。这只是构建产物静态检查，不宣称16KiB页 Android 真机执行通过。

进一步实际下载38543491385的完整artifact11884271839（35,076,737字节），完整ZIP CRC/XML核对9个linux-runtime单元与3个API35 Android，零失败/错误/跳过；设备Java输出为OpenJDK17.0.20.1，原apt探针Python3.12.3/Node18.19.1/npm9.2.0。Node24首轮38545704994失败为误用Ubuntu归档前缀校验（Node目录含版本点号），4db4f4b分离Node Source类型，保持Ubuntu归档边界不变；随后清理仅属于宿主的中断Node下载，等待原生产执行门禁重跑。

Node v24.21.0 官方BUILDING.md对Linux ARM64/x64的支持基线为kernel>=4.18、glibc>=2.28（旧内核可能可运行但不受其保证）。Ubuntu提供guest glibc，Android内核仍取决于手机；Android8可安装Solvit不等于所有旧手机均能运行这套开发环境。当前只验证API35模拟器，明确保留旧内核/OEM/ARM64真机边界，不使用降低targetSDK或系统root。

9d5069e 的 [38546632425](https://github.com/veryy/Solvit/actions/runs/38546632425) 全通过。实际下载任务日志：3/3 API35 Android用例、零失败/跳过，原归档边界/事务单元门禁亦成功；严格设备版本解析得到生产Python3.12.3、Node v24.21.0、npm11.19.0，Java17日志门禁成功。包括生产HTTPS下载及哈希、Ubuntu安装、Node执行/写文件、Java17安装、Ubuntu PTY、已授权AI native工作区命令与重建管理器恢复Shell运行环境。旧apt探针仍单独记录Node18.19.1，不能与生产24混淆。该轮设备完整归档artifact11884868270尚未下载，结论来自已下载完整任务日志和其严格设备报告门禁；ARM64运行仍未验收。

候选 [Android38546632484](https://github.com/veryy/Solvit/actions/runs/38546632484) 已全通过，源9d5069e：build和verify-ui成功；实际下载unit artifact11885465917并完整ZIP CRC/XML核对447个单元，零失败/错误/跳过；Lint、APK和固定签名门禁成功。完整应用界面任务亦通过，包括新增真实environment HTML页面、拒绝安装不写入和旧签名覆盖升级。应用设备报告精确计数待正式版本归档核对，不用生产探针3个测试代替应用UI测试。代码以保留历史的fast-forward合入main。0.31.0正式标签还必须通过build/UI/plugins/search/Linux后才能公开APK。

候选UI artifact11885916398完整ZIP CRC/XML核对59个应用/Web/终端测试，零失败/错误/跳过，但实际查看linux-plugin-before-install.png发现主内容空白、仅刷新footer可见。DOM存在断言不能证明可见可点，故主动取消未公开的0.31.0正式运行38549334671，保留其不可变标签，不移动历史。按已有Skills页面的稳定html/body百分比高度替换环境页面100dvh，并加强真实Android用例：记录布局尺寸，检查按钮可见范围及elementFromPoint命中，使用真实屏幕触摸打开安装确认，再验证拒绝不写入。环境包升1.0.1，应用候选0.31.1/build64；生成包静态同步后由远程生成检查确认。尚未宣称此布局修复通过，不发布空白页面APK。

环境页面1.0.1生成检查38550289601通过。线上已经保留旧1.0.0包并更新当前到1.0.1；HTTPS实际下载17,753字节，SHA2561856e4c2391b9d589b31095b5c8dae64d9e75311590d62b278c2419ba8c2d5f0，与仓库一致；API20实际完整目录与更新前相同，旧1.0.0下载仍为4d5d07...01b7。旧包同时保存到仓库legacy目录，避免重新部署丢失历史下载。页面视觉/触摸回归仍在运行。

38550289681的build通过但新可见性断言失败于LinuxEnvironmentHtmlTest行42。完整下载UI artifact11887750071（4,554,864字节）并核对ZIP CRC，设备日志明确viewport596/body0/main32，安装按钮DOM范围173.58..222.35、dvh支持true。因此“dvh不支持”的猜测被证伪；CSS百分比替换也没解决真实高度0。改为环境JS页面使用innerHeight明确设置body高度，初始化/宿主更新/窗口resize时同步，并在下一帧再次同步；保留原可见性/elementFromPoint/真实触摸断言，不增加超时或绕过失败。新包1.0.2由远程Node生成，旧1.0.0/1.0.1保存在legacy；仍不发布，等待真实设备复验。

修复源6c58e27的[38552946405](https://github.com/veryy/Solvit/actions/runs/38552946405) build/UI全成功。实际完整下载artifact11890490065（4,611,754字节），完整ZIP CRC/XML核对59个应用/Web/终端测试，零失败/错误/跳过。Linux页面设备布局viewport596/body596/main527.24，按钮完全位于可见范围且elementFromPoint命中；真实屏幕触摸打开包含本地化网络/空间说明的确认框，取消不启动下载、不创建工作区。实际查看PNG确认说明、安装按钮与底部刷新均正常，正文不再空白。Native运行时沿用已验证的Python3.12.3/Node24.21.0/npm11.19.0/Java17代码；正式0.31.1仍须全标签门禁与产物核对。

正式0.31.1全部门禁成功并公开：[Release](https://github.com/veryy/Solvit/releases/tag/v0.31.1)、[Actions38555594514](https://github.com/veryy/Solvit/actions/runs/38555594514)。正式unit/UI归档完整ZIP CRC/XML为447单元及59设备，零失败/错误/跳过；公开APK和对应源码已实际下载并与正式产物逐字节比较一致，正式截图确认页面完整可見。准确大小、SHA、签名和限制见release-0.31.1.md。此交付完成环境功能阶段，不代表ARM64真机、真实模型项目任务、所有Node扩展或stdio MCP已完成；继续原路线。

## 0.33.0正式门禁发现任务交接竞态

38586931701的verify-linux/115931304832失败，已下载完整任务日志：生产Ubuntu安装READY后，原设备用例立即installSoftware(JAVA)报“已有环境安装任务正在进行”。源begin在Job内部更新READY，finally才清installingRootfs，Job尚未inactive；StateFlow调用方能在这个窗口重入。排序假设：READY通知早于Job收尾（源码与栈吻合）、取消清理仍占用、旧rootfs进度回放（后两者无本轮证据）。最小修复仅在终态后join旧操作再校验重复启动，保留运行中拒绝、mutex和取消回收，不添加延时/重试或放宽断言。原完整生产下载安装、Java17、共享工作区和PTY设备用例就是正确回归入口；无需浅层镜像实现测试。新候选0.33.1/build67，保留v0.33.0不可变标签；修复待远程复验，尚未发布。

修复源74b36ae的[Linux38589013597](https://github.com/veryy/Solvit/actions/runs/38589013597)已成功；完整任务日志已下载，原生产环境完整回归3/3设备通过。严格设备报告版本门禁输出生产Python3.12.3、Node v24.21.0、npm11.19.0与Java17检查成功；原先READY后立即安装Java路径未改变，现可成功。该轮完整35MB探针归档尚未下载，不宣称其ZIP CRC/XML已核对。Android38588850320的build成功，verify-ui仍运行；正式0.33.1尚待发布。修复仅join终态旧Job，不依赖增加睡眠、重试或放宽原断言。

74b36ae候选Android38588850320的unit/UI归档已完整下载并通过ZIP CRC/XML：465单元/63设备零失败/错误/跳过。实际原始扫描页截图检查正文和关闭按钮完整。完整Linux生产回归与候选应用门禁已通过，准备FF合入main及不可变v0.33.1标签，正式门禁/产物核对仍待执行。


0.33.1正式发布已完成：[Actions38592485019](https://github.com/veryy/Solvit/actions/runs/38592485019)六项成功。Linux完整任务日志核对原3个Android用例通过、Python3.12.3、Node24.21.0/npm11.19.0和Java17严格门禁成功，安装READY后立即追加Java的原路径通过。正式APK与对应源码已从公开Release实际下载，与Actions产物逐字节一致并通过ZIP CRC；摘要见[0.33.1正式记录](release-0.33.1.md#正式发布与实际下载核对)。ARM64真机、真实模型开发任务、stdio MCP边界不变。
