# 聊天附件：实现与验收

用户要求先对齐聊天图片、文件上传及其他日常功能，屏幕操控暂缓。此阶段覆盖选择、预览、模型实际读取、持久保存、重试/队列及工作区联动，不以上传按钮或附件卡片作为完成。

## 参考与当前缺口

已读取固定Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb 的 `data/model/AttachmentInfo.kt`、`services/core/AttachmentDelegate.kt`、`ui/features/chat/attachments/AttachmentUtils.kt` 和 `data/model/AttachmentInfoTest.kt`：附件携带名称/MIME/大小，支持多个附件、重名处理及发送前状态。仅采用行为，不复制LGPL源码。

读取固定DSH d743267388641bc76f17c45ce8b4c231aed1d32c 的 `packages/session/session-persistence-jsonl/tests/content-admission.spec.ts`：内容块必须在持久化、队列和摘要链路保持完整，未知内容不应静默丢弃。仅研究MIT代码行为，不引入Cordis/Node运行时。

Solvit目前ChatMessage/ModelMessage只有文本，ChatService.send和ChatViewModel队列同样只有字符串，OpenAI序列化只有content字符串，Anthropic canonicalBlocks只有文本与工具。已有插件页面文件选择器不等于聊天附件。SQLite当前版本9，无附件引用字段。

## 第一项实现：私有持久附件

新增ChatAttachment元数据契约和ChatAttachmentStore服务接口。宿主持有输入流，随机UUID作为内部文件名，显示名称不能参与路径解析。每个对话独立目录；文件导入上限20MiB，流式检查真实大小、同步后安装。读取复核长度和SHA-256，拒绝外部链接和跨对话读取；导入权限撤销/超限清理本次临时及已安装文件，不删除用户原始文件。存储必须使用filesDir而非cacheDir。

真实文件系统回归覆盖重名独立、Unicode、服务重开、同长度损坏、外部符号链接与源文件保留、跨对话拒绝、安装后撤权及超限无残留。此项尚未远程验证，也尚未注册到App或接入消息、选择器和模型请求，不宣称附件功能已可用。全局磁盘配额、孤立附件回收及删除聊天/分支的引用归属仍需实现。

## 完整交付要求

1. 系统图片/文件选择，取消不改变草稿；缩略图、大图/文本预览、附件卡片和发送前移除。
2. 消息与SQLite迁移保存附件引用；图片字节不写入日志或请求诊断，重开能读取，原聊天数据保留。
3. OpenAI兼容图像content块与Anthropic原生图像块实际发送；文本/代码文档读取明确大小和编码限制。PDF处理单独实现，不把存储PDF视为模型已读文档。
4. 带附件的空文本发送、失败重试、队列、编辑/移除和打断正确归属；不能在排队时只保存文字。
5. 视觉能力选择/错误提示、Token预算及摘要保留附件来源；不声称所有OpenAI兼容模型支持视觉。
6. 文件导入工作区和预览共用当前目录权限；用户取消或切换聊天不得落入其他目录。
7. GitHub Actions验证原测试、真实HTTP模型请求、数据库迁移及Android选择/预览/保存/重开；固定签名APK和覆盖更新后发布。真实模型和真机验收单独记录。

## 消息与数据库引用

ChatMessage新增默认空附件列表，限定用户消息/最多8个且ID不能重复；ModelMessage保留引用，completeContext投影不丢弃用户附件。SQLite版本9→10添加默认空attachments列，保存元数据而非图片字节；读取完整引用并校验。新增实际数据库重开及真实v9消息表迁移回归，保留已有文字历史。此项待远程验证；模型图像编码、摘要/分支文件归属、选择器和App接入仍待完成，不将数据库字段视为可上传。

## 首轮远程失败与修复

350aaf9的Android38501256335失败，实际产物11865677764取回检查CRC/XML：新测试初始化错误为方法必须void；三个既有迁移场景明确duplicate column name: attachments。前者显式runBlocking<Unit>，后者升级前检查真实列，避免低版本号但已存在附件列时重复添加。真实v9消息表迁移仍独立验证新增列及旧内容。附件文件回归没有失败；本次完整测试尚未通过，不宣称新APK交付。

## 模型图片内容块候选

继续读取Operit同版本api/chat/llmprovider/OpenAIProvider.kt:824–889的image_url/data URI编码和DeepseekProviderMediaRoleTest.kt用户图片角色回归。参考其视觉能力检查及历史图像输入，未复制实现。另核对官方格式：[OpenAI图像输入](https://developers.openai.com/api/docs/guides/images-vision)、[Claude视觉](https://platform.claude.com/docs/zh-CN/build-with-claude/vision)。

新增临时ModelImage输入（UUID、受限图片MIME/Base64与大小，toString隐藏字节），仅用户ModelMessage可携带最多8张。OpenAI将文字与image_url块放在同一用户消息，Claude使用image/source/base64块。新增OpenAI实际HTTP请求回归、Claude图片/签名工具后续步骤及改变图片后拒绝旧签名回归。尚未实现私有附件→ModelImage解析与App选择器，因此不能在界面上传。

包含图片时Claude不持久保存带Base64的requestPrefixJson，每步从原用户内容重新构造并校验prefixHash，保留原生助手思考/工具块，不重复保存用户图片字节。普通文本的前缀重放保持。图片预算暂按每张4096预留，明确不是供应商实际usage；后续需尺寸/能力/总请求字节门禁。附件元数据加入摘要前缀校验（无附件历史摘要的旧摘要值不变），摘要资料保留名称/类型/ID/内容版本并明确没有原文，不允许据名称虚构内容。

此候选待Actions验证，真实模型视觉能力、图片格式解码/尺寸限制、请求诊断脱敏、PDF/文档解析、分支引用归属和全局存储配额仍未完成。不能将ModelImage编码测试当作上传功能完整交付。

## 私有附件内容解析候选

新增宿主AttachmentContentResolver：读取存储校验后的原字节，图片编码为临时ModelImage，文本/代码严格UTF-8解码并保留来源名称/ID/SHA框架。整条消息最多8项/20MiB，文本单项256KiB；无效UTF-8、二进制NUL、未支持MIME、撤权明确失败，不返回部分内容。PDF仍未实现，明确拒绝而非假装读完。解析暂存ByteArray完成后清零，不修改存储原字节。新增内容解析回归属于转换层，不代替生产文件存储、图像有效性/尺寸、HTTP真实模型、App界面或全链路。

下一步须注册服务与ChatService的带附件发送契约，再接ViewModel草稿/队列、系统选择器和消息预览；同时处理分支复制后的附件目录归属、删除回收和真实用户视觉能力选择。当前解析器尚未调用，不能宣称聊天上传可用。
