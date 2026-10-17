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
