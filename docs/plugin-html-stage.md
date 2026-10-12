# 插件 HTML/JS 业务页面与交付验收

用户在 2026-10-13 明确纠正方向：插件应能用 JS/TS、HTML 自己绘制业务界面，记忆以关系图为主，MCP 要管理多个服务器，聊天内核必须按实际效果验收。API9 的 TS 控件树加 Compose 渲染不能当作这一目标已经完成。原有全部产品路线继续保留。

## 已核对的实际源码

- Operit 锁定 dbf71916 的 `MemoryScreen.kt`、`graph/GraphVisualizer.kt`：图谱占首页主要区域并持续挂载，搜索、目录、节点/边选择及详情按需显示；实际图谱是 Compose Canvas，不是 HTML。采用交互方式，不能宣称已经搬来同等图谱能力。
- Operit `ToolPkgComposeDslWebView.kt`、`JsComposeDslBridge.kt`：有独立 WebView 控制器、资源与事件桥接和关闭清理。读到的相关测试是输入队列与回显保护；没有找到对应图谱/HTML桥的直接回归，不把这些当作 Solvit 新 WebView 的验收。
- Operit `McpConfigImportParser.kt` 及实际 `McpConfigImportParserTest.kt`：多个具名服务器、明确区分 stdio 与 HTTP/SSE、逐连接配置和禁用状态，拒绝模糊传输声明。Solvit 当前 MCP 只有一个 module-level session 和一个地址，不能称为多MCP管理。
- DSH `ui-settings-shell/tests/apply.client.spec.ts`：插件业务页注册、主题语言与 dispose 时撤销；`agent-loop/tests/tool-updates.spec.ts`：请求中的真实工具目录与变化记录。Solvit已有每步上下文/工具刷新，但当前MCP只暴露桥接入口，真实模型角色、工具选择与任务完成质量尚未验收，不能重新宣称现有机制全都没有，也不能用测试计数代替效果。
- [Cytoscape.js 官方文档](https://js.cytoscape.org/)确认纯JS图谱、MIT许可、节点/边事件及拖动缩放。拟采用现成组件，离线打入插件包并保留许可；尚未引入依赖或实现图谱。
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
