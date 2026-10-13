# 工作区文件管理与代码预览

用户要求工作区包含文件管理、预览、编辑、终端及Git Changes，插件携带HTML/JS界面并共用同一目录。当前分页目录和4KB小文本编辑不满足完整范围，Git/GitHub插件也尚未实现。此阶段先完成原生分段读取契约，再接插件代码预览和编辑、文件树、新建目录/重命名/删除与Git工作台；不能把原生接口视为已经交付页面。

读取固定DSH d743267388641bc76f17c45ce8b4c231aed1d32c packages/fs/fs-local/src/index.ts的readByteRange/readText/writeText及tests/filesystem.spec.ts中invalid UTF8、同内容版本、并发stale和symlink targetKey测试：采用完整严格解码、内容版本、不混合过时读取和变更前校验。其cwd明确不是权限边界，Android继续使用宿主绑定工作区的实际containment与撤权校验。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb ui/features/chat/webview/workspace/FileManager.kt的FileBrowser/DirectoryEntry/OpenFileInfo采用目录和打开文件信息分离、加载状态与目录导航语义。MIT/LGPL仅阅读行为，未复制实现；JS/HTML业务页仍由插件提供，系统读取/写入和授权由宿主执行。

新增原生WorkspaceTextChunk契约，设计为每次最多4096 UTF-16单元、文件最多256KiB UTF-8，保留完整内容SHA-256。后续片段必须传首次读取版本；中途改写、删除、二进制、无效UTF-8、超限与撤权明确失败，不返回部分内容当作完整文件。Unicode分页不切开代理对。新回归先提交到远程复现，当前默认服务尚未实现读取，页面/安装包/API版本尚未变更，未发布。
