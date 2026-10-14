# 工作区文件导入

状态：下一阶段底层候选，尚未发布。PDF 0.33.0正式38586931701仍在运行，不能把本阶段候选加入已经打好的标签。

目标是选择文件或已有聊天附件，在JS/HTML工作区页面查看名称、大小、类型、来源和目标位置，确认后将原始字节写到AI和Shell共用目录。图片/PDF/代码都属于范围；文本转换或模型已经读到附件，不等于工作区有这个原文件。导入后文件树、预览、Git changes、Shell必须看到同一文件。覆盖应明确选择并带版本检查，不能静默替换；首先提供真正不覆盖的新增文件操作，再补覆盖交互与独立验收，不用这个检查点缩小整体目标。

实际参考锁定DSH d743267的`packages/api/workspace-files/src/index.ts`、`tests/read-bytes.spec.ts`、`packages/fs/fs/src/index.ts::writeText`和`tests/service.spec.ts`：区分字节与文本，容量超限拒绝而非截断，调用作用域与取消信号，写入明确create/update意图和版本。DSH workspace-files本身只读，没有二进制上传API，不能声称直接移植了其上传实现。Operit dbf71916的`core/tools/defaultTool/standard/StandardFileSystemTools.kt::copyFileCrossEnvironment`检查环境/源存在与目录递归，再转到文件系统实现；`ComposeDslFilePickerRequestTest.kt`区分document/directory/media模式与MIME，不将任意宿主路径当成授权。未找到独立同名跨环境复制测试，不据此继承正确性。当前仅借鉴契约与测试边界，不复制LGPL/DSH实现代码，不新增依赖。

## 首个实现检查点

WorkspaceService增加独立importFile：来源字节上限20MiB，指定SHA256，在既有目标目录中新增文件；已有同名文件明确冲突。AndroidWorkspaces沿用工作区和页面授权，复制输入后核对摘要，私有临时文件完整写入并sync，再重验授权/路径/摘要，以同目录硬链接原子发布；并发Shell抢占文件名也失败，不覆盖其文件。完成时核对链接身份和实际文件摘要，回传workspaceId/相对路径/大小/摘要，无私有路径或正文。拒绝越界、符号链接目录、内部.git路径、控制字符与缺失父目录；不在失败时悄悄创建目录。取消/失败正常清理暂存字节及文件。

新增4个生产服务单元用例：30万字节二进制不经过UTF8、现有列表与重建服务可见；已有/提交前并发文件名冲突不覆盖；错误摘要/越界/内部路径/符号链接拒绝；最后授权撤销不留下部分文件。只做diff静态检查，远程未运行。不把JVM文件系统上的硬链接成功代替API35和ARM64设备兼容性；真实Android执行还需加同源用例。

宿主同UID的并发Shell仍能在观察之间改变目录或文件；当前逐阶段重验不构成跨进程文件系统快照，也不声称能防御同UID恶意进程的所有TOCTOU。进程被杀时可能遗留`.solvit-import-*.partial`，启动回收待实现。系统文件选择器、私有URI生命周期、聊天附件来源授权、JS页面/宿主桥、确认/重名处理、原图与PDF预览、进度取消、多语言、真实Shell/Git/设备截图和正式APK均须继续完成。

追加真实API35设备用例到现有隔离WorkspaceExecutionDeviceTest：通过生产AndroidWorkspaces实际导入30万字节二进制，文件列表与原字节核对，经生产ShellJobEngine启动同目录Android Shell调用toybox sha256sum核对摘要/退出码，再检查错误来源版本拒绝及最后授权撤销无暂存残留。尚待远程执行；不将它当作JS插件导入UI或真实AI选择工具证明。

源ecbc163的远程38588341397 build成功；实际完整下载unit artifact11907380409，ZIP CRC/XML核对469单元零失败/错误/跳过，其中WorkspaceFileImportTest四项均通过。该轮设备任务仍在运行，不能提前宣称Android硬链接或Shell摘要验证通过。0.33.1安装交接修复在另一分支独立验证，本分支未混入当前发布标签。

## 真实Android否决硬链接发布

38588341397/verify-ui实际失败：`WorkspaceExecutionDeviceTest.actualAndroidBinaryImportPublishesCompleteBytesToTheBoundShellWithoutOverwrite`在`UnixFileSystemProvider.createLink`抛AccessDeniedException，52个应用设备用例中仅此项失败。源与暂存已实际创建并完成SHA检查，证明JVM硬链接策略不能充当Android发布实现。先排序检查Android链接限制、目录权限、暂存状态；日志明确失败位置在link，先前写入/读取成功，不调整系统权限或直接普通rename覆盖。具体SELinux拒绝原因尚未取回AVC，不笼统宣称所有Android文件系统禁止硬链接。

