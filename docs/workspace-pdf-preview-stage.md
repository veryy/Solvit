# 工作区 PDF 原页与文字预览

状态：0.34.0文件新增导入及文本/图片预览已发布。当前工作区PDF原页/文字预览仍在候选，尚未发布APK；不能将底层renderer或测试源码当作插件界面已可用。完整联动范围仍保留已有聊天附件导入、版本确认覆盖、进度取消/崩溃暂存回收，见[完整导入阶段](workspace-import-stage.md)。

## 实际参考与适配

再次核验九个upstream checkout。Operit dbf71916的`ui/features/chat/webview/workspace/WorkspaceReadOnlyDocumentPreview.kt::WorkspacePdfPreview/WorkspacePdfPage/resolvePdfPreviewState/renderPdfPage`使用ParcelFileDescriptor/PdfRenderer，以实际pageCount与页范围渲染原页，LazyColumn按需请求，白底Bitmap。`DocumentConversionUtil.kt::extractTextFromPdf`先PDFBox提取再OCR；`AttachmentViewerDialog.kt::isTextLikeMimeType`不把PDF当UTF8文本，`AttachmentInfoTest.kt`明确application/pdf元信息。未找到该工作区PDF renderer对应的实际单元/设备测试；`StatusCardHtmlDocumentTest.kt`只测离线HTML字体，与PDF解码无关，不据此继承PDF正确性。DSH d743267的`workspace-files/src/index.ts`和`tests/read-bytes.spec.ts`核对原始字节、窗口/容量与调用权限，不提供PDF renderer，也不把其绝对路径协议直接暴露给JS。

仅采用原页渲染/实际页数/按需读取和格式区分原则，自行编写Android适配，不复制LGPL源码或引入上游运行时。复用本项目已经发布的PDFBox 2.0.27.0、Android PdfRenderer和JPEG限额编码，依赖/许可通知保持，不新增库。

## 首个原生检查点

提取共用PdfSourceAccess/readablePdf：聊天附件和工作区选中文件均先校验固定容量/SHA/PDF头、原生授权和取消，在独立UUID私有缓存中写入源，再打开文档。拒绝密码保护/禁止提取，失败或取消清理；启动时回收两个专属PDF缓存中的异常退出目录。聊天原有混合PDF/OCR/页范围/HTTP/持久化回归保留完整执行，不能用新服务测试替代。

PagePdfPreviewRenderer只接当前页持有的PageBinaryDocument和1-based页码。PDFBox实际检查最多5000页，仅提取当前页前8192 UTF16单元，达到明确预览边界停止输出，不分裂代理对；truncated明确显示。扫描/空文字页仍返回原页，不伪造文字或宣称执行OCR。Android PdfRenderer渲染同一原文件的当前页，白底、最长边640、JPEG最多64KiB；与图片预览共用编码器，normal close释放图片副本。同步解析/渲染不能承诺即时打断或进程隔离，原PDF字节始终不变，不写工作区。

新增核心边界/资源关闭测试和五个真实Android用例：第二页文本与蓝色像素/完整来源、嵌入扫描图无虚构文字、8192边界明确截断、坏PDF/密码/撤权、暂存后取消及再次读取。尚待GitHub Actions编译和执行，未进行本地构建。下一步必须接API版本与服务声明、选择id/SHA/对话授权、插件HTML页码/原页/正文、六语言、真实HTML截图与原文件导入、市场不可变历史及完整正式APK门禁。

## API23 与插件页面检查点

新增 importPdf 查询只向声明 workspace.files 的 API23 页面开放，沿用原生选择 token、SHA、当前对话和工作区授权；返回当前页 JPEG、文字、页数与截断标记，返回后释放原生图片副本。工作区插件1.6.0提供六语言原页/文字预览与页码导航，预览不写文件，确认仍导入完整原PDF。Git因共享页面源码升1.2.2，保留已公开的workspace1.5.0/Git1.2.1原始包。客户端市场仍使用API22，等待服务端兼容验证后再切换。

原生源bfd43c2的Android38614115299 build已成功，真实设备任务仍运行；没有把编译成功当作PDF原页或插件页面验收。此检查点生成包仍待远程TypeScript生成同步，真实HTML像素与API22升级测试随后补齐。
