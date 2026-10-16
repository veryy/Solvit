# 插件 HTML/JS 页面（API10）

布局由插件包中的 HTML/CSS/JS 绘制，Android 提供通用离线 WebView 和实际权限/确认。TS 业务回调仍在独立 QuickJS 实例运行。无需为每个插件新增 Kotlin 页面。API9控件树仍可使用，新页面声明增加 `entry`：

```json
{"apiVersion":10,"pages":[{"id":"main","title":"我的插件","entry":"ui/index.html"}]}
```

沿用完整清单的 `id/name/description/version/permissions/dependencies/tools` 等字段。把入口 HTML、样式和脚本放在同一 `ui/` 目录。HTML 引用 `main.js`；开发源码可写 `main.ts` 或 `main.mjs`，SDK用esbuild将其和浏览器依赖打包为离线 `ui/main.js`。发布包包含 `manifest/source/resources`。浏览器依赖与QuickJS业务依赖分开打包，不加载CDN。静态资源支持HTML、JS、CSS、SVG、JSON和TXT，共32个文件、UTF8总计1MiB；包总计2MiB。拒绝路径穿越、绝对/远程入口、符号链接和重复输出。

## 状态与交互

QuickJS导出 `pages.main(input,ctx)`，返回已有 `PluginPageView`：`title/nodes/footer`。这些节点声明当前页允许的字段、动作、参数和危险操作确认；HTML可以自由绘制，宿主不会把这些节点另画成原生业务界面。

HTML先注册接收方法，再发送就绪：

```js
let state;
window.solvitPageUpdate = next => {
  state = next;
  // next.view: 当前业务状态/动作；next.busy、next.locale、next.theme: 宿主环境
  // token变化表示业务页面版本改变。busy/主题更新不应覆盖正在输入的草稿。
};
solvitHost.postMessage(JSON.stringify({type:'ready'}));
```

宿主收到当前页面的 `ready` 后才下发状态。在此之前发生的主题、语言和业务状态变化会保留为最新值，握手时一次发送；不会逐条重放初始化期间的旧状态。因此要等接收函数和页面组件初始化完成后再发送 `ready`。页面关闭或文档替换后，旧页面的握手和消息失效。

点击操作时发送声明的节点ID，不自行发送action、pluginId或confirmed：

```js
solvitHost.postMessage(JSON.stringify({
  type:'event',token:state.token,nodeId:'save',fields:{note:input.value}
}));
```

`value`用于choice/graph等节点的选择；`fields`仅能包含当前声明的field，长度和参数由宿主验证。过期token、未知节点和注入动作会拒绝。确认动作由宿主弹出真正的确认对话框后才执行，网页不能自行批准。业务回调收到 `input.event`，通过已有 `ctx.storage/http` 等宿主服务读写，仍受本插件权限、取消和实时撤权检查。

浏览器没有Node、原生对象或直接文件/Shell接口。它只访问本包资源和当前页消息桥；业务能力在QuickJS回调中调用经过授权的宿主服务。页面被关闭、插件停用/替换/卸载后，旧会话与资源服务失效；销毁WebView撤销消息桥并释放浏览器。页面内监听器/图实例也应在 `pagehide` 清理。

完整实际示例：[记忆插件HTML](../builtin-plugins/memory/ui/index.html)、[JS图谱与表单](../builtin-plugins/memory/ui/main.mjs)、[TS业务](../builtin-plugins/memory/pages.ts)。Cytoscape.js固定3.34.3离线打包，许可随资源分发。当前交付/验收状态以[阶段记录](plugin-html-stage.md)为准。

## 连续操作与确认回执

发送动作时网页应立即标记本地pending并阻止重入，不能只等宿主下一帧的busy。新token到达或宿主busy周期结束后解除，防止前一操作刚完成时用旧页面发送下一动作。需要确认的声明节点由宿主回复`{type:"confirmation",token}`，网页可解除对应pending；实际确认仍由原生对话框控制。`{type:"error",message}`表示请求被拒绝，可解除pending并显示本地通用错误。MCP使用SDK的可选`mountPage`实现；它随插件打包，不是原生业务渲染器。
