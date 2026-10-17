# 附件预览：紧凑标题与完整文件信息

2026-10-18承接build99交付。上一轮已将会话草稿/编辑状态和终端标签合入main，完整远程与可下载APK均已核对；本轮继续打磨附件预览，不将整体UI/Agent目标缩为此单页。

## 实际观察与设计

build99的真实PDF选页截图中，长文件名占两行，将关闭文字按钮和内容顶端拉开。图片、文本和PDF共用的旧页面也使用相同两行标题。这是页面层级和空间使用的改进，并非宣称发现数据或读取错误；不为了视觉调整制造无关红测。

采用统一的原生TopAppBar：左侧关闭，中间单行文件名，右侧文件信息。标题只在视觉上省略，完整文件名、MIME类型和系统格式化大小在可滚动、可选择的文件信息中查看。系统返回先关闭文件信息，预览正文保持打开；再关闭预览才回到聊天。图片、文本和PDF继续使用原读取服务、附件所有权和选择，未改变发送、重试、持久化、权限或文件字节。

复用FullScreenPage与Material 3的Scaffold/TopAppBar/AlertDialog。状态栏由TopAppBar处理，导航栏/IME仍由FullScreenPage处理，正文消费对应padding，避免重复系统边距。新标签补齐中文、英文、日文、韩文、西班牙文和法文。

## 已实际读取的上游与许可

九个锁定checkout已verify-only核对。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb：

- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/attachments/AttachmentViewerDialog.kt` 186–237：文件图标、单行ellipsis标题、独立IconButton关闭以及正文分隔；借鉴紧凑导航，不复制其有界媒体对话框、外部应用打开或读取实现。
- `ui/features/chat/components/AttachmentPreview.kt` 99–119：附件卡片显示名限制一行，大小为次要信息。
- `app/src/test/java/com/ai/assistance/operit/data/model/AttachmentInfoTest.kt`：文件名/MIME/大小及copy保留，不覆盖本次Android布局或返回交互。
- `app/src/test/java/com/ai/assistance/operit/ui/features/chat/components/PastedTextAttachmentTest.kt`：粘贴/选区行为，不是预览布局测试。未找到可直接复用的预览导航设备测试，不将这些单元当作上游界面验证。

已读取其LGPL-3.0 LICENSE；未复制上游实现或引入新依赖。本次为既有Solvit Compose组件的Android适配，插件HTML页面路线和完整agent-direction.md继续保留。

## 验证要求与当前状态

新增实际共享预览外壳的设备回归：150%字体、长中文文件名、浅色/深色，检查单行省略、标题/关闭/信息按钮不相互遮挡、正文位于工具栏下、完整文件名不省略、类型和大小可滚动查看，以及真实系统Back先关闭信息再由关闭按钮退出。保存四张实际系统截图。此用例只证明共享导航布局，附件字节/模型内容仍由既有真实picker、SQLite、HTTP、PDF/OCR/选页/重试/分支用例覆盖。

App必需设备数87增至88，新方法加入独立报告门禁；保留其他角色、流式、搜索、插件、工作区和终端回归及15场恢复。版本code100区分已交付99，versionName仍0.43.10，固定签名和迁移保留。等待GitHub Actions完整测试/Lint/APK和设备、实际截图及产物核对；当前未声称编译或新用例通过。无本地Android/TS构建或测试，无临时DEBUG探针。

真实模型/真机、ARM64及第三方/账号任务、正式公开市场部署与严格live门禁仍未完成。图谱密集总览、工作区和插件操作层级是后续打磨，不计入本轮完成。

## 首轮远程与断言修正

5403cb0a的Android39074885859构建通过628单元、Lint、version100和固定签名，APK59665075字节、SHA256 ff32b837baa3160c6e50add6d4e865b99898aacab5023ed628a0c5699c5cd7b1。定向39075273987在4.237秒处失败：实际大小文本为“128 B”，assertTextContains("128")却使用默认substring=false，按整段文本匹配。已读取官方ui-test1.8.3 Assertions.kt确认API默认值；仅改为substring=true，不改变生产UI、文件大小或其他断言。原始归档12117076057大小/SHA/CRC已核对，浅色150%标题和信息截图实际查看；此前单行、几何与完整文件名检查通过，尚未走到系统返回及深色，不能记作整套通过。

首个完整流程由修正后的同分支完整流程替代，历史失败保留。修正后可先复用同生产源码的已构建APK运行定向，随后核对最终APK字节与完整新源码结果；不靠重新提交生产代码去掩盖测试API误用。

## 完整流程中的MCP测试同步缺口

4a72bd5f完整Android39075710944的构建通过；普通设备App88/Web3/terminal26全部产生报告，其中只有McpPageUiTest失败。归档12117985289的大小、SHA256、CRC与原始XML已核对。失败在137行取消删除后再次删除：立刻查找plugin_page_confirm时节点不存在；第一次删除与保存都有显式等待，这一处缺失。最终150%聊天/stdio强杀因普通套件失败未运行，不记整套成功。

按顺序检查：①测试在异步桥消息/Compose生成弹窗前点击；②第二次真实触摸未送达；③视图刷新撤销确认。实际HtmlPageProbe.tapPoint只注入DOWN/UP，不等待JS→host→Compose；PluginPageContent.deliver异步建立确认。仅补齐与第一次删除相同的有界等待，保留真实触摸、取消后两条记录、确认后只剩docs及密钥检查；不加sleep、不绕过确认、不改变生产插件或权限。若事件未送达或确认被撤销，新等待和原数据断言仍会失败，不能仅凭后续通过宣称其他可能已根治。

本次继续读取DSH锁定ui-settings-shell/tests/apply.client.spec.ts的vi.waitFor服务/页面注册断言、Solvit确认与实际WebView触摸实现；上游测试并非Android确认测试，不复制其实现。新增mcp-page同生产APK定向入口，等待远程检查及完整新源码门禁，保留本次失败。

## build100实际交付结果

采用标准64dp TopAppBar，普通字号不保证比旧48dp行更矮；本轮验收单行标题、导航对齐和完整信息，不声称节省固定正文高度。

最终源码2afc3da5的[完整Android](https://github.com/veryy/Solvit/actions/runs/39077647058)以none模式通过628单元、Lint、App88/Web3/terminal26、15独立恢复、覆盖升级、150%聊天及stdio实际宿主强杀。独立报告/原始XML零失败、跳过和缺失，runner/PID及最终APK版本100、固定签名、14内置插件、六语目录和归档摘要/CRC均核对。[定向39075712947](https://github.com/veryy/Solvit/actions/runs/39075712947)两轮实际5.101/4.572秒成功；其APK来自首轮5403cb0a构建，最终APK SHA256相同，两次源码间仅测试、诊断脚本/工作流及文档有差异，生产实现相同，已核对。MCP定向39077648784两轮通过，实际耗时[19.435, 18.443]秒，APK与最终包逐字节一致；保留原39075710944缺少第二次确认等待的失败，实际生产实现未变。实际长标题/信息浅深色150%、真实PDF和已保存文本截图已查看，当前/历史对比图已收入版本说明。候选APK、Linux源码和验收JSON独立保存，build99文件和历史失败不改。正式市场/真实模型任务/真机等边界仍保留；整体UI打磨和完整Agent路线继续。
