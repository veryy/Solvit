# 工作区文件导入

状态：0.34.0发布系统原文件新增导入和文本/图片预览，0.35.0补齐工作区PDF原页/文字分页预览；最新490单元/75设备及正式六项门禁成功，实际截图与公开APK核对完成。完整联动仍包括聊天附件来源、带版本覆盖、进度取消及异常暂存回收。[最新下载与证据](release-0.35.0.md)。下文保留各检查点历史。

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

API22协议候选继续扩展Kotlin manifest、TS类型/打包器与市场服务的版本识别，workspace.files只有API22加workspace权限才合法；现有插件包/内置工作区版本和客户端hostApi请求仍不切换。新增真实QuickJS页面用例，验证显示控件未确认不打开、未声明/旧API拒绝、确认选择只返回元数据及页面关闭回收；SDK打包器增加旧版本/缺权限/未知服务拒绝。市场只接受额外版本号，不改旧API选择排序或历史包。远程生成与测试待执行；不能将协议识别等同于文件导入界面已完成。

## JS/HTML文件导入页面候选

工作区1.5.0/API22源页面增加六种语言的系统文件选择、名称/类型/字节数/SHA/来源、可编辑目标相对路径及单独确认。选择不写入，确认才调用importFile；重名错误停在确认页，可取消返回改名，绝不自动覆盖。取消系统选择保留原选择，放弃选择只释放草稿；成功刷新文件树。当前是文件信息预览，不是图片/PDF正文预览，聊天附件来源、进度/中断、覆盖版本与完整设备链继续待做。

市场保留原workspace1.4.0完整原字节到legacy，显式插回原目录位置；API1..21继续选原版本，API22才选新包。客户端hostApi请求仍保持21，待线上市场兼容验证后再切换。SDK行为用例覆盖选择不写入、空绑定不显示入口、路径越界拒绝、确认取消、重名改名重试和放弃；市场用例覆盖新版本与历史原包下载。生成包尚未本地编译，按约定将由远程Actions生成后取回，候选当前不可作为可用APK交付。

远程生成38597964986成功，日志门禁116 SDK及5市场测试通过；普通push检查尚未收回包而失败：SDK是生成包diff，market是暂时与legacy重复的旧workspace1.4.0键。不会把这些暂态失败当已发布功能。另静态发现git/pages.ts复用workspacePage；Git保持API20，因此显式关闭其新导入入口，避免显示必失败的API22控件。共享代码变化将Git包升1.2.1，保留原1.2.0完整包，禁止替换同版本公开字节。Git读写能力与协议不变。

9c0c90a的远程生成38598548619成功；实际下载artifact11913300855并完整ZIP CRC检查，收回workspace1.5.0/API22与Git1.2.1/API20到市场和APK，其他包逐字节未变。没有本地执行TS构建。增加实际Android界面用例：真实HTML/QuickJS与ContentResolver读取30万字节FileProvider文件，先拒绝原生确认不打开选择器，再查看元数据与SHA，取消不写入、同名失败保留旧内容、改名成功，生产Git状态看到untracked、生产Shell校验SHA与退出码、关闭重开文件树可见，并导出两张截图。ActivityResultRegistry注入真实URI，不宣称实际点击系统DocumentsUI。新用例尚未远程编译执行，图片/PDF内容预览及其他完整范围仍待后续。

## 文本内容预览候选

再次读取Operit AttachmentViewerDialog的MIME/容量检查与AttachmentInfoTest：它们区分图片、文本和PDF，不将所有字节当文本。Solvit新增独立importText只读查询，API22/workspace.files及当前选择id/SHA同时检查；严格UTF8及NUL判定，最多256KiB源文本、显示前8192 UTF16单元，代理对完整，截断明确标记。超限和二进制只返回分类，不暴露正文；不能把信息预览叫做图片/PDF内容预览。JS页面按钮显式请求，正文以text节点显示，不执行脚本，保留正在编辑的目标路径；原始导入字节完全不变。新增核心严格解码/容量/截断/关闭用例、真实bridge token/选择生命周期及预览不写入用例、TS页面字段与预览状态用例。尚待远程生成及验证；图片/PDF内容预览、聊天附件来源、覆盖版本、进度中断/崩溃回收仍保留完整待办。

f0d121f/38599238571 build实际失败在WorkspaceInstructionsHookTest四个旧版本断言，包目标已为1.5.0而断言仍1.4.0；其他迁移断言没有取消。修正目标版本，并静态核对发布1.4.0的完整SHA为675764bb...db41，发现升级白名单尚未包含这版；补入精确哈希，新增原API18→API22真实restore回归，保留启用/权限/projectRules、修改包不覆盖和卸载不重装断言。报告归档下载待核对。旧API22协议分支38597379623的设备失败为PdfChatDeviceTest第117行等待首个文字页超时，独立于新导入页面；其UI归档已请求下载，继续查错误/OCR进度/展示，未提高超时或跳过用例。

