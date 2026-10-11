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

manifest `dependencies` 是其他已启用 Solvit 插件的 ID，用于运行时依赖排序；TS import 是打包依赖，两者用途不同。插件跨服务调用、事件 Hook、模型扩展和声明式 UI 还未开放。

## 内置包与远程验证

```sh
npm run build:builtins
npm run typecheck
npm test
```

build:builtins 同时更新市场包与 Android 离线 assets；不要只改生成的 JS。GitHub Actions 重新打包并检查两份产物与提交内容一致，执行 TS 和插件测试。Android 的实际 QuickJS 与存储集成由 Android Actions 验证。
