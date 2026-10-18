# 插件查找与返回位置

从已交付 build102 / `087d835ba4ac67a529c23617c440f1678a129ac7` 继续，主题候选仍不合入。此阶段改善插件管理的日常查找和导航，不新增插件执行或下载协议。

## 实际参考

已运行 `python3 scripts/fetch-upstreams.py --verify-only`，九个参考 checkout 与锁定版本一致。读取 Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：

- `app/src/main/java/com/ai/assistance/operit/ui/features/packages/screens/PackageManagerScreen.kt`：各分类使用独立的 `rememberSaveable` 搜索输入/查询，按当前分类绑定搜索栏。
- 同目录 `PackageSearch.kt`：名称、ID、说明及能力元数据使用不区分大小写的包含匹配。
- `app/src/test/java/com/ai/assistance/operit/core/tools/packTool/ToolPkgManifestSelectionTest.kt` 实际验证包清单选择，不是查找 UI 测试；检索 test/androidTest 未找到上述查询函数或页面的直接回归，不能借用其测试数量作为本项目验收。

只阅读 LGPL-3.0 上游，不复制实现、不引入新依赖。Solvit 安装包较少，本地直接过滤，无需照搬面向大目录的延迟查询。插件业务界面仍由插件 HTML/JS 提供；这里是原生宿主的安装和权限管理。

## 行为与适配

源码确认此前只有网格和市场/已安装页签，没有搜索或状态筛选，而且两个分类使用同一个隐式滚动状态。本轮：

- 市场按名称、ID、说明查找；已安装按名称、ID、工具说明和页面标题查找，忽略首尾空格和大小写。
- 已安装可筛选全部、已启用、已停用；显示匹配/总数，无匹配可清除筛选，区别于没有安装任何插件。
- 两页签分别保存查询及滚动位置；修改查询/筛选回到结果开头，切换页签及关闭详情继续原位置。打开详情、切换页签及键盘搜索动作收起键盘。
- 查询与筛选可恢复 Activity 重建；不将用户模型配置或凭据加入搜索和日志。六语言标签补齐，仍用原有插件安装/升级/授权/页面流程。

实际 MainActivity 回归覆盖真实已安装插件、系统150%字号与IME、状态筛选、无结果恢复、详情返回、Activity重建和两分类的查询/位置隔离。市场目录使用固定元数据夹具，不执行下载，不能证明公网市场部署或安装服务可用。已安装侧使用真实服务和内置时钟插件，临时启用后恢复原状态。

候选版本 0.43.10/build103，远程验证尚未完成；不得以代码存在声称交付。真实模型、ARM64真机、导航栏对比及正式市场/live发布门禁继续保留。
