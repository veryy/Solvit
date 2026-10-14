# 工作区导入进度、取消和暂存回收

继续0.37.0之后的完整导入范围，不替代真实模型、真机、Git克隆恢复、完整JS内核和其他长期目标。下一版必须贯通原生任务、插件HTML、明确取消与完成状态、旧包兼容及完整远程APK验收；仅本文件或测试存在不算交付。

## 对照源码与许可证

本阶段重新核对9个参考仓库固定版本。DSH d743267的`packages/fs/fs-local/src/fsio.ts::writeFileAtomic`采用独立暂存、写入/同步后检查AbortSignal、发布后清理失败不反转已完成写入；同文件有64KiB读取取消检查。实际读取`tests/filesystem.spec.ts`中pre-aborted write不创建目标和并发版本写入拒绝过期观察的用例。采用分块检查取消、区分提交前取消与提交事实、清理不删除完成文件；Android用协程和既有RENAME_NOREPLACE，不复制MIT实现代码。

Operit dbf71916的`StandardFileSystemTools.kt::copyFileCrossEnvironment`依赖源/目标环境分派与存在/目录检查；原`ComposeDslFilePickerRequestTest.kt`验证picker种类，不是复制进度测试，没有据此声称继承进度/取消正确性。Solvit仍独立实现Android/Ubuntu共用目录，未复制LGPL源码或增加依赖。

## 原生基础（未发布）

新增仅可信宿主可挂载的WorkspaceImportMonitor协程上下文。进度是写入工作区暂存的实际字节，不伪装成系统选择器/网络下载进度。64KiB写入之间挂起检查取消，SYNCING/VERIFYING/PUBLISHING是独立阶段，PUBLISHING是最后可取消检查点；文件发布后的COMMITTED观察失败不能撤销已提交文件。替换中取消继续使用0.37.0的恢复协议，保留并发名字与原文件备份。

启动异步清理已登记工作区的保留`.solvit-import-*.partial`普通文件，进程内共享活动暂存登记保护另一存活服务实例；不跟随符号链接、不进入.git、不删除solvit-backup备份或正常文件。每个工作区最多扫描20000项、32层，超出部分留待以后扫描；这不是全局容量配额。原生基础与5个实际文件回归已写入，尚未远程运行；页面任务/进度取消交互、存储容量和正式APK继续待完成。
