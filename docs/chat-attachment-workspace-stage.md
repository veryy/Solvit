# 已保存聊天附件导入工作区

目标：在工作区插件自己的HTML/JS页面选择当前对话已保存的附件，显示来源消息、名称、类型、完整大小/SHA和目标路径。图片/PDF/代码均保留完整原字节，先预览、再明确确认，文件树、Git和Shell共用同一文件。0.35.0的系统文件导入/PDF预览已发布；此阶段尚未发布，完整文件联动仍包括带版本覆盖、进度取消及异常暂存回收。

## 参考与边界

核验九个锁定upstream。实际读取Operit dbf71916的AttachmentSelector.kt::getAttachmentSource（保留content URI/file来源）、AttachmentInfo.kt和AttachmentInfoTest.kt（名称、MIME、大小与inline content分开）；WorkspaceAttachmentProcessor.kt只生成工作区上下文描述，不是附件原文件导入实现。DSH d743267的packages/api/workspace-files/src/index.ts及tests/read-bytes.spec.ts区分会话作用域、原始字节/UTF8、读取限额与版本，包含NUL/无效UTF8仍按原字节读取的断言；没有现成聊天附件上传API。仅采用来源/原字节/作用域原则，自行适配已有SQLite和AttachmentFiles，不复制LGPL源码，不新增依赖。

## 原生来源检查点

SavedChatAttachmentSource捕获当前对话，只接受来源消息ID、附件ID和预期SHA。它从持久ChatStore重新找到当前对话的用户消息和附件元信息，经现有ChatAttachmentStore读取完整原文件，再核对来源仍存在、大小与SHA。授权/取消/切换前后检查；临时读取副本在成功或失败后擦除，返回的PageBinaryDocument独立持有副本并由调用方关闭。PDF选择页范围/OCR文字不替换原文件；不将URI/路径交给JS，不删除持久原附件。

四个真实SQLite/AttachmentFiles回归覆盖重开PDF完整原字节及页范围、跨对话/错消息/旧SHA在读文件前拒绝、读后来源删除与副本擦除、读后取消/撤权保留持久文件。尚待远程编译执行，不能把源码或native helper当作用户可用功能。后续仍须接宿主页面声明和确认控件、分页选择与来源展示、系统选择/PDF/图像共用预览、真实HTML/Git/Shell设备任务、旧插件升级/市场兼容及正式APK发布。

## 宿主页面桥检查点

原生来源提供最新消息优先的20条元信息分页，不读取blob或消息正文。WorkspacePageBridge新增chatAttachments查询与经显示控件确认的selectChatAttachment，选择成功只创建临时选择并附来源消息/附件ID，不写工作区；现有文本/图像/PDF预览继续复用，确认importFile前重新验证保存来源。失败保持旧选择，删除来源后确认拒绝，切换到系统选择清掉来源标记，关闭清理临时选择且不删除原附件。

ScriptPageExecutionContext对新查询/选择强制API24和workspace.files声明，原23及更早无法调用；当前manifest最高仍23，API24生成包/客户端市场尚未开放。新增SQLite元信息分页、生产桥完整二进制导入/删除来源拒绝/系统来源替换、API门禁及一次确认只能消费一次测试。宿主服务尚未接正式应用和HTML页面，不宣称可以由用户使用。原生源63404c0的38623041850仍在构建，此检查点在独立分支保留它的运行；继续远程验证新桥，再做完整页面与发布。

## 插件页面接入检查点

API24候选workspace1.7.0添加六语言聊天附件入口、20条分页、消息来源/文件MIME和大小。选择是单独原生确认控件，不写文件；选择后沿用文本/图片/PDF分页预览和完整原文件确认，返回选择列表与取消保留旧选择路径。正式应用从服务注册表提供ChatStore/ChatAttachmentStore，当前对话由WorkspaceViewModel捕获，业务JS不获得历史正文或私有路径。

Git因共用页面源码升1.2.3/API20。已保留workspace1.6.0和Git1.2.2公开原字节，新的1.7原包恢复只匹配确切已公开1.6 SHA；用户修改、停用、卸载与权限保留规则继续。应用候选0.36.0/build70，客户端市场仍hostApi23。新增SDK流程覆盖分页/来源、取消、文本/图片/PDF复用和另行确认，新增API23旧包迁移用例。生成包仍旧版本，下一步必须远程TS生成同步、生产HTML/SQLite/Git/Shell设备测试与市场兼容后再正式发布。

UI源1432382的远程TypeScript38624452027全部通过，实际完整任务日志为119 SDK/5市场零失败及公开搜索/MCP/Skills通过。实际下载artifact11923576120并核对ZIP CRC，仅同步新workspace/Git两包；workspace1.7.0/API24为763192字节/SHA3ea4c9bf2e2f8f038b9ebf54d921ea4a0b40b5f99ff8d288928cc7b07c954e7a，Git1.2.3/API20为745980字节/SHA521a4a95fb571db7bda40afe23097b877521bf66106a8d09ac3f7e0034f36679。其他生成包字节保持。

原生源63404c0的Android38623041850 build已成功，device仍运行；桥源e71547d的38623768561实际失败于跨模块测试读取SqliteChatStore内部attachmentFiles字段，生产编译未报错。修复测试使用公开AttachmentFiles组件并指向同一chat-attachments目录，保留真实SQLite/删除/二进制断言，不扩大生产字段可见性。新增真实app HTML设备用例走已保存PDF来源→原页文字/蓝色像素→取消选择/确认→完整原PDF导入→Git untracked→Shell sha256→原聊天附件保留；未启动系统文件选择器，PDF聊天选页不影响完整原文件。修复和完整设备链待远程执行，尚未发布。
