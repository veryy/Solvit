# 文档附件：后续实现与验收

状态：研究，尚未实现。先完成0.32.0附件编辑和清理的发布门禁，再接入本阶段；不以研究说明或文件卡片宣称模型已经能读PDF。

实际参考 Operit dbf71916 的 `util/DocumentConversionUtil.kt`（extractTextFromPdf/convertPdfToTextWithOcr）、`core/tools/defaultTool/standard/StandardFileSystemTools.kt` 的PDF读取分支、`data/model/AttachmentInfo.kt`，以及 `AttachmentInfoTest.kt`/`ChatMarkupRegexAttachmentTest.kt` 的附件引用行为。锁定依赖为pdfbox-android2.0.27.0、ML Kit多语言识别；文件系统工具调用转换器，读取转换后的文字并返回工具结果。转换器先PDFBox取文字，内容很少时逐页PdfRenderer渲染并OCR。没有找到该锁定仓库独立的PDF转换/OCR测试，不能继承未经证明的正确性。

Solvit现状：选择器、私有原文件、消息引用和HTTP图片/文本路径已实现；`ChatAttachmentValidation`和`AttachmentContentResolver`仍拒绝PDF。加入MIME或预览卡片不能解决模型读取。宿主应提供可替换文档读取服务，聊天只依赖服务契约；Android解析、OCR和原文件权限由宿主管理，插件可调用同一受限能力。PDFBox的API调用需单独核对版本、许可证和Android兼容性，不复制Operit的LGPL转换器；不为读取PDF引入其iText写入依赖。

交付链：选择真实PDF → 保存独立原文件及SHA → 预览页/提取结果 → 明确可读页与处理状态 → 模型请求中包含实际内容和页来源 → 失败详情/取消 → 重开、重试、编辑分支保持同一内容。带文字和扫描PDF均属于最终支持范围；加密、损坏、空内容必须有明确提示，不能当作空文本成功。OCR模型可用性、语言与首次下载应在页面显示，不暗中要求用户服务Key。

解析/渲染需限定原文件、页数、像素、提取结果和并发内存，逐页可取消并释放Bitmap/descriptor/document。短内容不应直接判定为扫描件；混合PDF按页决定OCR。长文不能静默截断或全部塞进请求，需展示覆盖范围、页选择和既有上下文预算；文档内容作为用户资料，不能改变角色或授权规则。

验收必须使用Android真实PDF字节、文字页和扫描页、多页及中英文、坏文件/密码文件/超限/取消、选中对话归属、SHA校验和清理保留边界；核对实际HTTP正文与页面来源，并查看真实截图。模型读取答案质量另外用真实模型任务验收，不由本地提取或MockWebServer代替。工作区导入复用受授权文件服务，同目录文件内容应与聊天原文件一致；系统选择器不代表工作区导入已完成。
