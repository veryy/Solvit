# Solvit JS/TS 插件 API 1–12

插件宿主使用 QuickJS，执行 JavaScript。[TS SDK](../plugin-sdk/README.md) 提供工具、生命周期、存储与网络接口类型；TypeScript 在发布前编译并打包为 JS；没有 Node.js 或 npm 模块运行时。

## 插件包

UTF-8 `.solvit.json` 包含 `manifest` 与 `source`。市场示例：[当前时间插件](../market-server/packages/dev.solvit.clock.solvit.json)。

- `id`：稳定 ID，最长 40 字符，使用小写域名形式。
- `version`：三段数字版本。
- `apiVersion`：整数 `1`–`12`；API 2 增加配置，API 3 增加 Hook 和角色，API 4 增加工作区与插件草稿，API 5 增加交互 PTY，API 6 增加匿名浏览器观察与单次 HTTP 超时，API 7 增加布尔配置及 Hook 公共配置快照。
- `permissions`：`storage`、`network`；API 4 起还支持 `workspace`、`shell`、`plugins`。无权限插件填写空数组。
- `dependencies`：先启用的插件 ID 集合。
- `tools`：工具名、描述、JSON 对象输入 schema；工具名最长 20 字符。
- `source`：打包后的 JS，导出 `globalThis.solvitPlugin`。

```js
globalThis.solvitPlugin = {
  async activate(ctx) {
    // 可选：启用时准备状态。
  },
  tools: {
    async current_time(args, ctx) {
      return { utc: new Date().toISOString() };
    }
  },
  async deactivate(ctx) {
    // 可选：停用时清理。
  }
};
```

每个启用插件拥有独立引擎、32 MiB JS 内存上限、512 KiB 栈和 30 秒执行期限。宿主 Coroutine 取消与引擎中断相连，停用先中断执行，撤销工具并关闭引擎。模型可见工具名以插件 ID 转为下划线的前缀命名，避免跨插件重名。

原生能力通过 `ctx.storage.get/set` 和 `ctx.http.request` 异步桥接；市场安装与权限配置已经接入原生宿主：用户启用插件时授予声明的全部权限，每次宿主调用重新检查授权。`storage` 仅访问该插件私有键值存储，单值序列化后最多 262144 个 UTF-16 字符；`http.request({url, method?, headers?, body?})` 支持 HTTPS GET/POST/DELETE，禁止 URL 内嵌凭据和自动重定向，响应大小不超过 1 MiB。引擎在调用宿主前检查声明权限，未知操作直接拒绝，不暴露 Java 反射或 Android Context。

## 当前边界

核心包解析、JS 执行、超时、权限检查、依赖启用和工具撤销测试已通过 GitHub Actions。Android 市场安装、用户权限授予、持久化配置与手动工具执行 UI 已通过远程编译与 Lint，包含在 0.2.0 源码中；尚未真机测试。当前源码已接入模型工具调用：启用的插件工具自动出现在模型请求中，逐次弹窗确认参数，显示执行状态与结果，默认最多 8 轮、16 次调用；跨模块 HTTP→QuickJS 集成测试已通过 GitHub Actions，真机与真实模型仍待验证。源码已开放只读 beforeModel Hook、声明式配置 UI 和角色范围；更多服务注册、事件 Hook 和 UI 扩展继续增加；当前 API 不声称兼容 DSH/Cordis。

## 内置记忆

[本地记忆插件](memory.md) 采用同样的包与宿主 API，提供事实、画像、关系、文本分块和关键词检索。首次启动离线安装但保持停用，启用时授权。插件被用户卸载后不会自动恢复安装。

## MCP 与配置

[MCP 插件](mcp.md)的API10候选支持多个具名连接，配置界面由插件HTML/JS绘制，宿主提供加密保存和页面权限。旧API2单地址包保留兼容。`ctx.settings.get`仅读取本插件声明的配置；API10页面可用`ctx.settings.compareAndSet(key,expected,value)`原子更新自己的声明字段，工具、Hook和生命周期不能写配置。`pageManaged:true`字段由HTML管理页编辑，宿主不再显示重复表单。MCP通信与会话逻辑仍在TS，HTTPS与取消由宿主提供。当前发布状态见MCP文档。

## API 5：交互式 PTY

声明 `apiVersion: 5` 和 `shell` 权限，通过 `ctx.terminal.call(operation,args)` 请求持久终端。与 API 4 的工作区工具一样，必须绑定当前聊天，参数与本次确认的工具 JSON 完全一致。宿主隔离终端所属插件与角色；JS 不接收 native handle。

- `open` `{shellId}`：打开当前工作区的命名 Shell，返回 `id`；同一插件和角色再次打开会恢复活动会话。
- `input` `{id,text}`：发送原始终端文本，执行命令/回答提示时自行在末尾添加 `\n`；返回会话快照。
- `status` `{id}`：读取 `active/output/revision/truncated/exitCode/failure`；输出累计保存最近 65536 个字符，模型应按 revision 避免重复处理。
- `interrupt` `{id}`：Ctrl-C 中断前台命令，保留交互 Shell。
- `close` `{id}`：关闭终端。停用插件、切换执行身份或停止使用该终端的 Agent 回合也会清理会话。

