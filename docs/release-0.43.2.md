# Solvit 0.43.2

已发布0.43.2/build85。[下载APK](https://github.com/veryy/Solvit/releases/download/v0.43.2/solvit-debug.apk) · [完整Actions](https://github.com/veryy/Solvit/actions/runs/38783582460)。

修复插件HTML文档更换后页面空白的问题：旧WebView销毁时，AndroidView仍保留原来的节点，没有挂载新文档。本版让文档拥有独立的视图、消息桥和协程生命周期，并由视图容器释放回调执行正常清理；已失效实例不再接收页面更新。聊天数据、插件权限和Linux运行环境保持兼容。

真实设备回归已在原0.43.1 APK上复现“新文档没有挂载WebView”，修复后同一实例/DOM/消息桥断言通过；完整远程发布检查已通过。[排查与原始证据](webview-lifecycle-stage.md)。

已知问题仍保留：系统WebView124在快速切换工作区/Activity重建时出现过原生SIGSEGV。原APK的短循环和七场景前置复现未触发，但提高到每轮30次后，第二轮第3次重建复现了同一原生地址的崩溃；修复版压力检查完成59次后由另一项Compose跨线程绘制异常中断，并非同一SIGSEGV；不能将计划的240次写成全部通过。本次文档所有权缺陷与该原生崩溃的因果关系尚未证实，不能宣称已根治。继续保留失败报告和独立排查。

Linux/PRoot环境仍以Android应用身份运行；Python、Node/npm、Java17、Skills脚本和本地stdio MCP用法见[环境指南](linux-environment.md)。旧Skills用户仍需明确更新及审阅新增workspace权限。ARM64真机、第三方依赖和真实模型自主任务尚未验收。正常同签名覆盖安装保留数据，卸载会删除本地数据。

## 正式检查与产物

完整远程门禁通过：611单元、4项报告门禁回归、Lint、固定签名、141 SDK/7市场、搜索和实际Ubuntu脚本。原始UI报告独立核对97常规设备（app69/Web3/终端25），包含新文档替换回归，无失败/错误/跳过；15场中断恢复、覆盖升级和实际stdio宿主SIGKILL通过。独立压力检查失败仍单独披露，不能以正式回归通过替代。

APK58,955,111字节，SHA256 `d70b99e301c965c2f2d3692c372354486ce29acb6ec55d93419f777c7a235b23`；Linux源码ZIP1,072,711字节，SHA256 `8159edb249dd29762134baaf8aa886744af689264287535bc815df4eb441f766`。完整正式产物及CRC已核对，公开资产大小和GitHub摘要一致。固定签名仍为`f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

公开 APK 与源码下载入口均另以范围请求返回 HTTP 206，并读取到 ZIP 文件头；完整内容校验来自前述正式 Actions 产物，不将范围请求视为公开文件完整下载。
