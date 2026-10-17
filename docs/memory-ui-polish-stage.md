# 记忆图谱长标签打磨（未交付）

110项整合回归38980183244的实际深色HTML图谱截图中，两条真实记忆的长标签重叠。0.43.4候选因授权信息可见性未通过而取消正式发布，标签保留，此阶段先在独立分支复现，不把下一批改动当成本次已发布内容。

已读取锁定Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `ui/features/memory/screens/MemoryScreen.kt` 保持图谱挂载/加载覆盖，`screens/graph/GraphVisualizer.kt` 的getNodeLayoutMetrics文字实际测量、宽高与碰撞间距；未找到GraphVisualizer对应的上游单元测试。读取DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `ui-plugin-manager/tests/components.client.spec.tsx` 的状态/重载保留页面行为，以及本项目SDK memory-pages图谱真实边/256节点512关系边界测试。Operit LGPL-3.0、DSH MIT；没有复制代码或增加依赖，现有Cytoscape许可继续保留。

核对现用Cytoscape3.34.3实际[CoSE源码](https://github.com/cytoscape/cytoscape.js/blob/v3.34.3/src/extensions/layout/cose.mjs)：默认nodeDimensionsIncludeLabels=false，Solvit未覆盖此项；这是优先验证的原因，不宣称未跑验证就已解决。其次检查长词换行与缩放后边界，最后检查重用位置与尺寸变更。采用既有组件实际尺寸，避免重写整个关系图。

回归使用实际Android宿主→QuickJS→打包HTML→Canvas图谱，读取锁定引擎容器registration中的实际renderedBoundingBox（[源码依据](https://github.com/cytoscape/cytoscape.js/blob/v3.34.3/src/core/index.mjs)）；仅测试使用内部注册入口，无生产诊断。原始页面随机初始化，最多8次真实重开，首次重叠即失败，未手动挪动节点、伪造布局或调整随机种子。保留原主题、真实触摸选中、编辑/删除确认和重开持久化路径。先在未改插件的d88c731c APK（38983023229）执行，结果待定。

原APK任务38984051218实际失败于第三次初始布局（attempt2）：两组引擎实际文字/节点边界均有效，重叠宽约113.68 CSS px、高36.85 CSS px，明确为几何重叠；不是编译、WebView初始化或内部注册入口失败。只启用既有CoSE的nodeDimensionsIncludeLabels，保留随机初始化、布局迭代、字号、缩放和既有位置复用；插件升为1.4.2，未改权限和数据格式。使用既有远程plugin-pages流程重建JS/Android作图谱定向验证，SDK生成流程另行上传可审阅插件包；不在本地运行Android或TS构建/测试。诊断APK仅证明图谱，不替代完整Linux生产APK验收。结果待定，生成包尚未合入。

候选38984643507实际在第一次布局失败：启用CoSE标签尺寸后仍重叠约94.27×16.25 CSS px，未合入主线。锁定CoSE源码299–311使用layoutDimensions但宽高对应颠倒，且底部标签偏移不等于节点中心；不据此单独断言唯一根因。下一单变量换用现有Cytoscape的concentric布局（无新依赖），保留真实标签尺寸与图谱交互，按现有组件默认的节点关系度数分圈，避免随机初始方向导致文字碰撞。读取[锁定实际实现](https://github.com/cytoscape/cytoscape.js/blob/v3.34.3/src/extensions/layout/concentric.mjs)的layoutDimensions、maxNodeSize和avoidOverlap间距计算；该库未找到独立collection-layout.js测试文件（404），使用本项目真实页面回归验证。保持8次真实重开和编辑/删除路径，不手动伪造位置或改断言。市场测试锁定最新记忆版本同步为1.4.2，旧API版本断言保留；目录摘要仍由真实包字节计算。

远程SDK生成38985352820已通过typecheck、141 SDK测试、市场完整检查及线上搜索/MCP；生成artifact12082712563 SHA256 6ae164b46a9ea3c3cff6d8b48cc9741417266399cf0da379bb39e8b06a4bdc2a、ZIP CRC已核对。只导入memory1.4.2的同字节市场包/Android资源，其他14包与已提交字节一致；旧1.4.1原包移动到legacy保留下载，新增历史字节断言。提交后还须正常模式验证生成资源与源码一致，真实图谱设备任务仍未完成，不能计作交付。

38985378584原始远程runner明确OK (1 test)，8次实际页面重建及原触摸/编辑/重开/删除确认路径全部执行。原失败断言保留，未手动移动节点，原APK38984051218/第一候选38984643507失败记录保留。完整截图归档正在下载核对，整合进0.43.5候选的最终生产APK仍须完整远程门禁，未发布。

38985378584完整截图归档12082693679已下载，SHA256 70c89bd2f385a13b13aec9feaadbe30a0b2b1d7db3440ade92de6cc7295c85bc与GitHub元数据一致、ZIP CRC通过、原始result为OK (1 test)，8张layout-a..h实际截图齐全。已查看最终深色memory-html-graph-home：两条长标签及实际关系箭头分离，搜索/图谱/底部操作完整；只证明本次真实双节点场景，不能据此宣称稠密图谱或所有标签组合均已验收。合并候选84a0b71d的正式生产APK与完整门禁仍待结果。

整合38985977662构建实际失败于三项市场检查（旧测试期望1.4.1，目录URL亦锁定旧版本），因此未生成可用候选APK。审阅restore发现1.4.1发布原包SHA256 24620ba62dee1b98ce03b49fbb94524868947dc5b4a7f7f5135e5977d21fd7d9不在原包安全升级名单；这属于源码审计发现，尚未用新回归执行复现。先加入原1.4.1真实包资源及保留事实/授权/启用状态、修改包不覆盖/卸载不恢复的回归，更新旧版本预期，保持生产restore不变，使用独立远程unit诊断执行。聊天大字可用现有插件页面诊断流程独立检查，诊断包不含完整Linux可执行程序，不替代最终生产APK。

38986546901实际执行13项定向市场测试，仅releasedHtmlMemoryUpgradesWithoutLosingFactsOrExistingGrants失败；旧版本预期修正后其余12项通过，包括修改包/卸载保留边界。现在仅把已核对的官方1.4.1原包摘要加入既有升级名单，不改变修改包保护、权限或数据格式。相同断言远程重跑待定；不将源码审计或编译成功视为升级通过。

另用公开API31目录实际核对，线上memory仍为1.4.1，仓库新包为1.4.2。当前UI把任何不同版本标作更新，原生安装亦未阻止旧目录降级；这会使本轮升级被用户误操作撤销，须一起处理。先用真实旧包、实际市场服务与本地启用/权限/记忆建立回归：旧目录必须在下载前拒绝，并完整保留当前包/状态。此阶段仍是测试提交，生产版本比较尚未修改。

38986787166的完整13项升级回归已通过；artifact12083865836 SHA256 e342a558164186442111d8397297ebe1336e2c628eef25c831189827a61a2462、CRC及两个原始XML核对为13项零失败/错误/跳过。38986857381追加旧目录回归后14项仅olderMarketCatalogCannotDowngradeBundledMemoryOrResetItsState失败，证明安装入口允许降级。现把同一三段数字版本比较用于市场UI与mutex内安装前校验；已有较新版本时不提供“更新”，在下载/关闭页面/清除权限前拒绝旧包。同版本显式API重装保留旧契约，本地用户选包路径保留；没有改网络API或提高插件API版本。新增数字顺序/大整数/无效格式检查，不按字符串大小排序。

读取锁定DSH host/plugin-inventory/src/index.ts和tests/inventory.spec.ts，确认读取现有加载状态/元数据、停用不执行插件和不维护第二份状态；本项目以现有packages作为安装判断源，在原有mutex内检查。DSH MIT；没有复制代码或新依赖。这一补充处理本轮新包与旧线上目录的实际组合，不宣称市场已部署。完整生产APK仍待远程构建与验收。

防降级原断言38986857381的归档12083841200 SHA256 2242539e24e46852b031ecdca33eaf64a3df6e596c3019148776ac751982b89f、CRC/XML已核对：14项仅旧目录断言失败，实际错误是预期拒绝却安装成功。修复后的同断言38987193511归档12082748935 SHA256 41b0e78bdef3a5673715accef4d4731be4d46ea9a308b4cdfab328387fd25853、CRC/XML核对14项全通过，零失败/错误/跳过；包含下载前拒绝、包字节/启用/权限/事实保留。整合生产构建38987190372仍在执行，这些定向单元结果不替代APK或设备验收。
