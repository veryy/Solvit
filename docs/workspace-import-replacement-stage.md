# 导入前审阅现有文件

当前阶段在 0.36.2 已保存附件导入的基础上继续完成二进制文件替换。屏幕操控仍暂缓，完整路线见 product-roadmap.md 与 agent-direction.md；本阶段不替代真实模型、ARM64 真机、stdio MCP 等待验收项。

## 对照源码和许可

- DSH d743267388641bc76f17c45ce8b4c231aed1d32c：`packages/fs/fs-local/src/index.ts` 的 `writeText`、`fsio.ts` 的原子发布；`tests/filesystem.spec.ts` 的 replaceIfVersion、已删除目标与并发写入用例。采用先观察、显式确认、提交时核对已观察版本及并发冲突不盲目重写的约束。DSH 为 MIT；本次独立实现 Android 二进制适配，没有复制代码。
- Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb：`app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardFileSystemTools.kt` 的 `copyFile` 与跨 Android/Linux/SAF 环境分派。采用文件管理、终端使用相同文件的路径约束；不复制其 LGPL Kotlin 实现。不把普通复制的 overwrite 选项当作版本确认。

## 原生提交协议

源 SHA256 与目标审阅 SHA256 分开。只允许不超过20MiB的普通目标文件；目录、符号链接、内部目录与越界路径拒绝。旧 create-only 导入接口保持原语义。

替换时先核对目标摘要，再用现有 RENAME_NOREPLACE 将旧文件移到同目录 `solvit-backup-UUID`，对实际移走的内容再次核对，然后以 create-only 发布完整新文件。成功也保留原文件，用户可在文件管理中检查后删除。这样已经打开旧文件的 Shell 描述符仍写入保留的旧 inode，不会把新文件静默改掉。

发生取消、授权失效或提交失败时，在不可取消的清理中尝试不覆盖地恢复旧名字。如果 Shell 抢先创建了新同名文件，保留该文件和原文件备份，错误明确给出恢复路径，不删除任一方。此协议不锁定外部进程，也不宣称存在文件系统 compare-and-swap；旧名移走至新名发布之间存在短暂空窗。备份不是自动回收的临时文件，不在后台偷偷删除。

## 当前验证状态

原生接口与6个有实际文件读写/并发注入的回归已写入，尚未远程运行。页面审阅、原生 token 绑定、权限/API 门禁、HTML 设备任务、插件兼容发布与新版 APK 仍待完成。不能将这些源代码当作已发布功能。
