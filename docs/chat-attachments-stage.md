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

## 发送服务与队列候选

ChatService增加带附件发送契约，旧实现只接受空附件而非静默丢弃。DefaultChatService支持仅附件发送，先保存原消息引用，再读取生产附件服务转换为模型用户内容；后续轮次重读历史图片。SQLite插件注册私有filesDir附件服务，App组合明确要求该服务。队列记录快照附件列表，发送、直接打断、排队编辑空文字和出队保持附件；限制数量/总大小。新增实际ChatService持久引用→模型内容→后续轮次回归，以及ViewModel空文字图片排队/外部列表改变/直接打断回归。尚未远程验证，选择器和预览尚未接入；分支附件文件复制与删除回收仍须完成。

## 分支附件归属候选

SQLite存储与插件注册共用同一个AttachmentFiles实例。创建分支在IO事务中为新对话独立复制附件文件，复制按声明大小限制、长度/SHA复核，保留原附件元数据引用；删除原对话不会删掉分支副本。复制失败清理本次独占新目录，不删除原附件。删除聊天先提交SQLite删除，再删除自己的平铺附件目录，拒绝意外子目录，不跟随外部符号链接。

新增真实SQLite/私有文件回归：附件消息→回复→分支→删除原聊天→重开分支仍读相同内容→删除分支后文件不可读。全局磁盘配额和事务失败后的孤立新分支目录回收仍需完善；本次代码/回归未远程通过，不以复制实现替代选择器或页面验收。

## 选择与聊天预览候选

再次读取Operit AttachmentPreview.kt:45–115和AttachmentDelegate.kt:205–270，采用系统URI读取名称/MIME、私有复制、多附件横向卡片和发送前移除行为，未复制实现；没有加入截图/屏幕控制。App新增OpenMultipleDocuments、按选取时对话绑定导入，迟到选择与聊天切换拒绝；UI不要求广泛外部存储权限。导入验证图片解码尺寸≤12000边/3600万像素、真实MIME匹配，文本≤256KiB/有效UTF-8/无NUL；未支持文档明确拒绝。

附件草稿按对话保留，发送/排队成功后移交消息并清空草稿引用；输入框支持仅附件发送、导入进度和附件移除。聊天用户框显示附件卡片，图片缩略图/最大2048采样大图、文本独立全页可复制查看；图片预览从校验后的私有字节解码，不能直接使用外部URI。实时用户行同样携带附件，避免存储回调到达前只出现空白文字行。新增六语言文案与Robolectric真实PNG/MIME伪装/文本编码/不支持文件回归。

尚未Actions通过，实际Android选择器/导入/发送HTTP/重开/分支/移除和页面截图仍待验收；无视觉模型配置、重试、PDF/其他文档、文件导入工作区及总磁盘配额仍未完成。本次不将源码UI接通视为可下载APK交付。

## 旧聊天删除失败修复

495de45的完整38502934494失败在原SqliteChatStoreTest.migratesOldMessagesAndPersistsToolCallsAcrossReopening：旧v1聊天ID为s，附件清理的UUID限定抛IllegalArgumentException。保留原旧聊天/工具回传/删除断言，将非UUID旧ID映射为固定SHA-256目录名；UUID仍原目录兼容，原始旧ID不作为路径拼接。31项存储测试仅此项失败，修复仍待完整Actions验证。

## Android附件链路验收候选

新增ChatAttachmentsDeviceTest：实际OpenMultipleDocuments launch contract、取消后草稿不变、真实FileProvider图片/文本URI返回、Compose导入与预览、生产AttachmentFiles/SQLite/ChatService/ToolAgentRuntime/OpenAiProvider、真实MockWebServer HTTP请求、空文字发送、请求图片字节与选取PNG逐字节一致、文本正文包含中文/emoji、发送后查看文档、关闭SQLite重开保存引用与文件一致。选择器返回通过测试ActivityResultRegistry注入，未宣称系统DocumentsUI人工操作或真实模型视觉质量已验收。

缩略图单独144像素采样，大图最多2048；选择器目标对话跨配置保留，application/octet-stream按已知文本扩展解析，其他二进制仍拒绝。队列的仅附件项显示文件名，编辑空文字仍可保存。新增Android测试依赖MockWebServer4.12.0（项目既有JVM测试版本），由Actions安装/运行，没有本地构建。当前代码和测试尚未远程通过，保留完整原Git/角色/插件/签名/覆盖安装门禁。

## 图像测试环境失败修复

f21af79的38504186035失败在ChatAttachmentValidationTest有效PNG，实际产物11867876290取回检查CRC/XML：失败为“图片内容与文件类型不匹配”，core62/chat-runtime60/provider-openai20/provider-anthropic16/storage-sqlite31全部无失败，App39项仅此项失败。核对Robolectric4.16.1实际ShadowBitmapFactory.java:183–206，legacy decodeByteArray没有给outMimeType赋值；不能把它作为Android原生解码器的MIME证据。测试改用GraphicsMode.NATIVE运行同一PNG/伪装MIME/坏图断言，生产校验不放宽。参考上游原生ShadowNativeBitmapFactory，未复制源码。远程验证仍待完成。

## 历史附件加载前预算

检查完整请求发现每条消息20MiB限制不能限制多轮历史累计的加载内存。增加全保留历史20MiB附件资料门禁，在读取/编码任何文件前检查；超限保留用户消息/文件和失败状态，明确请求新建对话，不静默丢弃资料。新增回归断言超限时文件读取次数和模型执行均为零，两个用户消息的原附件引用仍在。该门禁是当前资源限制，后续需结合摘要/附件按需读取改善长会话；不宣称全局磁盘配额或完整多模态摘要已完成。

## build60实际单元报告

b3ea270的38505362217 build任务成功。取回11867923488（335276字节）检查ZIP CRC/实际XML：431单元零失败/错误/跳过，17项附件相关场景全部执行，包括原生PNG、UTF-8、旧v9迁移、分支删除、历史预算、实际HTTP图像内容和Claude签名后续。Lint、APK和固定签名检查成功；模拟器115663801353仍在运行，不作为页面通过或正式Release证据。正常插件38505598660成功，完整日志计数待核对。

## 首次完整页面验收失败（保留报告）

b3ea270的38505362217页面任务115663801353结束失败。产物11868191477（4184126字节）取回CRC正确，实际XML为App46项/4失败、Web1项通过、PTY10项通过，共57项/4失败，无跳过。附件失败在发送后等待HTTP回复；旧终端用例等待实际命令输出；Git安全表单等待HTML控件；旧只读Git工具测试仅给workspace/git权限，而新包还声明network，安装启用直接拒绝。未延长超时或删除断言。

6664bf6修复旧Git启用测试所需network权限，仍验证工具实际只读capabilities；其他三项仅加入有界测试夹具状态（错误/HTTP次数/消息状态，公共DOM控件ID及disabled，作业退出状态）用于判断实际失败原因。该提交完整Android38507784923的build已成功，页面仍待结果，不能宣称通过。
