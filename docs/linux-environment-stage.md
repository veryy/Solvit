# 无需 root 的 Linux 工作环境

2026-10-14 用户新增要求：在手机上安装 Python、Node.js 等开发工具，在没有 root 的情况下让 AI 与工作区终端执行脚本。现有 Android PTY 只运行系统 shell，没有 Linux 发行版，也不提供 Node/Python；不能把终端界面当作该需求已经完成。当前状态：源码研究，尚未提供可下载的 Linux 环境版 APK。

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
