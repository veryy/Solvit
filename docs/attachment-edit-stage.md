# 编辑附件与遗留文件清理

本阶段承接0.31.1后的日常功能，不扩展屏幕操控。源码复核：Operit dbf71916 的ChatScreenContent.kt把完整message.content带入MessageEditor，附件引用以内容标记保留；AttachmentDelegate.kt按路径管理附件且清理草稿不等于删除源文件。实际读取AttachmentInfoTest.kt、ChatMarkupRegexAttachmentTest.kt及本仓库ChatAttachmentHistoryTest/ChatAttachmentsDeviceTest；Operit使用路径标记，Solvit则保持类型化附件+私有blob，不复制其代码（LGPL3）。

现有before-user分支只复制此前历史，VM只恢复文字，选中问题附件丢失。新实现将所选问题的附件校验读取后以新ID复制到新分支草稿，复制失败回滚新分支；原对话及之前历史的副本保留。图片/文件可预览、移除、添加，再由用户发送；纯附件问题也能创建编辑分支。

移除队列按所选对话处理，仅删除未被保存历史、其他队列、输入草稿或当前生成引用的附件。启动恢复在Storage和Attachment服务注册前执行，从数据库完整读取引用，随后只清理受管目录内无引用UUID blob和partial，不跟随链接、不删除未知目录/文件；数据库读取失败不清理。未发送草稿/队列仍不持久恢复，启动时释放其遗留文件；不冒称已实现草稿恢复或全局配额。

候选0.32.0/build65。只做静态检查，尚待GitHub Actions单元/实际Android编辑附件HTTP请求/清理/完整UI/Lint/签名/升级/APK验证；公开版仍0.31.1。

85119bd的候选38560190465 build成功，实际下载unit artifact11892049395，完整ZIP CRC/XML为452单元，零失败/错误/跳过。Android完整回归实际失败在ChatAttachmentsDeviceTest行216，已通过复制后的两个独立ID、原图字节、空分支历史、文字回填、文件预览断言，随后第二张卡片移除后等待数量1超时。移除按钮位于可横向滚动的第二张卡片右侧，原测试没滚动按钮到可见范围；下一轮先performScrollTo并检查真实按钮可见再触摸，不替换VM调用、不延长超时。失败时补充所选对话/导入状态/草稿引用及截图，继续原HTTP/原对话/完整回归门禁。是否修复仍需设备证明。

22190e5 的候选38562793717 build/UI成功。实际完整下载unit artifact11892294457和UI artifact11895215187，ZIP CRC/XML为452单元、59设备，零失败/错误/跳过；真实按钮滚动/移除及后续HTTP字节断言通过。但实际查看chat-edit-attachment-draft.png仍显示删除前两卡片，说明VM状态断言早于已显示画面，不能作为最终显示证据。截图改为等待Compose空闲及硬件帧提交，并在截图前确认已移除卡片不存在、保留图片可见，继续原完整回归；尚未创建正式标签。

28df383的38566195895首轮在talloc固定源码下载失败：curl (56) Connection died，退出56，未进入原生编译或Gradle/UI。完整任务日志已读取；不修改源码、哈希或门禁，调用同一run的rerun-failed-jobs重跑，截图同步修复仍未验收。
