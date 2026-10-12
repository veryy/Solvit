# 插件原生页面示例

API 9 开发分支示例，公开的 Solvit 0.19.0 还不支持此接口；完整页面版本发布后使用。这里只用 TS 定义业务、文案、表单和事件，App 不需要新增便签专用页面。

使用 SDK `build-plugin.mjs` 打包本目录的 `index.ts` 与 `manifest.json`，得到 `.solvit.json` 后导入支持 API 9 的 Solvit；安装默认停用，确认 storage 权限并启用后，在已安装插件详情打开“便签”。六种文案跟随宿主传入的 locale，保存/删除仅访问 `example.notes` 命名空间。

`footer` 固定在正文滚动区域下方；`confirm` 由原生宿主显示确认窗口，点击取消不调用插件。宿主检查事件对应的显示版本、节点和表单字段；插件页面不能获取应用模型密钥或绕过 Shell/工具授权。
