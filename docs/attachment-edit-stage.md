# 编辑附件与遗留文件清理

本阶段承接0.31.1后的日常功能，不扩展屏幕操控。源码复核：Operit dbf71916 的ChatScreenContent.kt把完整message.content带入MessageEditor，附件引用以内容标记保留；AttachmentDelegate.kt按路径管理附件且清理草稿不等于删除源文件。实际读取AttachmentInfoTest.kt、ChatMarkupRegexAttachmentTest.kt及本仓库ChatAttachmentHistoryTest/ChatAttachmentsDeviceTest；Operit使用路径标记，Solvit则保持类型化附件+私有blob，不复制其代码（LGPL3）。

现有before-user分支只复制此前历史，VM只恢复文字，选中问题附件丢失。新实现将所选问题的附件校验读取后以新ID复制到新分支草稿，复制失败回滚新分支；原对话及之前历史的副本保留。图片/文件可预览、移除、添加，再由用户发送；纯附件问题也能创建编辑分支。

移除队列按所选对话处理，仅删除未被保存历史、其他队列、输入草稿或当前生成引用的附件。启动恢复在Storage和Attachment服务注册前执行，从数据库完整读取引用，随后只清理受管目录内无引用UUID blob和partial，不跟随链接、不删除未知目录/文件；数据库读取失败不清理。未发送草稿/队列仍不持久恢复，启动时释放其遗留文件；不冒称已实现草稿恢复或全局配额。

候选0.32.0/build65。只做静态检查，尚待GitHub Actions单元/实际Android编辑附件HTTP请求/清理/完整UI/Lint/签名/升级/APK验证；公开版仍0.31.1。
