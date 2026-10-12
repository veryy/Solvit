# 工作区插件页面阶段

工作区的创建、绑定、目录浏览、文本编辑和插件草稿审阅迁移到插件自己的离线 HTML/CSS/JS。原生宿主只提供文件、权限、原子写入和页面生命周期。交互式 PTY 暂时保留现有终端界面；本阶段不宣称终端界面或全部业务页面已经迁移。

本阶段实际读取固定 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `packages/workspace/workspace/src/entity.ts`、`tests/workspace.spec.ts` 和 `packages/terminal/terminal/src/index.ts`、`tests/service.spec.ts`，以及固定 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `core/tools/defaultTool/standard/StandardShellToolExecutor.kt`、`StandardTerminalCommandExecutor.kt`。采用 DSH 的精确会话归属、绑定验证、取消与资源清理边界；Operit 的真实终端会话、输出流和超时后中断原则。Operit 的 `util/TerminalManager.kt` 是外部终端 App 下载管理器，不能当作 PTY 实现。未复制这些上游文件，无新增上游运行时或许可依赖。

API12 的页面能力由原生捕获聊天 ID，文件查询不能提供其他聊天或工作区 ID。切换聊天或外部重新绑定会使旧能力失效，原生文件锁内再次检查。页面只能执行当前显示、用户确认的操作，不能给 perform 偷塞参数或重复消费确认。模型工具、Hook 和旧 API 页面不能使用这个人工能力；既有 AI 工具仍沿用单独授权。内置人工管理器只运行 APK 内不可变代码，不启用已停用的 AI 插件，不赋予下载插件人工豁免。

文本编辑先明确限制为 4KB UTF-8 文本，拒绝二进制和超限文件；大文件继续使用 Shell。保存必须带读取时 SHA-256，新文件要求此前不存在，检查与原子写入在同一个文件锁中，避免覆盖外部更新。目录每页最多 40 项，草稿源码分块查看，安装仍绑定审阅过的摘要并保持停用。

验证尚在进行。不得把源码检查、待执行用例或 HTML 中出现按钮当作构建、界面与实际任务验收。后续补远程 QuickJS/原生测试、实际模拟器点击与可见区域截图、稳定签名 APK 和兼容旧 API 的市场部署证据；角色、流式、工具选择、记忆和真实模型任务仍沿完整路线图验收。
