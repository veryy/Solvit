# Solvit TypeScript 插件 SDK

TypeScript 编写业务，发布时打包为 `.solvit.json`，由 Android QuickJS 执行。SDK 当前提供类型化工具、生命周期、私有存储、HTTPS 请求、私有配置和 JSON 参数辅助函数。不是 Node.js/Cordis 运行时。

## 开发和打包

开发环境使用 Node 22+。在仓库根目录：

```sh
cd plugin-sdk
npm ci
npm run typecheck
node scripts/build-plugin.mjs ../plugin-examples/echo/index.ts ../plugin-examples/echo/manifest.json /tmp/example.echo.solvit.json
```

入口文件默认导出 `definePlugin({ tools, activate?, deactivate? })`，示例见 [echo](../plugin-examples/echo/index.ts)，实际内置插件见 [memory](../builtin-plugins/memory/index.ts)。构建器把 TS、相对模块和可打包依赖合并为一份 JS，设置 `globalThis.solvitPlugin`；manifest 的 tools 名称必须对应处理器。

第三方依赖应安装到插件自己的开发目录，构建时全部打包。只能使用 QuickJS 和宿主提供的能力；依赖 Node/DOM 的包无法直接使用，不支持运行时 npm 安装、process、Buffer、fetch、setTimeout、文件系统或 Node MCP stdio SDK。

## 宿主接口

```ts
import { definePlugin, textArgument } from '../../plugin-sdk/src/index';
export default definePlugin({
  async activate(ctx) { /* 可选初始化 */ },
  tools: {
    async remember(args, ctx) {
      const text = textArgument(args, 'text');
      await ctx.storage.set('note', { text });
      return { saved: true };
    },
  },
});
```

`storage.get/set` 需要 manifest 声明 storage 权限且用户已授予。get 不存在时返回 null，set 返回 null；输入输出必须可 JSON 序列化，undefined、函数和循环对象不支持。`http.request({ url, method?, headers?, body?, cancelBody? })` 需要 network 权限，支持 HTTPS GET/POST/DELETE，返回 `{ body, status, headers }`；HTTP 错误状态也返回，响应头仅暴露 Content-Type 和 MCP 会话/版本头。请求和响应大小有上限，禁止重定向、URL 内嵌凭据和控制连接的请求头。

API v2 可以在 manifest `settings` 声明 text/url/secret 配置字段，由原生界面渲染，停用时保存。`ctx.settings.get(key)` 只读取本插件声明的字段，返回字符串或 null；配置使用加密存储，不进入模型描述。旧 API v1 插件仍可运行。新功能插件应声明 apiVersion: 2，旧 App 的目录只显示它支持的包。

`cancelBody` 仅用于 POST：请求被取消且 network 权限仍有效时，宿主最多用 1 秒向相同 URL/请求头发送该清理内容。MCP 用它发送取消通知；不要在这里安排新的业务操作。

manifest `dependencies` 是其他已启用 Solvit 插件的 ID，用于运行时依赖排序；TS import 是打包依赖，两者用途不同。API 3 已开放只读 beforeModel Hook、角色上下文与管理工具。插件跨服务调用、其他事件 Hook、模型扩展和更多 UI 扩展继续实现。

## 内置包与远程验证

```sh
npm run build:builtins
npm run typecheck
npm test
```

build:builtins 同时更新市场包与 Android 离线 assets；不要只改生成的 JS。GitHub Actions 重新打包并检查两份产物与提交内容一致，执行 TS 和插件测试。Android 的实际 QuickJS 与存储集成由 Android Actions 验证。

## API 3：角色与上下文

manifest 声明 `apiVersion: 3` 与 `hooks: ["beforeModel"]`，插件实现：

```ts
beforeModel: async (input, ctx) => {
  const directory = await ctx.storage.get('catalog');
  return JSON.stringify(directory ?? []);
}
```

Hook 只允许读取本插件存储，不允许 settings.get、storage.set 或 HTTP。每个最多 10 秒/32 KiB，合计最多 64 KiB。返回文本加入本轮模型上下文；角色未绑定时不执行，卸载会注销。

