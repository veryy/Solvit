# 在手机上运行 Python、Node.js 和 Java

Solvit 的 Linux 环境已经随 0.31.1 发布，后续版本也包含它。先[安装最新版 APK](https://github.com/veryy/Solvit/releases/latest)，不需要 root，也不用另外安装 Termux。

## 怎么开始

1. 在对话里创建或绑定一个工作区。
2. 打开「工作区 → Linux」，点「下载并安装 Ubuntu」。首次安装需要网络和存储空间，可以查看进度、近期日志或停止安装。
3. 等待「已就绪」，点「新建 Ubuntu Shell」。原来的 Android Shell 仍是 Android 环境；请打开新建的 Ubuntu Shell。
4. 在交互终端运行下面的命令，确认当前环境：

```sh
pwd
python3 --version
node --version
npm --version
```

基础环境是 Ubuntu 24.04，包含 Python/venv 和 Node.js 24/npm。需要 pip 或 Java 时，在 Linux 页面分别点「安装 Python / pip」「安装 Java 17」，等安装完成后再使用。

## Python 项目用独立环境

Ubuntu 是开发工具运行的 Linux 用户空间；Python 的 venv 则用来分开每个项目的 Python 依赖。进入工作区的 Ubuntu 交互终端后：

```sh
python3 -m venv .venv
. .venv/bin/activate
python -c 'from pathlib import Path; Path("hello-python.txt").write_text("Hello from Python\n")'
```

安装第三方依赖时，激活 venv 后使用 `python -m pip install 包名`。普通命令每次都是新进程，不会保留上一条命令的激活状态；也可以直接用 `.venv/bin/python` 和 `.venv/bin/python -m pip`。

Node.js 同样可以直接处理工作区文件：

```sh
node -e 'require("node:fs").writeFileSync("hello-node.txt", "Hello from Node.js\n")'
```

回到工作区文件列表，可以看到这些输出文件。终端内的 `/workspace` 对应当前绑定的工作目录，文件管理与 Git 使用同一份文件。

## 怎么让 AI 使用

启用「工作区与 Shell」插件，并在当前角色中允许它使用。告诉 AI 使用刚创建的 Ubuntu Shell 运行命令；需要交互输入的程序另需启用终端插件。调用仍经过对话内的工具授权，失败可查看工具详情、退出码和输出。

Linux 环境插件管理安装；工作区与终端插件负责执行。QuickJS 中运行的 JS/TS 插件不会因此自动获得 Node 的 `process`、`require` 或任意文件访问能力。本地 stdio MCP 进程接入仍未完成。

## 它能做什么，权限到哪里

实现参考 [Operit TerminalManager 的 PRoot 启动](https://github.com/AAswordman/OperitTerminalCore/blob/045868988e4091d613cb34b2941057703899b96e/src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt)和[软件安装页面](https://github.com/AAswordman/OperitTerminalCore/blob/045868988e4091d613cb34b2941057703899b96e/src/main/java/com/ai/assistance/operit/terminal/ui/SetupScreen.kt)。Solvit 独立适配了按需下载、哈希检查、安装取消和工作区绑定，没有复制 Operit 的 Kotlin 实现或预编译程序。

PRoot 提供 Linux 用户空间，继续使用 Android 内核和 Solvit 的应用身份。可以安装用户空间软件、运行脚本、处理已授权文件；不能借此读取其他应用的私有数据、取得 root 或绕过 Android 权限。也不承诺 Docker、内核模块和完整系统服务可用。Shell 仍具有本应用身份的访问能力，工作目录映射不构成独立安全沙箱。

远程 API35 x86_64 Android 模拟器已经实际验证 Ubuntu 下载、Python3.12.3、Node24.21.0/npm11.19.0、Java17、共享文件与交互终端。ARM64 程序已编译，但 ARM64 真机、OEM 后台限制、旧内核兼容性和真实模型自行完成项目任务仍待验证。Node24 的官方 Linux 内核基线为 4.18；Android 版本满足安装要求，不代表所有设备都能运行这套工具。

失败时先看 Linux 页的详细日志，确认网络与剩余空间，再重试。首次安装失败不会标为已安装，重装失败保留原环境；卸载应用会删除本地环境和数据，同签名覆盖升级保留它们。[研究与实际构建证据](linux-environment-stage.md)。
