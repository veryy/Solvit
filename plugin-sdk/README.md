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

### API 8：后台记忆（Solvit 0.19.0）

Declare `hooks: ["afterTurn"]`, `afterTurnEnabledSetting: "autoSave"`, and a
boolean setting `autoSave` with `defaultValue: "false"`. Request `model`
permission to use `ctx.model.complete({ instructions, input })`. The host owns
credentials, input/output budgets, the deadline and the one-call limit.
Solvit 0.19.0 已发布此接口；真实模型的抽取质量仍需验证。

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

## API 9：插件原生页面（Solvit 0.20.0）

清单用 `pages: [{id: 'main', title: '便签'}]` 注册页面；插件实现 `pages.main(input, ctx)`，返回 `title`、`nodes` 和可选固定 `footer`。节点与事件类型见 [pages.ts](src/pages.ts)，可安装示例源码见 [notes](../plugin-examples/notes)。页面是独立 QuickJS 实例，不运行 activate/deactivate，不共享聊天 Hook 的全局状态。宿主提供主题、语言、系统/键盘边距、原生表单和关系图，TS 定义业务、数据、文案和事件。

`input.locale` 有 zh/en/ja/ko/es/fr；`input.event` 包含当前节点派生的 action、输入值及校验过的表单字段。关闭、停用、卸载或更新会取消对应实例；事件有显示版本保护，数据写入锁内重新检查授权。页面不提供隐式 Shell、工作区或模型调用权限。下载页面须先启用并授予声明权限；停用时的内置记忆管理只运行 APK 固定代码。

`ctx.jobs.list({cursor})` 仅在页面中提供本插件的任务元数据，最多 20 条；`available:false` 表示服务缺失。`ctx.jobs.perform()` 不接受目标参数，只执行当前按钮由宿主确认的 `hostAction`，每次事件只能消费一次。重试/删除/停止声明具体 jobId 和 attempt，删除还声明预期 status；完成清理使用 `postTurn.cleanup`。宿主始终展示自己的费用/删除/停止提示，插件的文案不能替换确认内容。任务列表、详情和导航仍由 TS 提供。实际实现见 [记忆任务页面](../builtin-plugins/memory/task-pages.ts)；重试不会启用插件、授予模型权限或直接等待推理结果。


## API14：可安装 Agent 步骤策略

最低宿主为0.23.1候选，示例见 [agent/index.ts](../plugin-examples/agent/index.ts) 和 [manifest](../plugin-examples/agent/manifest.json)。声明 `apiVersion:14`、`permissions:["agent"]`、`agentEnabledSetting:"selected"`；selected必须是默认关闭、非pageManaged的boolean配置字段。安装后先在宿主配置中打开接管，再授权启用；已有启用实例须先停用才能修改配置。插件页面不能自行写入这个开关。

`definePlugin({tools:{},async agent({maxRounds},ctx){...}})` 的ctx是独立 `AgentContext`，不是工具的PluginContext。每轮全新实例，不共享activate/deactivate状态；只有model(round)、tool(index)、settle(round)、finish(round)四个异步操作。model返回本步骤的工具数量，逐个tool完成后必须settle；没有工具时finish。宿主限制步骤/调用数并继续执行权限确认、角色上下文、原生协议转录和持久结算。具体循环见示例；错误或跳步不能绕过宿主校验。

当前驱动选择影响全部聊天，多个策略同时打开时会报错；无选中策略使用包内JS。停用、卸载、替换取消其正在运行的轮次并等待清理。第三方Agent实例限8MiB内存、256KiB栈与10分钟评估期限；不能直接访问API Key、网络、存储、模型消息或工具原始结果。尚未开放消息投影、模型流RPC或JS Provider/存储替换，不能把本接口当作完整DSH/Cordis内核。

SDK构建任务同时生成 `market-server/packages/example.agent.solvit.json`，不预装到Android主APK。阶段证据见 [Agent插件阶段](../docs/agent-plugin-stage.md)。


