# 插件 HTML/JS 业务页面与交付验收

用户在 2026-10-13 明确纠正方向：插件应能用 JS/TS、HTML 自己绘制业务界面，记忆以关系图为主，MCP 要管理多个服务器，聊天内核必须按实际效果验收。API9 的 TS 控件树加 Compose 渲染不能当作这一目标已经完成。原有全部产品路线继续保留。

当前候选已实现API10离线HTML/JS通用容器、Cytoscape记忆图与多MCP管理页。284项单元回归、Lint、APK和固定签名通过；完整模拟器44项中仅记忆图点击失败。单用例实际记录确认节点点击被桥接接受，但UI回调携带旧页面对象，导致当前版本校验丢弃。2f30ba2让事件携带桥接验证的页面快照，并将确认动作绑定同一快照；原失败用例正在远程复验，尚未发布0.22.0。

## 已核对的实际源码

- Operit 锁定 dbf71916 的 `MemoryScreen.kt`、`graph/GraphVisualizer.kt`：图谱占首页主要区域并持续挂载，搜索、目录、节点/边选择及详情按需显示；实际图谱是 Compose Canvas，不是 HTML。采用交互方式，不能宣称已经搬来同等图谱能力。
- Operit `ToolPkgComposeDslWebView.kt`、`JsComposeDslBridge.kt`：有独立 WebView 控制器、资源与事件桥接和关闭清理。读到的相关测试是输入队列与回显保护；没有找到对应图谱/HTML桥的直接回归，不把这些当作 Solvit 新 WebView 的验收。
- Operit `McpConfigImportParser.kt` 及实际 `McpConfigImportParserTest.kt`：多个具名服务器、明确区分 stdio 与 HTTP/SSE、逐连接配置和禁用状态，拒绝模糊传输声明。Solvit 当前 MCP 只有一个 module-level session 和一个地址，不能称为多MCP管理。
- DSH `ui-settings-shell/tests/apply.client.spec.ts`：插件业务页注册、主题语言与 dispose 时撤销；`agent-loop/tests/tool-updates.spec.ts`：请求中的真实工具目录与变化记录。Solvit已有每步上下文/工具刷新，但当前MCP只暴露桥接入口，真实模型角色、工具选择与任务完成质量尚未验收，不能重新宣称现有机制全都没有，也不能用测试计数代替效果。
- [Cytoscape.js 官方文档](https://js.cytoscape.org/)确认纯JS图谱、MIT许可、节点/边事件及拖动缩放。已采用固定3.34.3组件离线打入插件包并保留许可；实际交互验收见上方当前状态。
- [AndroidX WebView桥接契约](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)：消息桥限制页面来源、主frame与生命周期。将继续核对实现和真实Android回归。

## 当前实施范围

API10插件包提供HTML/CSS/JS静态资源和页面入口；Android保留通用WebView容器、权限、凭据、文件/Shell桥和资源清理，业务布局由插件自行绘制。资源与插件源码一起做完整包哈希，限制大小和相对路径；不从CDN加载图谱库。API9旧控件树继续兼容。

HTML页面复用现有QuickJS业务回调、实际页面版本、字段/动作校验及宿主确认。DOM事件只能调用当前页声明的动作，存储和其他能力仍经过所属插件服务；关闭、卸载、权限撤销后不能继续调用。主题/语言作为页面环境输入；主题变化和数据刷新不能不断重建关系图而丢失视角。

记忆新首页以真实节点/边关系图为主体，点击节点打开详情和编辑，搜索/过滤与次要任务收进按需面板；不在首页堆一排排操作按钮。保留事实来源、人工编辑保护、删除与已有数据。图谱首屏、缩放/拖动、编辑/关闭重开以及未启用时的人工作业必须在真实APK中验证。

随后多MCP采用具名连接、独立会话/启停/错误、加密凭据、工具所属服务器标识和标准配置导入；AI请求和结果需要证明正确路由到至少两个服务器，不能只添加多个地址框。stdio所需执行环境必须单独验证，不能把HTTP接入称为完整stdio支持。

聊天内核继续以角色应用/新对话、真实可见流式、工具实际执行/回传和失败修复任务为验收依据。真实模型与真机当前未验收；后续工作区规则、完整diff、手机观察/动作及长期路线不缩减。

首个检查点仅添加离线资源包契约回归：合法入口、整体哈希覆盖JS、禁止路径穿越/远程入口、大小及旧API门禁。下一步用GitHub Actions观察当前缺失的支持，再实现资源、页面桥和实际图谱。当前尚无HTML页面或新APK，不能称为交付。

首个远程回归[38274724104](https://github.com/veryy/Solvit/actions/runs/38274724104)编译成功，在合法离线HTML/JS包解析处失败（plugin-runtime 36个测试中1项失败），确认现有宿主不支持该契约。随后候选增加API10资源包与HTML入口校验，保留API1–9的现有包；整体包哈希覆盖HTML/JS、资源限制32文件/1MiB、拒绝绝对/远程/穿越路径。回归额外核对解析后入口和JS原文仍存在。仅实现包契约，WebView桥、实际图谱、多MCP和新APK尚未实现/验收；下一运行继续验证，不能把这一步当作HTML插件完成。

第二个候选检查点把资源服务接入页面会话，只有已打开且未关闭/撤权的页面可读取本插件资源；旧页面默认不提供HTML文档。HTML事件使用当前页面token和声明节点，拒绝调用方注入action/pluginId/confirmed，复用字段与图节点参数校验；删除请求仍需实际宿主确认，不能由网页传true绕过。已添加对应契约回归，尚未远程执行，也尚未接入实际WebView。Cytoscape官方npm元数据确认3.34.3/MIT，将固定版本并离线打包；未引入该依赖。

通用WebView候选接入AndroidX WebKit 1.12.1的精确来源/主frame消息监听。HTML、CSS、JS由本插件离线资源提供，网络/文件/内容访问关闭，页面销毁和渲染进程退出均撤销桥并清理WebView。主题、语言、busy与当前页面状态更新不重载文档。DOM事件必须携带当前token，并通过已声明节点/字段验证；危险动作仍由宿主实际确认。新增真实Android回归覆盖HTML脚本加载、通过QuickJS写入Marketplace持久存储、关闭重开、取消/确认删除及停用关闭；这是待远程验证的候选，尚无新HTML APK。

包解析候选运行38275479326的build成功，但完整UI在已有交互终端第二次输入（ChatLayoutTest.kt:874）超时，不能记为全通过。继续读取报告定位；新HTML容器尚不属于这次运行源码，不能把它归为HTML验收结果。

已核对Cytoscape.js v3.34.3实际commit716a1cb6的`src/extensions/layout/cose.mjs`、`src/core/viewport.mjs`和`test/events.mjs`（选择/位置事件与destroy清理）。固定依赖、离线browser bundle、保留JS版权注释和插件内`ui/LICENSE.txt`。采用库本身图布局、拖动缩放和节点/边事件；未复制Operit LGPL源码。新记忆候选1.4.0/API10以图为首页，搜索和更多菜单、节点详情/编辑作为HTML面板，后台任务也在HTML里绘制。图实例保持挂载，状态/主题变化保留视角；实际数据及来源/CAS修改仍沿用已有TS事务。API9 1.3.1原字节保留legacy，当前候选尚未生成/远程验证，不算交付。

远程TypeScript生成运行38279589604（0cc8808）成功：SDK typecheck、69项SDK回归、2项市场回归和公开搜索验证通过。取回实际solvit-builtin-plugins产物，1.4.0包538841字节、SHA256 ad6fea902fc1549340159b3c4cfdb655b4b01eaf4ec694e2fb736150bf09e3c6，HTML/CSS/JS/许可均在包内；市场与APK资源使用同一字节。生成工作流明确允许生成尚未提交的包，后续正式发布仍须执行提交包一致性门禁。

桥接候选38278158546已编译到app，但新撤权/替换测试在重新打开fixture时失败：fixture直接撤销grants后，enabled仍为true，enable是幂等操作而非重新授权。修正测试在下一检查前先明确disable，再enable；前半段真实撤权后的资源/文档拒绝断言保留，没有修改权限机制绕过错误。待下一完整运行验证。已有终端超时的异常类型未被原Exception诊断捕获，临时加Throwable捕获保留真实失败和状态/截图，并核对发送前输入字段；不是放宽超时或跳过测试。

完整Android候选38279936308暴露实际市场目录缺陷：catalog过去把清单包装成无资源假包来复用解析，API10有HTML入口后严格入口校验使目录失败。改为独立`parseManifest`校验清单，真实下载仍使用完整`parse(bytes,expectedSha256)`并强制入口资源存在。新增回归证明目录可保留HTML声明，而缺资源的可执行包仍拒绝；实际MockWebServer目录→下载→安装回归升级为API10，继续证明不自动启用或授予权限。

94101cf运行38280669363的build通过。首轮HTML UI失败来自探针只遍历Activity decor，实际PluginPageScreen在独立Dialog窗口；修正探针遍历WindowInspector实际窗口，复用同一个异步求值驱动，异常通过Deferred传回测试线程。原终端诊断新增assertTextContains默认整串匹配，自行造成误报（报告EditableText中实际包含UI_PTY_STATE），改为明确substring匹配。保留真实工具输出等待，不把这两处测试修正当作产品交互通过；下一次继续完整UI验收。

cd09572运行38282268111：build成功；HTML notes真实保存/重开/取消与确认删除/停用，以及HTML后台任务操作已通过。记忆关系图实际触摸未进入详情，普通Shell输出UI另有超时，因此整轮仍失败。截图首屏显示尚未填入主题/数据的白页，需要区分探针选中残留窗口、首屏准备时机和触摸来源/坐标。探针下一候选限定当前Activity且有焦点的真实窗口，明确SOURCE_TOUCHSCREEN、使用实际事件时间；保留实体触摸与详情等待，失败时仅记录颜色/尺寸/命中元素等无正文信息。Shell失败时记录此固定fixture命令的作业状态及UI；临时DEBUG探针在定位后移除，不把部分通过当作图谱完成。

多MCP候选1.1.0/API10已实现具名独立会话、HTML配置及加密CAS保存。已读取DSH实际`connection.ts`、`server-context.ts`和对应重连/上下文测试；仅采用每连接会话边界，不声称实现DSH完整重连与动态目录。远程生成38285153870通过，新包36488字节、SHA256 79dfb626d1216ecef5813dd82c332dc9c5b4e3fa5868c7974294e34ea8faeef0。API9保留原1.0.0包。Android多HTTPS路由和HTML管理验收仍待运行，服务端尚未部署新包。

1461ac2完整UI运行38284159748揭示实际图谱容器高度0、节点renderedPosition为(10,10)，截图只有搜索栏没有图。修正HTML页面容器显式绑定viewport并明确图区域宽高，增加真实高度断言；同时窗口探针只选当前Activity拥有且显示的WebView，原生确认临时夺焦时允许读取底层页。修正后的远程生成38285810859通过，记忆候选包538936字节、SHA256 082fa4ad75e54f1f94151db72b78fcbefa7d22e443a74c6b42a8d70b9ece37dc。完整Android验收仍待通过。

单用例运行38291008276记录图谱tapstart/tapend/tap均准确命中m1且busy=false；38292096486进一步记录消息桥接受graph/value，随后页面回调identity=false，未进入dispatch。由此排除触摸坐标与图谱命中作为剩余失败原因。2f30ba2明确转发事件所属的已验证ScriptPageView，HTML字段也使用该事件快照；共享确认状态保存视图/值/字段并只呈现当前视图的确认，过期token和宿主版本校验继续生效。截图等待WebView visual-state回调，选中边的加粗样式只作用于edge，节点保持圆形。下一步验证实际节点→详情→编辑→保存→重开→确认删除；临时诊断在发布前清理。

原失败单用例[38293650208](https://github.com/veryy/Solvit/actions/runs/38293650208)在4e53448通过，实际result为OK (1 test)，并查看实际图谱/编辑详情截图。仅传递已验证快照仍会失败：前一个请求完成到网页重新显示之间，网页按钮尚未同步进入busy，紧接的点击仍来自旧token。采用发送时同步pending、状态返回后解除、确认请求由宿主确认通知解除，原图谱点击→编辑保存→关闭重开→取消/确认删除实际通过。临时页面/图谱/触摸及原生日志探针随后清理，等待完整Android、插件包一致性及正式发布门禁；单用例通过不等于全部发布验收。

清理诊断后的远程生成38294468996成功，取回记忆包539,542字节/SHA256 `8650268525bf39cc49ce39f5e4bdfc3d3039c6f7e01ddd5829225bb98231c719`及MCP包37,381字节/SHA256 `111d17a33de9272b9b645e54ddc599aaed909e45960231b3254ba4dcb05f0f2c`。d42931e同步到APK和市场；正常SDK运行38295011430已通过提交包与源码一致性，完整Android38295011493尚在运行。