`input` 不保证命令已经完成，之后需读取 `status`。支持同一进程的 `cd`、环境变量和交互提示；原有 `workspace.start` 是每次独立运行的有限作业。当前 PTY 使用普通应用身份，Root 模式明确拒绝，不降级。示例：[终端插件](../builtin-plugins/terminal/index.ts)；完整边界见 [工作区与 Shell](workspaces.md)。

## API 6：匿名浏览器观察

声明 `apiVersion: 6` 和 `network` 权限，工具内使用 `await ctx.web.visit("https://example.com")`。返回 `{ url, title, text, links, truncated }`，每条链接含 `{ url, title, snippet }`。宿主只接受 URL，不接受 Header、Cookie 或任意浏览器设置；Hook 与启停回调不能调用浏览器，实际工具授权仍由宿主执行。

观察服务只允许无用户密码的 HTTPS 地址，有单次 7 秒期限，正文最多 16,000 字符、链接最多 60 条。页面完成加载后等待 500ms 再观察初始 DOM，不保证所有长时间异步内容完整。取消、失败或完成后销毁 WebView。

`ctx.http.request` 可额外传 `timeoutMs`（200–20,000 毫秒），仅 API 6 接受，限制该次调用，超时后允许工具执行受限的后备逻辑；未提供则沿用宿主默认期限。网页与工具返回内容属于外部资料，不能覆盖角色、用户意图或宿主权限。

新宿主查询市场使用 `hostApi=6`。搜索包 1.2.0 使用这些接口；旧客户端仍获取兼容包。完整示例见 `builtin-plugins/web-search/index.ts`。

## API 7：公共配置与布尔开关

`settings` 支持 `kind: "boolean"`，可声明字符串 `defaultValue: "true"` 或 `"false"`，由原生 Switch 编辑并校验。只有布尔配置可声明默认值，密钥字段不能提供默认值。

`beforeModel(input, ctx)` 的 `input.settings` 是该插件声明为非 `secret` 的配置副本，值为字符串或 null；旧 API 不提供此字段。Hook 仍不能调用 `ctx.settings.get` 读取密钥，也不能写入、联网、运行工具或 Shell。Boolean 例子见记忆插件的 `autoRecall`。

模型每个步骤重新读取 Hook，并以原始身份/规则重建提示词。不要在 Hook 内写入、调用模型或持续累积文本；不启用或角色未绑定的插件不提供上下文。新版宿主查询市场使用 `hostApi=7`，旧客户端过滤不兼容包。

## API10：插件自己绘制 HTML/JS 页面

页面清单可声明本包HTML入口，资源随包离线分发。宿主负责通用WebView、权限/确认与关闭清理；业务布局和图谱由插件JS绘制。开发与桥接示例见[HTML页面指南](plugin-html-pages.md)。API9旧页面继续兼容；新版宿主查询 `hostApi=10`，旧宿主保留对应版本包。实际交付状态见阶段记录，不能把资源支持当作全部插件/聊天功能已经迁移。

## API12：工作区页面与独立原生服务依赖

插件可以携带自己的 HTML/CSS/JS 工作区页面。`ctx.workspacePage.query(operation,args)` 支持 `snapshot`、`list`、`read`、`drafts`、`draft`，聊天及绑定由宿主捕获，文件查询不能提供其他聊天或工作区 ID。`list` 每页40项；`read` 返回最多4KB UTF-8文本和读取时 SHA-256。

页面按钮声明 `hostAction: {kind:"workspace.perform", operation, arguments}`，可预览创建、绑定、解除绑定、创建Shell、写文件或安装草稿。宿主显示操作及参数，用户确认后，页面通过无参数 `await ctx.workspacePage.perform()` 消费一次该确认。写入带 `expectedSha256`，新文件用 null；草稿安装带已审阅摘要并保持停用。切换聊天、外部重新绑定、关闭页面或撤权会拒绝旧能力。模型工具、Hook及生命周期回调不能调用此人工页面接口，AI仍通过独立工具授权执行。下载插件需声明并获得对应权限，安装草稿还需 `plugins` 权限。

API12 将权限与服务依赖分开：有限Shell作业不再自动依赖PTY；普通HTTP也不自动依赖浏览器。调用 `ctx.terminal` 的插件显式声明 `nativeServices:["terminal.pty"]` 和 `shell` 权限；调用 `ctx.web.visit` 的插件声明 `nativeServices:["web.pages"]` 和 `network` 权限。API5–11的隐式依赖保留兼容。新版市场查询 `hostApi=12`，旧宿主仍选择旧包。示例及实际验证范围见[工作区页面阶段](plugin-workspace-stage.md)。