### API15 Agent 步骤观察（开发中）

策略声明 `agent` 和 `agent.read`，经宿主授权后可调用 `ctx.observe()` 获取当前用户请求、角色名称、模型正文/思考、当前工具参数与结算事实；字段使用 `{text,truncated}`，工具最多16项，`toolsTruncated` 提示省略。没有模型连接配置、Provider原生签名或任意宿主对象。返回独立副本，修改它不会改写聊天协议或持久记录。

`ctx.decline(index)` 按原顺序拒绝拟调用，不进行审批/执行；拒绝结果仍回传下一轮模型。每个调用仍须完整结算，不允许通过快照截断删除后续协议消息。API14或没有读取权限的包没有观察/拒绝桥。示例见 `plugin-examples/agent-policy`，它仅审核参数是否完整；不代表复杂任务策略或真实模型效果已经验证。当前详细验证状态见 [阶段记录](../docs/agent-observation-stage.md)。

### API16 项目规则读取（候选0.25.0，未发布）

beforeModel内可调用`ctx.workspace.instructions()`，需要apiVersion:16、workspace声明/授权和角色绑定。宿主捕获当前聊天，只返回绑定目录根AGENTS.md、CLAUDE.md、AGENTS.local.md、CLAUDE.local.md；接口无参数、禁止其他路径和工作区选择。未绑定时available=false。每源最多16KiB严格UTF-8，sources含状态、正文与SHA；不能从工具、页面或生命周期调用。

内置workspace1.2.0由TS整理来源、去重、local优先及12KiB UTF-8预算；projectRules=false可关自动投影。更新和删除每步重读，资料不改变角色身份、系统规则或工具授权。宿主读前/读后核验授权和绑定，取消传播；旧API1–15目录继续使用原包。实际契约、请求回归和未完成范围见[阶段记录](../docs/workspace-instructions-stage.md)。


### API19 Git 页面（开发候选）

清单声明 `permissions: ["workspace", "git"]` 和 `nativeServices: ["workspace.git"]`。页面可调用 `ctx.gitPage.query("status", {offset: 0, limit: 10})`、`diff`、`diffChunk`、`history` 和 `reviewCommit`。差异/暂存区审阅返回宿主句柄，JS不传根目录、patch或Git版本对象。`ctx.gitPage.perform()` 无参数，仅消费当前用户确认的 `git.perform` 按钮；支持 initialize、stage/unstage 的 reviewId 和 commit 的 revisionId/message/author/email。关闭页面收回句柄及绑定接口，旧宿主拒绝API19。此协议尚未在公开0.28 APK中提供，Git页面及工具接线仍在开发。

### API29：中断的 Git 克隆（候选，尚未发布 APK）

Git 1.3.1 页面使用 `gitPage.query("cloneInstalls", {offset: 0, limit: 5})` 查看当前工作区的持久记录，再通过 `reviewCloneInstall` 和 `{id}` 审阅。宿主返回来源、阶段、可继续/重新下载状态、文件统计、问题说明及一次性 `reviewId`，不返回私有暂存路径。

用户明确确认 `git.perform` 后，页面调用 `gitPage.perform()`；支持 `resumeCloneInstall`、`restartCloneDownload`、`discardCloneInstall`，参数只能是 `{reviewId}`。继续和重新下载也可嵌入既有 `startRemote` 异步任务。句柄在尝试执行前消费，失败须重新审阅；页面关闭、工作区变化或权限撤销后不能复用。恢复不会自动执行：继续核对已安装文件，冲突不覆盖；放弃仅关闭登记的私有暂存，保留工作区文件。重新下载需要另行网络授权，已发布仓库的确认不会重置后续编辑。

API1–28 目录保留 Git1.2.7 的原始包，不能调用 API29 恢复动作。测试结果和实际强杀验收进度见[阶段记录](../docs/workspace-git-recovery-stage.md)。
