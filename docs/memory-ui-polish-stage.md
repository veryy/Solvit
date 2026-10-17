# 记忆图谱长标签打磨（未交付）

110项整合回归38980183244的实际深色HTML图谱截图中，两条真实记忆的长标签重叠。当前0.43.4发布候选仍在正式门禁38983612714验证，此阶段先在独立分支复现，不把下一批改动当成本次已发布内容。

已读取锁定Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `ui/features/memory/screens/MemoryScreen.kt` 保持图谱挂载/加载覆盖，`screens/graph/GraphVisualizer.kt` 的getNodeLayoutMetrics文字实际测量、宽高与碰撞间距；未找到GraphVisualizer对应的上游单元测试。读取DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `ui-plugin-manager/tests/components.client.spec.tsx` 的状态/重载保留页面行为，以及本项目SDK memory-pages图谱真实边/256节点512关系边界测试。Operit LGPL-3.0、DSH MIT；没有复制代码或增加依赖，现有Cytoscape许可继续保留。

核对现用Cytoscape3.34.3实际[CoSE源码](https://github.com/cytoscape/cytoscape.js/blob/v3.34.3/src/extensions/layout/cose.mjs)：默认nodeDimensionsIncludeLabels=false，Solvit未覆盖此项；这是优先验证的原因，不宣称未跑验证就已解决。其次检查长词换行与缩放后边界，最后检查重用位置与尺寸变更。采用既有组件实际尺寸，避免重写整个关系图。

回归使用实际Android宿主→QuickJS→打包HTML→Canvas图谱，读取锁定引擎容器registration中的实际renderedBoundingBox（[源码依据](https://github.com/cytoscape/cytoscape.js/blob/v3.34.3/src/core/index.mjs)）；仅测试使用内部注册入口，无生产诊断。原始页面随机初始化，最多8次真实重开，首次重叠即失败，未手动挪动节点、伪造布局或调整随机种子。保留原主题、真实触摸选中、编辑/删除确认和重开持久化路径。先在未改插件的d88c731c APK（38983023229）执行，结果待定。

原APK任务38984051218实际失败于第三次初始布局（attempt2）：两组引擎实际文字/节点边界均有效，重叠宽约113.68 CSS px、高36.85 CSS px，明确为几何重叠；不是编译、WebView初始化或内部注册入口失败。只启用既有CoSE的nodeDimensionsIncludeLabels，保留随机初始化、布局迭代、字号、缩放和既有位置复用；插件升为1.4.2，未改权限和数据格式。使用既有远程plugin-pages流程重建JS/Android作图谱定向验证，SDK生成流程另行上传可审阅插件包；不在本地运行Android或TS构建/测试。诊断APK仅证明图谱，不替代完整Linux生产APK验收。结果待定，生成包尚未合入。
