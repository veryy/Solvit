# 在 Solvit 内让 AI 编写插件

启用“插件开发”插件，角色允许 `dev.solvit.authoring` 后，可以在对话中要求：

> 写一个返回名字问候的 JS 插件，生成可以审阅的插件草稿。

AI 调用 `draft_plugin`，提交完整的 `manifestJson` 和 `source`。工具确认中可以查看实际参数。宿主解析清单、检查版本、权限、工具声明，并在独立 QuickJS 引擎中执行启动校验：32 MiB 内存、5 秒执行期限；校验阶段不能调用网络、文件、Shell、插件安装等宿主操作。所有声明的工具必须有函数处理器。

成功后，草稿 JSON 写入当前工作区的 `plugin-drafts/<草稿 ID>.solvit.json`，并记录 SHA-256。生成草稿不会安装或启用插件。在“工作区 → 插件草稿”查看代码和权限，再点“安装为停用插件”；安装只接收审阅过的摘要。同 ID 旧插件会被替换并停用，必须回插件页重新配置、授权和启用。文件被修改后需重新校验。启用后角色仍需允许该插件。

应用内目前生成的是普通 JavaScript：

```js
globalThis.solvitPlugin = {
  tools: {
    hello: async (args, ctx) => ({ message: `你好，${args.name}` })
  }
};
```

不能直接执行包含类型注解、`import`、Node `require` 的 TS 源码。TS 开发者使用本仓库 SDK，在 GitHub Actions 或开发工具中编译为 IIFE JS，再生成 `.solvit.json` 包。应用内 TS 编译、完整编辑调试、自动修复循环、发布到市场尚未实现。

启动校验能证明清单和处理器可以加载，不能证明工具在真实服务上的业务行为正确，也不保证代码安全。启用插件后需要试运行工具并检查授权范围。当前草稿记录保留最近 64 条，每包最多 256 KiB；配置备份尚不包含这些工作区文件。

参考 Operit 的 [脚本开发说明](https://github.com/AAswordman/Operit/blob/main/docs/SCRIPT_DEV_GUIDE.md) 和 [ToolPkg 格式](https://github.com/AAswordman/Operit/blob/main/docs/TOOLPKG_FORMAT_GUIDE.md)，沿用“生成 → 校验 → 查看 → 安装”的流程，以 Solvit 自己的清单、JS 引擎和服务注册实现，没有复制 Operit 的代码或引入 Cordis 运行时。
