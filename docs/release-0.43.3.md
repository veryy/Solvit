# Solvit 0.43.3

已发布 0.43.3/build86。[下载 APK](https://github.com/veryy/Solvit/releases/download/v0.43.3/solvit-debug.apk) · [正式 Actions](https://github.com/veryy/Solvit/actions/runs/38826293232)。

这次主要整理界面生命周期和回归验证，没有增加新的模型协议或插件入口。

- 更新 Compose 依赖，采用上游对协程拦截器替换的修复。原版本在真实 Activity 重建后的正常清理中会触发主线程检查错误；同一回归在更新后通过。
- 配置备份按钮现在订阅聊天的就绪和忙碌状态，界面会随状态变化更新。
- 修正远程界面测试中的排队滚动和状态读取：覆盖长历史分支、角色继承、设置底部、模型设置、语言切换、流式结束及 PDF 扫描/重试。这些是测试驱动的修正，不能据此宣称真实模型行为已经全部验收。

合入前的[完整远程回归](https://github.com/veryy/Solvit/actions/runs/38823261172)通过：611 项单元测试、Lint、固定签名、98 项常规设备用例、15 场恢复检查、覆盖升级及实际 stdio 宿主强杀。[排查记录和失败证据](webview-lifecycle-stage.md)完整保留。

已知边界：WebView124 在额外压力中出现过原生 SIGSEGV，根因仍未定位；不能把本次依赖更新或某轮压力通过当作根治。ARM64 真机、第三方依赖和真实模型自主任务尚未完整验收。

Linux/PRoot、Python、Node/npm、Java17、Skills 脚本和本地 stdio MCP 继续可用，见[环境指南](linux-environment.md)。版本为 0.43.3/build86，沿用固定签名；正常覆盖安装保留数据，卸载会删除本地数据，请先导出配置备份。

## 正式检查与产物

六项正式任务全部通过：611 项单元、4 项报告门禁回归、Lint、固定签名、141 项 SDK/7 项市场、公开搜索和 3 项实际 Ubuntu 设备检查。原始报告确认 98 项常规设备用例（app70/Web3/终端25）、15 场独立恢复、覆盖升级及实际 stdio 宿主 SIGKILL 通过，所有用例正常结束、无失败/错误/跳过。Ubuntu 生产运行环境记录为 Python3.12.3、Node24.21.0、npm11.19.0。

APK 59,488,722 字节，SHA256 `6590c2b59d3149fe24dda5d79f09244669e5523b8753742b51f12ee567ff37da`；Linux 源码 ZIP 1,072,711 字节，SHA256 `23479d4789bbf6697d49cb645c31a88825e3c9bc2ee2d3cb4aa158f8b6fd21b3`。正式产物与内部 ZIP 的完整 CRC、公开资产大小及 GitHub 摘要均已核对。固定签名仍为 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

公开下载入口另以无凭据的范围请求返回 HTTP206，并读取到正确 ZIP 文件头；完整内容校验来自正式 Actions 产物，不将范围请求冒称公开文件完整下载。