改为独立workspace原生适配器，以同一打开目录fd调用renameat2(RENAME_NOREPLACE)，目标已存在返回EEXIST，旧文件不变；临时完整文件在发布前仍sync与SHA核对，发布后重新核对实际字节。目录O_DIRECTORY/NOFOLLOW与来源regular-file检查保留，ABI沿用ARM64/ARMv7/x86_64/x86，NDK28，不改变SDK或root权限。核对[Linux man-pages官方rename说明](https://www.man7.org/linux/man-pages/man2/renameat2.2.html)：此标记要求内核/文件系统支持，缺失时明确失败，不降级成可能覆盖的rename。没有复制外部实现代码。

JVM边界用例显式注入create-only发布适配器，只验证宿主事务和授权，不把它当JNI通过。生产Android用例仍使用默认JNI，并追加最后授权检查时并发Shell创建同名文件，要求FileAlreadyExistsException且内容不变。原Shell摘要/完整二进制/撤权用例保留，等待远程再次执行。当前不发布工作区导入APK，0.33.1安装交接回归已通过，独立进入正式发布门禁。

## 文件选择宿主候选

UI候选分支保留历史merge了main的0.33.1安装收尾修复，版本准备0.34.0/build68；没有加入已启动的v0.33.1标签。PageDocumentAccess追加可选selectFile，旧文本/ZIP接口保持；宿主复用原OpenDocument选择器，同一页面只允许一个文件选择/保存等待，最多读取20MiB原始字节，不给JS URI或文件路径。PageBinaryDocument保存固定大小/SHA，防御复制，close清空并拒绝后续读取，诊断不输出名称/正文；不解码成UTF8、不把图片/PDF转成文本替代原文件。

取消/关闭/授权失效后，未交接对象清空，延迟系统结果不转给新请求；同一Outstanding槽等旧选择器返回，避免旧URI污染下一次选择。追加2个核心用例核对二进制/摘要、输入与返回副本、日志和关闭失效、无效类型/名称/超容量。当前入口还未连到JS工作区页面，实际选择器、插件桥、预览/确认、进度取消及设备完整链继续实现；本候选仅静态diff检查，不提前宣称远程执行通过。

## 页面归属的导入草稿桥

PageWorkspaceAccess追加host-only stageImport；WorkspacePageBridge捕获对话/工作区，接受选择后只返回不含URI/正文的id、文件名、类型、大小、SHA。一页只持有一个原文件，替换、关闭和成功导入清空旧字节；无工作区的普通聊天拒绝而不自动创建工作区。importFile动作必须匹配当前host token与SHA，路径来自已确认控件，经原生WorkspacePageAuthority在锁内重验。冲突保留选择供改名重试，取消/失败不自动覆盖或删除已有文件。

新增4个真实宿主桥单元入口：选中文件只返回元数据且确认后原字节写入；旧token/切换共享工作区对话拒绝及关闭释放；重名保留草稿、提交前撤权无部分文件、改名重试；普通聊天拒绝并释放来历文件。JVM发布适配器显式注入，仅验证宿主桥；JNI设备验证继续依赖独立原用例。

ScriptPageHostAction加入importFile/dropImport严格字段检查，ScriptPageExecutionContext要求API22，旧API1..21不能调用新动作。当前manifest上限仍21，新动作暂不能由发布插件发起；后续还须增加API22声明、文件选择动作、BuiltinWorkspaceManager文档选择器接入、TS SDK/HTML页、市场旧版本保留，以及实际选择→预览→确认→Shell/Git/重开与撤权验证。本文不把几个服务接口当成产品入口已完成。新增桥代码尚未远程编译执行。

## 选择器与插件执行上下文连接

0.33.1已经公开，下载和正式门禁见release-0.33.1.md。导入候选7569a8c/38592230545的编译成功，设备任务失败于旧ChatLayoutTest.openWorkspace的10秒等待，不是新增二进制导入的异常；完整设备报告尚待下载核对，不将任务失败说成JNI再次失败，也不以此宣称整套功能通过。

本检查点继续核对上述DSH readBytes实现/实际read-bytes.spec.ts和Operit ComposeDslFilePickerRequestTest；采用原始字节、容量拒绝、显式用户选择的边界，不复制上游代码。新增selectImport已显示控件动作：只接受空参数，不能由JS指定URI/路径；API22及workspace.files声明同时满足才可选择、查询或提交导入。先核对绑定工作区再打开系统选择器，返回后重验授权；未交接文件遇撤权或错误即close，成功只移交给当前WorkspacePageBridge，取消保留已有选择。BuiltinWorkspaceManager将宿主PageDocumentAccess传入真实脚本页。

增加四个宿主执行边界用例：旧API/未声明服务/隐藏URI拒绝且不打开选择器，无绑定拒绝；30万原字节一次性移交、不调用写入；选择器延迟返回时撤权清空；用户取消保留旧草稿。这些不是实际DocumentsUI或QuickJS API22整链验收。manifest暂保持API21上限，市场与SDK/UI切换尚未完成；本检查点待远程编译与执行。