实际完整下载失败unit artifact11913212689，ZIP CRC通过，四个失败XML均明确expected1.4.0/actual1.5.0，支持版本断言修复。协议设备artifact11913297671完整ZIP CRC核对，原生产JNI二进制发布/Shell摘要/重名/撤权用例通过，整轮仍因PDF超时失败，不宣称整体绿。PDF用例日志没有解析/OCR错误，也没有当时画面；尾部emulator-logcat已覆盖测试时段，无法用其无错误反推成功。下一轮在原90秒等待失败时记录合成夹具页面的进度/错误/覆盖/文字节点状态和截图，保留原断言与超时，用于区分服务未完成和LazyColumn未显示。该日志只在诊断测试失败时出现，无生产代码改动。

5c1e5c1远程生成38600208802通过；完整下载artifact11911249674，ZIP CRC核对后收回workspace/Git候选生成包，其他包不变。候选1.5.0/1.2.1均未公开，不替换任何已发布同版本文件。正式APK及全设备链继续等待。

## 图片缩略图宿主候选

独立分支保留正在运行的文本/导入候选，不取消其设备诊断。实际读取Operit ImageBitmapLimiter.decodeDownsampledBitmap的先读取尺寸再采样策略，以及AttachmentViewerDialog的MIME/20MiB边界、AttachmentInfoTest与MediaLinkParserImageOpsTest；后者只检查媒体链接，不是图像解码验收，未找到对应limiter单元测试。新实现自行编写，不复制LGPL代码/预编译库，不新增依赖。

PageFilePictureRenderer只接受当前页持有的PageBinaryDocument，原生先尺寸探测、最大100,000,000像素/每边32768，再采样至640边；读取平台EXIF方向并在预览中旋转/翻转，透明图白底缩略JPEG最多64KiB。编码超过上限降质量/尺寸，失败明确拒绝，不截断图像字节；原文件始终不改动。解码/编码前后重验授权与取消，Bitmap与输入副本正常清理；同步native调用不能承诺即时打断。PNG/JPEG/WebP/GIF图像范围，动图仅首帧；其他格式仍待支持。

新增核心副本/容量/关闭测试及三个实际Android用例：大PNG缩略后像素/尺寸与原文件一致、JPEG EXIF旋转保持原字节、坏数据/撤权无预览。当前只有可信宿主组件，尚未接到API22桥或HTML图片节点，不能宣称产品图片预览完成。完整范围仍包括PDF/原图界面、聊天附件导入、覆盖版本/进度/崩溃回收、Shell/Git完整设备链与正式APK。远程编译和这些设备用例尚待运行。

图片桥/页面候选新增importPicture只读查询：API22声明、选择id/SHA及当前对话在渲染前后检查，返回有界缩略图，不返回原图字节、URI或EXIF。WorkspaceViewModel注入可信原生renderer；JS选择图片后显式请求，在自己HTML中绘制img。新增image节点只允许≤64KiB的规范Base64内联JPEG、≤640尺寸、无action/远程URL，执行器要求API22及HTML入口。它只是表示格式约束，不能把JPEG标记检查叫完整图像解码；生产缩略图来自实际原生解码/编码。HTML已有img-src data策略无需放宽；绘制发生在工作区自己的JS，避免修改通用browser-page helper重写所有已发布插件包。图片及文本预览互相独立，确认只带原文件SHA，绝不把缩略JPEG写成原PNG。新增节点边界与TS查看图片不写入/确认保持原摘要用例，实际AndroidHTML图像显示仍须新增并验收。

新增WorkspacePictureHtmlTest真实Android链：系统选择结果注册器提供真实PNG URI，生产renderer→bridge→QuickJS→工作区HTML img；检查原生缩略图实际解码尺寸、屏幕内可见范围，再核对真实截图有红色图片像素（不只DOM存在）。预览阶段工作区仍空，原文件未改；确认后实际目录里的完整PNG字节与来源一致。真实DocumentsUI手指选择不在此用例范围。远程生成及设备结果仍待执行；不将源代码/测试存在当成显示成功。

文本/迁移候选bd58c94的Android38601350681 build成功。完整下载unit artifact11914581695并ZIP CRC/XML核对483单元零失败/错误/跳过；旧API18升级保留配置等原迁移组通过。设备115988220478仍在运行，PDF诊断和整条导入链尚无成功结论。图片页面77bd4f1远程生成38603304566成功，实际下载artifact11915201331、完整ZIP CRC后收回workspace/Git候选包，其他包未变。新增实际图片设备用例73d978f尚待Android编译/运行，不提前发布。


