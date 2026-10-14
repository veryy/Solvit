# Solvit 0.33.1：把 PDF 读进聊天

包含PDF读取与原页预览，并修复Linux安装完成后立即追加软件时的任务交接。正式发布以标签工作流与实际公开产物为准。

- 聊天附件支持 PDF。文字页直接提取，扫描页用手机上的 OCR 识别；混合文档逐页处理，不把短文字页误当扫描页。
- 点击附件查看读取结果，可选择起止页和识别语言：中文、日文、韩文或拉丁字母。识别模型随 APK 提供，使用时无需额外服务 Key 或首次下载模型；APK 为58,048,501字节（约55.36MiB），包含离线识别模型。
- 每次读取最多40页、256KiB文字。发送时带上原页码、读取方式和实际覆盖范围；范围外页面不会发给模型，超限明确提示，不悄悄截断。
- 可以停止、重新读取，并按需查看原始页面，对照 OCR 是否正确。OCR 可能识别错误，重要内容请核对原文。
- 页范围和语言随消息保存。重试、重开聊天或编辑并分支时保留选择；已发送消息的选择只读，调整时请编辑并分支。
- 密码保护、损坏、禁止提取或没有可读文字的文档显示具体错误。原文件仍由附件服务保存；解析临时文件正常结束后释放，异常退出遗留文件下次启动清理。

用法：输入框附件按钮 → 选择 PDF → 点击文件卡片 →「读取范围与语言」→「保存并读取」→ 返回聊天发送。PDF 内容作为用户资料进入模型请求，能否准确回答仍取决于模型及文档质量。

当前仍待完成：工作区文件导入与插件侧文档调用、全局存储配额、真实模型文档问答、ARM64 真机及大型复杂 PDF 验收。未发送草稿和等待队列仍不支持进程死亡恢复。Linux 开发环境延续0.31.1，屏幕操控暂缓。

发布前必须通过实际文字/中英文扫描PDF、页范围和OCR、原图截图、真实HTTP请求、失败重试、重开/分支，以及完整测试、Lint、稳定签名和覆盖升级门禁；以最终Actions及实际下载产物为准。

## 候选验证

源403ad38的[Actions38583010493](https://github.com/veryy/Solvit/actions/runs/38583010493) build与verify-ui成功。实际完整下载unit artifact11903609319及UI artifact11904952995，ZIP CRC/XML核对465单元、63设备零失败/错误/跳过。混合PDF中英文OCR、实际原图PNG解码/像素与范围越界拒绝、聊天HTTP/失败重试/重开/编辑分支通过。实际查看原图截图确认第2页的中英文扫描原文和关闭按钮完整。固定签名与覆盖升级门禁成功。

保留一次失败：此前原图候选38581841892在旧工作区终端用例中发生系统WebView原生SIGSEGV，尚未运行PDF用例。后续完整候选未修改或跳过该断言而通过；具体触发原因仍待跟踪，不宣称已修复WebView崩溃。

## Linux 安装收尾修复

0.33.0正式38586931701的Linux设备任务失败于安装READY后立即安装Java：旧Job仍处在finally，begin将其当成仍在安装。保留原标签且不公开该版本。0.33.1在READY/FAILED/CANCELLED后接收下一操作时先join旧Job的收尾，正在下载或安装时仍拒绝重复启动；不修改原完整Ubuntu/Python/Node/Java/PTY回归断言或超时。该修复仍待远程执行，以新正式门禁为准。

修复候选74b36ae的[Android38588850320](https://github.com/veryy/Solvit/actions/runs/38588850320) build与verify-ui成功。完整下载unit artifact11907405980和UI artifact11906834325，ZIP CRC/XML核对465单元与63设备零失败/错误/跳过；实际查看原始扫描页截图，正文和关闭按钮正常。独立[Linux38589013597](https://github.com/veryy/Solvit/actions/runs/38589013597)完整3/3 API35设备用例成功，下载日志核对生产Python3.12.3、Node24.21.0/npm11.19.0及Java17，READY后立即安装Java路径通过。Linux完整探针归档未下载，不宣称其ZIP CRC。正式标签六项门禁和公开APK/源码核对仍待完成。


## 正式发布与实际下载核对

[下载 APK](https://github.com/veryy/Solvit/releases/download/v0.33.1/solvit-debug.apk) · [Release](https://github.com/veryy/Solvit/releases/tag/v0.33.1) · [Actions38592485019](https://github.com/veryy/Solvit/actions/runs/38592485019) · [Linux 对应源码与许可](https://github.com/veryy/Solvit/releases/download/v0.33.1/solvit-linux-sources.zip)

标签v0.33.1固定源859194b094e0c981fefe954baea5afe835003621，应用0.33.1/build67。build、公开搜索、插件、Linux、应用设备验证和发布六项全部成功。正式unit artifact11907569962及UI artifact11909287045已完整下载、ZIP CRC/XML核对：465单元、63设备测试，零失败/错误/跳过。实际查看正式原页PNG，扫描页正文和关闭按钮完整。正式Linux完整任务日志核对3个Android用例通过，严格版本门禁为Python3.12.3、Node24.21.0/npm11.19.0及Java17；Linux完整探针ZIP未下载，不宣称其CRC校验。

公开APK及源码均已实际下载，与正式Actions artifact11908810356/11908501282内对应文件逐字节一致，外层及公开文件ZIP CRC通过。APK为58,048,501字节，SHA256 `17775a7caa47b191e0f0f18e1911b8d402635d924502601c7b83f2409c59f00e`；源码包1,072,569字节，SHA256 `b4f12a82a2685440428a1fe25a295132639f08e82ced198d56f657e013af9e77`。正式构建日志签名校验为稳定证书SHA256 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`，覆盖升级门禁通过。

以上是远程模拟器、固定HTTP测试服务和产物验证。ARM64真机、真实模型文档问答质量、真实模型完整开发任务及stdio MCP仍未验收。上述候选段落保留当时状态，当前正式状态以本节为准。
