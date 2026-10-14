# Solvit JS/TS 插件 API 1–12

插件宿主使用 QuickJS，执行 JavaScript。[TS SDK](../plugin-sdk/README.md) 提供工具、生命周期、存储与网络接口类型；TypeScript 在发布前编译并打包为 JS；没有 Node.js 或 npm 模块运行时。

## 插件包

UTF-8 `.solvit.json` 包含 `manifest` 与 `source`，HTML页面包另含离线 `resources`。市场示例：[当前时间插件](../market-server/packages/dev.solvit.clock.solvit.json)。

- `id`：稳定 ID，最长 40 字符，使用小写域名形式。
- `version`：三段数字版本。
- `apiVersion`：整数 `1`–`12`；API 2 增加配置，API 3 增加 Hook 和角色，API 4 增加工作区与插件草稿，API 5 增加交互 PTY，API 6 增加匿名浏览器观察与单次 HTTP 超时，API 7 增加布尔配置及 Hook 公共配置快照。
- `permissions`：`storage`、`network`；API 4 起还支持 `workspace`、`shell`、`plugins`。无权限插件填写空数组。
- `dependencies`：先启用的插件 ID 集合。
- `tools`：工具名、描述、JSON 对象输入 schema；工具名最长 20 字符。
- `source`：打包后的 JS，导出 `globalThis.solvitPlugin`。
- `pages`：页面 ID、标题与本包 HTML `entry`；资源放入 `resources`，业务页面由插件的HTML/CSS/JS绘制。
- `nativeServices`：API12可选原生服务依赖，见下方说明。

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

页面清单可声明本包HTML入口，资源随包离线分发。宿主负责通用WebView、权限/确认与关闭清理；业务布局和图谱由插件JS绘制。开发与桥接示例见[HTML页面指南](plugin-html-pages.md)。API9旧页面继续兼容；API10宿主查询 `hostApi=10`，当前API12宿主查询 `hostApi=12`，旧宿主保留对应版本包。实际交付状态见阶段记录，不能把资源支持当作全部插件/聊天功能已经迁移。

## API12：工作区页面与独立原生服务依赖

插件可以携带自己的 HTML/CSS/JS 工作区页面。`ctx.workspacePage.query(operation,args)` 支持 `snapshot`、`list`、`read`、`drafts`、`draft`，聊天及绑定由宿主捕获，文件查询不能提供其他聊天或工作区 ID。`list` 每页40项；`read` 返回最多4KB UTF-8文本和读取时 SHA-256。

页面按钮声明 `hostAction: {kind:"workspace.perform", operation, arguments}`，可预览创建、绑定、解除绑定、创建Shell、写文件或安装草稿。宿主显示操作及参数，用户确认后，页面通过无参数 `await ctx.workspacePage.perform()` 消费一次该确认。写入带 `expectedSha256`，新文件用 null；草稿安装带已审阅摘要并保持停用。切换聊天、外部重新绑定、关闭页面或撤权会拒绝旧能力。模型工具、Hook及生命周期回调不能调用此人工页面接口，AI仍通过独立工具授权执行。下载插件需声明并获得对应权限，安装草稿还需 `plugins` 权限。

API12 将权限与服务依赖分开：有限Shell作业不再自动依赖PTY；普通HTTP也不自动依赖浏览器。调用 `ctx.terminal` 的插件显式声明 `nativeServices:["terminal.pty"]` 和 `shell` 权限；调用 `ctx.web.visit` 的插件声明 `nativeServices:["web.pages"]` 和 `network` 权限。API5–11的隐式依赖保留兼容。新版市场查询 `hostApi=12`，旧宿主仍选择旧包。示例及实际验证范围见[工作区页面阶段](plugin-workspace-stage.md)。

## API16 项目规则只读 Hook（0.25.0起）

声明apiVersion:16、workspace权限与beforeModel，使用`ctx.workspace.instructions()`。只返回当前对话已绑定目录根的AGENTS.md、CLAUDE.md、AGENTS.local.md和CLAUDE.local.md来源，不接受任何路径、会话或工作区参数。未绑定时available=false、sources=[]，不会替普通聊天创建工作区。

sources包含path、TEXT/ABSENT/BINARY/OVERSIZED/UNAVAILABLE状态；TEXT有完整严格UTF-8正文及SHA-256，单源最多16KiB。它是文件原语，不承诺完整项目规则加载：嵌套目录触达仍待实现。JS/TS负责选择、去重、作用域、来源与预算，宿主检查角色快照、workspace声明/授权、插件启用及当前绑定，读取前后和返回前都复核。