## 文本导入设备链验收与图片候选检查

源bd58c94的[Android38601350681](https://github.com/veryy/Solvit/actions/runs/38601350681) build/verify-ui均成功。完整下载UI artifact11914623641（5,295,956字节），ZIP CRC/XML核对65设备零失败/错误/跳过；此前unit归档为483单元零失败。实际WorkspaceImportHtmlTest通过选择、信息预览、确认取消、重名保留原文件、改名导入、生产Git untracked、生产Shell SHA/退出码与重开文件树整链。实际查看workspace-import-selected.png及workspace-import-directory.png，信息、路径输入、确认按钮与导入的300000字节文件可见。注册器提供真实URI，不宣称自动点击系统DocumentsUI。PDF原用例也通过，但没有PDF生产修复，间歇性超时的原因仍未确认，不将本轮成功称为已修复。

图片候选cd423f4的[Android38603796081](https://github.com/veryy/Solvit/actions/runs/38603796081) build成功。完整下载unit artifact11914368368（370,356字节），ZIP CRC/XML核对485单元零失败/错误/跳过。合并已发布main文档后的8eacf7d推送触发分支并发规则，取消了旧verify-ui；取消归档11915428637完整ZIP CRC通过，只有部分设备执行记录，不能支持图片全链成功。继续观察新[Android38606210921](https://github.com/veryy/Solvit/actions/runs/38606210921)，不通过再次推送打断它。图片原生解码/EXIF/撤权与实际HTML可见截图仍以该完整设备结果为准，当前不公开候选APK。


## 线上服务器私有暂存兼容验证

在现有市场服务器建立独立before/next快照，仅替换候选server.mjs、workspace1.5.0、Git1.2.1并补入原1.4.0/1.2.0完整legacy包；其他线上包与自定义catalog-order保留。通过远程Node在127.0.0.1随机端口实际调用两个HTTP服务，逐个比较API1..19目录完全一致；API20/21除了Git兼容补丁1.2.1外其他条目及顺序一致，工作区仍为1.4.0。遍历38个历史版本下载，旧/新服务字节均与部署前快照相同。API22工作区1.5.0/API22、workspace.files及Git1.2.1/API20选择和下载摘要通过。

候选工作区754032字节，SHA256 `399e6a23148c4043903788adeb2eb7203504710c3fe95b6af9a39d89b38bc72a`；Git736820字节，SHA256 `77454f4db363a764fafd507dc00a695dedb4fdc09f8f420d262d3b4d653d83f9`。这只是服务器私有暂存验证，尚未更新公开服务或客户端hostApi；必须等实际图片设备链及截图验收再公开，不用兼容检查代替图片可用性。


## 图片原生与插件HTML整链通过

源8eacf7d的[Android38606210921](https://github.com/veryy/Solvit/actions/runs/38606210921) build/verify-ui均成功。完整下载UI artifact11916382922（5,440,774字节），ZIP CRC/XML核对69设备零失败/错误/跳过。FilePictureDeviceTest三个实际解码用例（大PNG、EXIF方向、坏图/撤权）通过，WorkspacePictureHtmlTest真实PNG→原生缩略图→bridge→QuickJS→工作区HTML→屏幕红色像素→完整原PNG导入通过；原二进制Git/Shell、PDF读取与覆盖升级用例保留通过。实际查看workspace-import-image.png：红色预览图确实显示，目标路径、说明及底部确认按钮可见。测试图不是产品图稿；系统选择注册器提供真实URI，不等于手指操作DocumentsUI。

485单元结果已核对同一代码树的cd423f4归档；8eacf7d仅合并main公开版本文档。正式客户端hostApi22更新及正式标签六项门禁仍待完成，公开APK尚未更新。


## API22市场公开更新与客户端下载切换

图片69设备及实际截图验收后，先保留线上完整备份，再更新API22服务与候选workspace1.5.0/Git1.2.1，服务仍以solvit-market独立用户运行。启动后健康和新目录摘要检查通过。随后通过外部公开HTTPS逐个比较API1..19目录，完全不变；API20/21仅Git兼容补丁更新，工作区仍为1.4.0；38个历史版本下载SHA/长度与更新前基线一致。新workspace754032字节/SHA399e6a...bc72a、Git736820字节/SHA77454f...d83f9均与提交的生成包逐字节一致。当前客户端才将市场请求从hostApi21切换22，保留原MockWebServer目录请求断言并更新实际版本；最终以正式标签全门禁及公开APK核对为准。
