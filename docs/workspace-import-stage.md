# 工作区文件导入

状态：下一阶段底层候选，尚未发布。PDF 0.33.0正式38586931701仍在运行，不能把本阶段候选加入已经打好的标签。

目标是选择文件或已有聊天附件，在JS/HTML工作区页面查看名称、大小、类型、来源和目标位置，确认后将原始字节写到AI和Shell共用目录。图片/PDF/代码都属于范围；文本转换或模型已经读到附件，不等于工作区有这个原文件。导入后文件树、预览、Git changes、Shell必须看到同一文件。覆盖应明确选择并带版本检查，不能静默替换；首先提供真正不覆盖的新增文件操作，再补覆盖交互与独立验收，不用这个检查点缩小整体目标。

实际参考锁定DSH d743267的`packages/api/workspace-files/src/index.ts`、`tests/read-bytes.spec.ts`、`packages/fs/fs/src/index.ts::writeText`和`tests/service.spec.ts`：区分字节与文本，容量超限拒绝而非截断，调用作用域与取消信号，写入明确create/update意图和版本。DSH workspace-files本身只读，没有二进制上传API，不能声称直接移植了其上传实现。Operit dbf71916的`core/tools/defaultTool/standard/StandardFileSystemTools.kt::copyFileCrossEnvironment`检查环境/源存在与目录递归，再转到文件系统实现；`ComposeDslFilePickerRequestTest.kt`区分document/directory/media模式与MIME，不将任意宿主路径当成授权。未找到独立同名跨环境复制测试，不据此继承正确性。当前仅借鉴契约与测试边界，不复制LGPL/DSH实现代码，不新增依赖。

## 首个实现检查点

WorkspaceService增加独立importFile：来源字节上限20MiB，指定SHA256，在既有目标目录中新增文件；已有同名文件明确冲突。AndroidWorkspaces沿用工作区和页面授权，复制输入后核对摘要，私有临时文件完整写入并sync，再重验授权/路径/摘要，以同目录硬链接原子发布；并发Shell抢占文件名也失败，不覆盖其文件。完成时核对链接身份和实际文件摘要，回传workspaceId/相对路径/大小/摘要，无私有路径或正文。拒绝越界、符号链接目录、内部.git路径、控制字符与缺失父目录；不在失败时悄悄创建目录。取消/失败正常清理暂存字节及文件。

新增4个生产服务单元用例：30万字节二进制不经过UTF8、现有列表与重建服务可见；已有/提交前并发文件名冲突不覆盖；错误摘要/越界/内部路径/符号链接拒绝；最后授权撤销不留下部分文件。只做diff静态检查，远程未运行。不把JVM文件系统上的硬链接成功代替API35和ARM64设备兼容性；真实Android执行还需加同源用例。

宿主同UID的并发Shell仍能在观察之间改变目录或文件；当前逐阶段重验不构成跨进程文件系统快照，也不声称能防御同UID恶意进程的所有TOCTOU。进程被杀时可能遗留`.solvit-import-*.partial`，启动回收待实现。系统文件选择器、私有URI生命周期、聊天附件来源授权、JS页面/宿主桥、确认/重名处理、原图与PDF预览、进度取消、多语言、真实Shell/Git/设备截图和正式APK均须继续完成。