此接口仅供beforeModel，不可从工具、页面或activate/deactivate调用；不会开放普通workspace.read/write或Shell。内置工作区1.2.0默认自动读取（用户启用并授予workspace/shell后），配置projectRules=false可关闭。每个模型步骤重新读取，修改/删除后旧Hook投影不会写入持久聊天历史或继续使用。包内TS按12KiB UTF-8预算渲染并优先保留local来源，省略/截断明确标记，资料不提升为角色/系统指令。受控Provider测试不代表真实模型遵守规则。

API16已随0.25.0发布，后续版本继续兼容；新包版本以当前市场与APK为准。实际证据与边界见[阶段记录](workspace-instructions-stage.md)。

## API21：Linux 环境页面

`dev.solvit.environment` 展示一份独立的 JS/HTML 环境管理页面；下载、解包、软件安装和子进程由原生 `environment.linux` 服务承担。第三方页面若使用这些能力，需要 `apiVersion: 21`、`nativeServices: ["environment.linux"]`，以及 `workspace`、`shell`、`network` 三项权限。

页面通过 `ctx.workspacePage.query('environmentStatus')` 获取 `supported`、`installed`、`stage`、`bytes`、`total`、`output`、`failure`。`output` 是有界近期日志，不能当作全部安装记录。`snapshot` 返回当前工作区和 Shell 的 `runtime`（`ANDROID` 或 `UBUNTU`）。

管理控件声明 `kind: 'workspace.perform'`，操作包括 `installEnvironment`、`cancelEnvironment`、`installSoftware`（`software` 只允许 `PYTHON`、`NODE`、`JAVA`）、`createLinuxShell`（当前工作区及名称）。执行仅接受当前页面已显示、由用户确认的单次原生操作；脚本不能提供进程参数、安装命令、下载地址、归档哈希或宿主私有目录。没有 API21、服务声明或完整权限时拒绝调用。

安装环境不会把 QuickJS 变成 Node，也不会为 MCP 自动启动本地进程。AI 使用已创建的 Ubuntu Shell，通过现有工作区工具和对话内授权执行命令。安装本身属于人类环境管理页面，不向模型添加一个可随意安装系统环境的工具。

## API22：工作区文件导入与预览

**已在0.34.0正式发布。** 当前宿主提供以下接口，0.33.1及更早宿主不支持API22。APK、市场兼容和设备结果见[导入阶段记录](workspace-import-stage.md)。市场为旧宿主保留兼容包。

人工HTML页面须声明 `apiVersion: 22`、`permissions: ["workspace"]`、`nativeServices: ["workspace.files"]`。聊天、工作区及文件来源由宿主捕获；脚本不能指定聊天ID、文件URI、宿主目录或替换为下载URL。普通对话没有工作区时先由用户创建/绑定；文件导入不会自动创建工作区。工具、Hook与生命周期不能使用此页面能力。

| 调用 | 返回与用途 |
| --- | --- |
| `workspacePage.query('importSelection')` | 当前选择的 `{id,name,mimeType,bytes,sha256}`，或null；不含原文件正文/URI |
| `workspacePage.query('importText', {id,sha256})` | `{kind,text,truncated}`。严格UTF-8，最多256KiB源文件、显示前8192个UTF-16单元；文本截断明确标记，二进制或超限返回分类 |
| `workspacePage.query('importPicture', {id,sha256})` | `{width,height,source}`。当前图像的有界JPEG缩略图，最多640边/64KiB；透明背景白色，动图仅首帧，原图不改变 |

选择和写入只接受**已经显示并由用户确认的控件**，处理函数调用无参数 `await ctx.workspacePage.perform()`：

```ts
const select = {
  id: 'select', kind: 'button', text: '选择文件', action: 'select',
  hostAction: {kind: 'workspace.perform', operation: 'selectImport', arguments: {}},
};
// 点击并获宿主确认后：
const selection = await ctx.workspacePage.perform();
```

`selectImport`返回文件元数据或null。取消选择保留已有选择；不要用null覆盖原页面草稿。选择本身不写入工作区。界面先显示名称、大小、来源及目标相对路径，然后另一个确认控件声明：

```ts
hostAction: {
  kind: 'workspace.perform', operation: 'importFile',
  arguments: {id: selection.id, sha256: selection.sha256, path: 'images/photo.png'},
}
```

确认后完整原始字节以create-only方式导入（最多20MiB），已存在的同名文件返回冲突，保留选择供用户改名；不得静默覆盖、重复自动提交或将缩略图代替原文件。父目录须已存在。成功返回工作区snapshot并释放当前选择。放弃选择使用确认控件 `dropImport` 与 `{id}`，只释放草稿。替换选择或关闭页面会释放旧选择。宿主在操作前后核对原对话/绑定及权限，变化或撤权时拒绝调用。

