# 工作区插件页面阶段

工作区的创建、绑定、目录浏览、文本编辑和插件草稿审阅迁移到插件自己的离线 HTML/CSS/JS。原生宿主提供文件、权限、原子写入和页面生命周期；未绑定时的原生创建入口和Shell兼容入口仍保留。交互式 PTY 暂时保留现有终端界面；本阶段不宣称终端界面或全部业务页面已经迁移。

本阶段实际读取固定 DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `packages/workspace/workspace/src/entity.ts`、`tests/workspace.spec.ts` 和 `packages/terminal/terminal/src/index.ts`、`tests/service.spec.ts`，以及固定 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `core/tools/defaultTool/standard/StandardShellToolExecutor.kt`、`StandardTerminalCommandExecutor.kt`。页面采用 DSH 的精确会话归属、绑定验证、取消与资源清理边界；Operit 的真实终端会话、输出流和超时后中断原则。Operit 的 `util/TerminalManager.kt` 是外部终端 App 下载管理器，不能当作 PTY 实现。未复制这些上游文件，无新增上游运行时或许可依赖。

API12 的页面能力由原生捕获聊天 ID，文件查询不能提供其他聊天或工作区 ID。切换聊天或外部重新绑定会使旧能力失效，原生文件锁内再次检查。页面只能执行当前显示、用户确认的操作，不能给 perform 偷塞参数或重复消费确认。模型工具、Hook 和旧 API 页面不能使用这个人工能力；既有 AI 工具仍沿用单独授权。内置人工管理器只运行 APK 内不可变代码，不启用已停用的 AI 插件，不赋予下载插件人工豁免。

文本编辑先明确限制为 4KB UTF-8 文本，拒绝二进制和超限文件；大文件继续使用 Shell。保存必须带读取时 SHA-256，新文件要求此前不存在，检查与原子写入在同一个文件锁中，检测已发生的外部更新；该锁串行化原生API调用，独立Shell进程仍可能在检查后并发写文件，不能称为操作系统级CAS。目录每页最多 40 项，草稿源码分块查看，安装仍绑定审阅过的摘要并保持停用。

## 远程验证记录

源码与生成包均已提交，未在本地运行Android或TypeScript构建/测试。最终代码 `60abb44` 的[完整Android运行38325315067](https://github.com/veryy/Solvit/actions/runs/38325315067)成功；实际XML核对302个单元、46个模拟器测试，无失败、错误或跳过。Lint、debug APK、固定签名及旧版覆盖升级后的加密配置/聊天保留通过；旧APK种子检查另有1个用例成功。源码 `d3a3db6` 的运行38324245952此前已通过构建/单元，但推送API文档触发同分支取消规则，中止未结束的模拟器检查；没有将该取消运行视为完整成功。[模型任务选择聚焦运行38324246140](https://github.com/veryy/Solvit/actions/runs/38324246140)实际1个用例18.24秒成功；最终全量中同一用例6.469秒通过。

正常[SDK运行38321717691](https://github.com/veryy/Solvit/actions/runs/38321717691)通过82个SDK、2个市场测试及源码/提交包一致性；包由远程运行38320784029生成后提交。先前的SDK仍按旧API4断言、App缺失导入和括号、有限Shell隐式PTY依赖及市场请求API旧断言均按实际失败日志修正。原生完整运行38322574006的单元、Lint、签名已通过，模拟器的模型任务选择失败；保持保存、任务路由持久化及重开断言，测试改为等待编辑页真实结束并滚动到懒加载选择条目。聚焦成功不能代替最终全量验证。

[工作区HTML聚焦运行38321081243](https://github.com/veryy/Solvit/actions/runs/38321081243)实际点击成功。后续完整用例使用约3550字节文本，检查预览/取消不写文件、原生确认按钮可见、实际文件写入、外部修改后的旧摘要拒绝、关闭重开后数据保留，且人工管理不启用AI插件。最终全量中的 `WorkspaceHtmlPageTest` 用例8.586秒通过。实际[目录截图](screenshots/0.22.2/workspace-html-directory.png)与[编辑截图](screenshots/0.22.2/workspace-html-editor.png)取自最终产物11780673525，已核对底部操作区可见。

## 市场与兼容

在线市场API12已部署并保留API11备份；API1–12目录和18种被选择包的实际下载长度及SHA-256均核对。工作区1.1.0/API12包为27700字节，SHA-256 `4afcd1206d15bb0f1fba79a93f0e623b0a6e29ff3172b54cadb833b9ea8471e4`。旧API4–11继续获取1.0.0，其原摘要 `7d80dbfc71c177b3a2a81df5c15bfa05b6d0cb274baf536a5d179dad55ce0004` 保留。API12显式声明 `nativeServices`，有限Shell不依赖PTY，普通HTTP不依赖浏览器；API5–11旧行为继续兼容。

本阶段不宣称真实模型角色回答、可见流式、工具选择、记忆利用或自主任务完成已通过。执行证据使用确定性Provider；真实模型、真机与完整PTY插件界面继续按[完整路线图](product-roadmap.md)验收。公网搜索质量门禁仍未通过，正式Release未创建。

下一阶段仍需审查PTY的跨对话归属、回合结束与持久Shell的生命周期，并采用成熟终端组件提供插件自己的交互界面。API12页面能力不等于AI执行服务已完整迁入JS。目录加载失败后的路径/条目一致性和大草稿分块的字符边界也需后续独立修正；已发布的1.1.0包保持不可变，新增修改必须升级版本并保留旧包。

[候选APK产物11780996811](https://github.com/veryy/Solvit/actions/runs/38325315067/artifacts/11780996811)为23,378,892字节，SHA-256 `350c607675ca79df0b5a8c1749b3ff579ed6c3cf42e7baa40185bf9ca492aa23`；已下载核对ZIP完整性、工作区包原字节与固定签名构建日志。该产物是0.22.2/build37候选，正式Release仍为0.21.3。
