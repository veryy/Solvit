# 文档附件：后续实现与验收

状态：研究，尚未实现。先完成0.32.0附件编辑和清理的发布门禁，再接入本阶段；不以研究说明或文件卡片宣称模型已经能读PDF。

实际参考 Operit dbf71916 的 `util/DocumentConversionUtil.kt`（extractTextFromPdf/convertPdfToTextWithOcr）、`core/tools/defaultTool/standard/StandardFileSystemTools.kt` 的PDF读取分支、`data/model/AttachmentInfo.kt`，以及 `AttachmentInfoTest.kt`/`ChatMarkupRegexAttachmentTest.kt` 的附件引用行为。锁定依赖为pdfbox-android2.0.27.0、ML Kit多语言识别；文件系统工具调用转换器，读取转换后的文字并返回工具结果。转换器先PDFBox取文字，内容很少时逐页PdfRenderer渲染并OCR。没有找到该锁定仓库独立的PDF转换/OCR测试，不能继承未经证明的正确性。

Solvit现状：选择器、私有原文件、消息引用和HTTP图片/文本路径已实现；`ChatAttachmentValidation`和`AttachmentContentResolver`仍拒绝PDF。加入MIME或预览卡片不能解决模型读取。宿主应提供可替换文档读取服务，聊天只依赖服务契约；Android解析、OCR和原文件权限由宿主管理，插件可调用同一受限能力。PDFBox的API调用需单独核对版本、许可证和Android兼容性，不复制Operit的LGPL转换器；不为读取PDF引入其iText写入依赖。

交付链：选择真实PDF → 保存独立原文件及SHA → 预览页/提取结果 → 明确可读页与处理状态 → 模型请求中包含实际内容和页来源 → 失败详情/取消 → 重开、重试、编辑分支保持同一内容。带文字和扫描PDF均属于最终支持范围；加密、损坏、空内容必须有明确提示，不能当作空文本成功。OCR模型可用性、语言与首次下载应在页面显示，不暗中要求用户服务Key。

解析/渲染需限定原文件、页数、像素、提取结果和并发内存，逐页可取消并释放Bitmap/descriptor/document。短内容不应直接判定为扫描件；混合PDF按页决定OCR。长文不能静默截断或全部塞进请求，需展示覆盖范围、页选择和既有上下文预算；文档内容作为用户资料，不能改变角色或授权规则。

验收必须使用Android真实PDF字节、文字页和扫描页、多页及中英文、坏文件/密码文件/超限/取消、选中对话归属、SHA校验和清理保留边界；核对实际HTTP正文与页面来源，并查看真实截图。模型读取答案质量另外用真实模型任务验收，不由本地提取或MockWebServer代替。工作区导入复用受授权文件服务，同目录文件内容应与聊天原文件一致；系统选择器不代表工作区导入已完成。

## 服务与模型请求接线（待远程验证）

新增核心DocumentTextService契约：宿主按对话和类型化附件读取PDF，结果携带附件ID/SHA、顺序页码、文字提取/OCR/空页状态；日志字符串不包含正文。完整结果暂限定40页/256KiB UTF-8，不接受遗漏页或静默截断；长文页选择继续保留为后续范围。聊天解析器通过独立服务读取PDF，在请求前核对身份/版本和授权；原图片/文本路径保留。ChatServicePlugin通过声明式依赖接入，正式应用尚未注册Android实现，选择器仍未开放PDF，公开版保持0.32.0。

新增7个用例覆盖多页完整性、UTF-8累计容量、空页/二进制/状态、日志隐去正文、服务结果身份与版本、读取中权限撤销，以及经实际OpenAI Provider和MockWebServer检查HTTP页文字/来源/模式。此HTTP用例的解析服务是夹具，不能证明PDFBox或OCR已能读取真实PDF。仅做diff静态检查，等待远程；Android解析、OCR、预览、进度/取消、工作区导入、实际文件和设备验证均需继续完成。

本次另核对[PDFBox Android官方使用/Apache-2.0](https://github.com/TomRoush/PdfBox-Android)和[ML Kit官方Android文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)：初始化PDFBox资源加载器；ML Kit支持打包模型或首次下载模型，需明确呈现识别语言与可用性。本提交只定义服务，尚未引入这些依赖。上述Operit文件与附件测试已重新读取，9个上游checkout verify-only成功。

## Android真实读取候选（尚未接入产品入口）

新增独立document-android：PDFBox2.0.27.0从经过对话/大小/SHA核对的私有附件读取；临时文件、8MiB主内存/64MiB scratch边界、输出writer和逐页授权/取消检查。短文字页保留文字，空文字页以PdfRenderer逐页渲染，最多2200边长/4百万像素，经打包ML Kit16.0.1识别；识别器支持Latin/中文/日文/韩文构造选择，当前候选默认中文。取消等待识别时，像素和OCR许可留给Task完成回调清理，避免回收仍在识别的Bitmap；后续同实例OCR等待上一任务真正释放。密码文件、禁止提取、损坏、全空、超页/超文字均明确失败，不静默回传空内容。40页选取范围、语言选择与进度界面仍须继续接入，当前拒绝超限而不截断，不以这个候选替代最终长文范围。

3个真实Android用例已写入：PDFBox生成含SHORT文字页和中英文扫描页的真实混合PDF，检查TEXT/OCR与实际字符；损坏/密码文件失败和临时文件回收；实际私有附件读取等待中取消、临时目录回收与下一次读取恢复。应用依赖模块以供设备门禁编译，但DocumentTextPlugin尚未注册，选择器仍拒绝PDF；没有提前开放不经设备验证的入口。PDFBox许可证/NOTICE及ML Kit官方条款来源随模块资产提供；尚未编译或执行这些新用例。

31bf87f的远程38573980984实际失败于talloc固定源码下载curl56，未进入Gradle，核心7用例也未执行。完整失败日志已读取。同类下载在0.32候选出现过，下一步只为两个Samba官方HTTPS归档地址增加有界连接/请求期限与网络错误重试，仍严格校验原固定SHA，不更换依赖版本或跳过原生构建。参考官方talloc下载页与download.samba.org/pub/talloc目录。

## 页范围、语言与进度的一致性候选

新增DocumentSelection作为PDF附件元数据：起页/止页（最多40页）、Latin/中文/日文/韩文OCR；总文件页数限定5000，默认仍读取全部，超40页需明确选择而非截断。DocumentText验证所选范围每页齐全、保留原页码和总页数；模型请求检查结果范围/语言与附件选取一致，并明确范围外页面未读取。SQLite沿用原attachments JSON列的可选字段，无schema升级；旧记录保持默认，错误字段类型不静默丢弃。编辑副本保留选择，摘要来源摘要纳入选择，避免复用旧范围的摘要。

DocumentTextService增加读取事件Flow，Android宿主在文字提取/扫描识别/页完成时发进度，最终独立发完整结果；默认替换服务也可直接返回结果。新增3个核心/聊天/SQLite用例，覆盖40页片段的页码完整性、摘要失效、请求范围不匹配拒绝、选择重开/分支与原字节保留；混合PDF设备用例追加仅选第2页的OCR与进度检查。尚待远程编译执行。产品页面/语言与范围编辑、PDF选择器、宿主注册及真实PDF->聊天HTTP仍须继续，不提前发布。