缩略图显示使用API22 HTML页面的 `image` 节点 `{id,kind:'image',source,alt,width,height}`。source仅接受规范Base64内联JPEG及上述容量/尺寸边界，不能使用HTTP、file或SVG地址，也不能附带action。HTML负责实际img绘制，例如工作区的 `ui/main.mjs`；通用旧版 `mountPage` helper没有图片绘制逻辑，第三方页面要实现自己的渲染。声明的JPEG标记不等于完整解码保证；内置预览服务会实际解码并重新编码缩略图。

图片/文本导入已在0.34.0发布，PDF分页预览已在0.35.0发布；完整导入范围仍包含聊天附件来源、覆盖版本检查、进度/停止、崩溃后暂存回收以及真机/真实模型任务仍按阶段记录继续实现。

## API23：工作区 PDF 分页预览

已在0.35.0正式发布；0.34.0/API22 不支持此查询。页面须声明 `apiVersion: 23`、workspace权限及 `workspace.files` 原生服务；使用当前选择返回的id/SHA调用 `workspacePage.query('importPdf', {id, sha256, page})`。page为1-based整数。结果包含page、pageCount、text、truncated、width、height和内联JPEG source。实际页数最多5000，当前页文字最多8192 UTF16单元，最长边640/图片64KiB；文字应作为普通文本显示。扫描页提供原页而不伪造OCR文字。

预览不会写入工作区，也不向脚本暴露URI或私有路径。导入仍使用已发布的importFile确认操作，写入完整原PDF，而非预览JPEG或截断文字。撤权/切换对话会阻止当前结果返回，关闭页面释放选择。源码和实际验证状态见[PDF阶段](workspace-pdf-preview-stage.md)。

## API24：当前对话已保存附件

0.36.2已发布。人工页面声明API24、workspace权限和workspace.files服务后，调用`workspacePage.query('chatAttachments', {offset: 0})`获取当前对话保存的用户附件；一次最多20项，返回`{offset,total,attachments}`。条目包含`messageId,messageNumber,attachmentId,name,mimeType,bytes,sha256`，不含聊天正文、附件原始字节或URI。对话和工作区由宿主捕获，脚本不能传入其他对话ID。

把用户已看到的条目放入确认按钮：`{kind:'workspace.perform',operation:'selectChatAttachment',arguments:{messageId,attachmentId,sha256}}`；获确认后调用无参数`workspacePage.perform()`，再查询`importSelection`。宿主重新读取并核对持久消息和原附件，选择元数据额外包含`source:{kind:'chatAttachment',messageId,attachmentId}`。预览及导入与系统选择的文件共用接口；PDF选页/OCR不替换原始文件。消息或原附件变化、撤权、切换对话/工作区时拒绝继续，关闭页面仅释放当前选择，不删除聊天附件。

## API25：审阅已有目标再导入（0.37.0）

0.37.0已发布，实际远程与公开下载验证见[发布记录](release-0.37.0.md)。须声明API25、workspace权限与workspace.files服务；不向普通工具、Hook或生命周期开放。

先查询`workspacePage.query('reviewImport', {id,sha256,path})`。id和sha256来自当前选择，path为当前工作区的相对路径。宿主返回`{reviewId,path,target}`；target为null表示未存在，否则是`{bytes,sha256}`。这里target.sha256是已有目标版本，和源文件的sha256含义不同。页面显示目标、源信息和替换说明后，提供确认控件：

```ts
hostAction: {
  kind: 'workspace.perform', operation: 'importReviewed',
  arguments: {id: selection.id, sha256: selection.sha256, path, reviewId: review.reviewId},
}
// 仅在已显示的上述按钮被用户确认后调用：
const result = await ctx.workspacePage.perform();
```

脚本不能传入目标摘要来伪造审阅。宿主reviewId绑定选中文档、源摘要、路径及宿主观察的目标版本；每次重新审阅替换旧凭证，提交尝试后不可重用。文件变化时保留源选择，要求重新查询审阅，不自动重新提交。目标原本不存在时仍使用create-only原子发布，同名竞争返回冲突。

成功返回snapshot，额外`lastImport:{path,sha256,backupPath}`。新建时backupPath为null；替换时为同目录保留的原文件相对路径，页面应展示给用户。备份可以在文件管理中查看/改名/删除，也会出现在Git未跟踪文件中；不会自动删除。外部Shell不受互斥锁约束，提交过程中抢先创建的同名文件不覆盖，原文件保留供恢复。旧API22的importFile仍只新建，不因升级而变成盲目覆盖。