普通工具与 Hook 都能通过 `ctx.role` 取得宿主提供的角色 ID、名称和 skillIds；默认角色为 null。插件 ID 由宿主用于工具发现及执行范围检查，管理参数不能伪造角色。`ctx.role` 的数据不可视为不同插件间存储隔离。

manifest 工具设置 `exposure: "management"` 后，只出现在原生管理调用中，不向模型提供。默认值为 `chat`。Skills 导入、删除、导出使用 management；聊天正文/资源读取使用 chat。示例及限制见 [Skills](../docs/skills.md)。

Solvit 0.7.0 为 API 3 提供可选 `ctx.clock.now()`，返回 `utc`、`localTime`、Android IANA `timezone` 和 JS 约定的 `timezoneOffsetMinutes`（上海为 -480）。这是只读服务，可在 beforeModel 中使用；旧宿主不存在此属性，插件应检查后回退到 JS Date。实际示例见 [时间插件](../builtin-plugins/clock/index.ts)。


## API 4：工作区与插件草稿

权限新增 `workspace`、`shell`、`plugins`；老宿主仍只收到兼容的市场包。插件不直接访问文件系统或进程，通过 `ctx.workspace.call(operation, args)` 与 `ctx.plugins.draft(args)` 请求原生服务。参数必须与用户确认的工具 JSON 完全一致，并绑定当前聊天的工作区。管理页手动试运行没有聊天上下文，因此工作区工具应在绑定工作区的对话中使用。

- `workspace.call('info', {})`：工作区名称、ID 与 Shell ID。
- `workspace.call('read', {path})` / `workspace.call('write', {path,text})`：工作区文本文件。
- `workspace.call('createShell', {name})`：创建命名 Shell 会话。
- `workspace.call('start', {shellId,command})`：以应用身份启动非交互式命令。
- `workspace.call('status', {id})` / `workspace.call('cancel', {id})`：当前插件和角色的任务输出、状态与停止。
- `plugins.draft({manifestJson,source})`：启动校验并写入草稿，无法安装或启用。

`beforeModel` Hook 仍不能调用这些能力，插件 `activate` 也没有用户授权的工作区上下文。Shell 除工作目录外没有操作系统隔离，可访问应用 UID 的文件与网络。实现示例在 `builtin-plugins/workspace`、`builtin-plugins/authoring`，使用边界见 [工作区](../docs/workspaces.md) 与 [插件开发](../docs/plugin-authoring.md)。

## API 5：交互终端

新增 `ctx.terminal.call('open' | 'status' | 'input' | 'interrupt' | 'close', args)`，由独立宿主插件提供 Android PTY。JS 工具示例见 [terminal](../builtin-plugins/terminal/index.ts)，参数、授权与运行边界见 [API 5 文档](../docs/plugins.md#api-5交互式-pty)。TS 在 CI 打包为 JS，手机不直接运行 TS 编译器。

### API 8 candidate: background completed-turn hooks

Declare `hooks: ["afterTurn"]`, `afterTurnEnabledSetting: "autoSave"`, and a
boolean setting `autoSave` with `defaultValue: "false"`. Request `model`
permission to use `ctx.model.complete({ instructions, input })`. The host owns
credentials, input/output budgets, the deadline and the one-call limit.
This candidate is not yet a published automatic-memory feature.

`afterTurn(input, ctx)` receives the completed user/assistant text, source message
IDs and role ID/name, plus schema-declared public settings. It runs in a fresh
engine without `activate`/`deactivate`; share data through persistent storage.
It may read/write its own storage, read the clock and request bounded inference.
Network, Shell and arbitrary tools are unavailable. Foreground tools and
`beforeModel` cannot call this model interface.

Use `ctx.storage.compareAndSet(key, expected, value)` to commit a merge only if
the last-read value is unchanged. Clone the expected snapshot before editing it,
then reread and merge on conflict. Do not repeat inference for storage conflicts.
Existing `storage.set` participates in the same host lock. Commit source deduplication
and facts together, so an interrupted task can be retried without duplicate facts.
