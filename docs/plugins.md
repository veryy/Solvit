# Solvit JS/TS 插件 API v1

插件宿主使用 QuickJS，执行 JavaScript。TypeScript 在发布前编译并打包为 JS；没有 Node.js 或 npm 模块运行时。

## 插件包

UTF-8 `.solvit.json` 包含 `manifest` 与 `source`。市场示例：[当前时间插件](../market-server/packages/dev.solvit.clock.solvit.json)。

- `id`：稳定 ID，最长 40 字符，使用小写域名形式。
- `version`：三段数字版本。
- `apiVersion`：当前为整数 `1`。
- `permissions`：`storage`、`network`，无权限插件填写空数组。
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

原生能力通过 `ctx.storage.get/set` 和 `ctx.http.request` 异步桥接；市场安装与权限配置已经接入原生宿主：用户启用插件时授予声明的全部权限，每次宿主调用重新检查授权。`storage` 仅访问该插件私有键值存储，单值限制 256 KiB；`http.request({url})` 当前仅支持 HTTPS GET，禁止 URL 内嵌凭据和自动重定向，响应大小不超过 1 MiB。引擎在调用宿主前检查声明权限，未知操作直接拒绝，不暴露 Java 反射或 Android Context。

## 当前边界

核心包解析、JS 执行、超时、权限检查、依赖启用和工具撤销测试已通过 GitHub Actions。Android 市场安装、用户权限授予、持久化配置与手动工具执行 UI 已通过远程编译与 Lint，包含在 0.2.0 源码中；尚未真机测试。当前源码已接入模型工具调用：启用的插件工具自动出现在模型请求中，逐次弹窗确认参数，显示执行状态与结果，默认最多 8 轮、16 次调用；正在远程验证跨模块集成测试。服务注册、事件 Hook 和声明式插件 UI 将继续增加；当前 API 不声称兼容 DSH/Cordis。
