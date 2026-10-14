# 工作区 PDF 原页与文字预览

状态：工作区PDF原页/文字分页预览已在0.35.0正式发布；490单元/75设备与六项正式门禁成功，正式截图及公开APK/源码逐字节核对完成。[下载与验证](release-0.35.0.md#正式发布与实际下载核对)。下文保留各检查点历史；完整文件联动仍包括聊天附件导入、带版本覆盖、进度取消/异常暂存回收及真机/真实模型任务。

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

HTML设备用例使用真实两页PDF、实际选中文件URI和生产renderer，检查第二页蓝色像素、文字和页码边界，预览不写文件，取消选择与确认保持原页，最终磁盘PDF字节等于原文件。系统DocumentsUI触摸仍不在此范围。新增已公开API22/1.5.0迁移用例，保留启用状态、权限和关闭项目规则配置，修改包及卸载不被覆盖/恢复。

远程TypeScript38615567853生成与typecheck/118 SDK零失败，但市场断言仍要求Git最新1.2.1，实际1.2.2使原检查失败。修正仅最新版本预期，保留API19原始Git字节与旧1.2.1下载检查；等待原工作流复验，不宣称本轮全部通过。

远程TypeScript38615928135全部成功，完整任务日志核对118 SDK/5市场零失败，公网搜索/MCP/Skills门禁通过。实际下载生成artifact11919273507并核对ZIP CRC，仅同步workspace/Git两包，其他当前包字节保持。新workspace1.6.0/API23为759034字节/SHA7e365776b2df302b79d338980e5ce990f233d7f3a7762399a89c8fa3a50283b6；Git1.2.2/API20为741822字节/SHA1d7aa4b3556104907dac7e29d7ea4fb5e35a7f6565d3a6bf668771e643b8130b。候选API23私有市场兼容脚本逐项检查API1..19目录不变、API20..22仅兼容Git补丁、API22继续workspace1.5.0及全部历史下载字节；仍待远端实际执行。完整APK/真实HTML与API22迁移回归随后执行。

原生bfd43c2/Android38614115299现已完整成功：实际下载11918424850单元及11920401437设备归档，两个ZIP CRC通过；XML为487单元与74设备，零失败/错误/跳过，明确包含两个PagePdfPreviewTest和全部五个PdfImportPreviewDeviceTest。原有聊天PDF与图像设备回归同轮执行。这是原生检查点证据，不等于后续API23 HTML验收。

私有市场before/next实际校验完成，来自线上当前API22部署快照：API1..19完整目录相同，API20..22仅Git兼容补丁且API22仍选择workspace1.5.0；40个已公开版本全部下载字节保持，新API23包与远程生成SHA一致。staging路径.staging-api23-1792002855，尚未切换线上服务和客户端hostApi。

候选7457b74/Android38616204223的build成功，实际下载11919662834并核对完整ZIP CRC/XML：490单元零失败/错误/跳过，明确包含选择/授权后图片关闭、API23声明门禁、真实1.5.0旧包迁移三个新增回归。Lint/APK/固定签名步骤成功，设备HTML任务116035044686仍在准备阶段；尚无候选HTML截图或正式0.35.0公开APK。HTTPS更新前22个旧API目录、40个历史下载已实际下载并SHA核对，记录public-before；私有API23服务尚未发布，客户端仍为hostApi22。

候选7457b74的Android38616204223已全通过。实际下载UI11920259565（5577055字节），完整ZIP CRC/XML为75设备零失败/错误/跳过；WorkspacePdfHtmlTest通过真实两页PDF、生产选中文件/renderer/QuickJS/HTML，检查当前页文字、蓝色像素、页码越界不读取、取消选择/确认保留页与路径、确认导入磁盘原字节。实际查看截图，第二页PAGE2/蓝色矩形、上一页/查看页面/禁用下一页及底部确认按钮均完整可见；截图保存到screenshots/0.35.0/workspace-import-pdf-candidate.png。原有PDF、图片、Git/Shell及覆盖升级回归同轮保留通过。

API23服务已从验证过的private staging更新，备份.backup-api23-1792004121872。更新后实际HTTPS校验23 API目录及40个历史下载SHA/字节；API1..19目录完全不变，API20..22仅Git兼容补丁，API22仍workspace1.5.0，API23当前workspace1.6.0/Git1.2.2下载与仓库一致。之后才将客户端catalog请求切换hostApi23并更新真实MockWebServer断言。候选完整设备与截图已通过，准备FF合入main及不可变v0.35.0标签；最终同源六项门禁与公开APK/源码仍需执行。
