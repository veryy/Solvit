# 搜索阶段：浏览器观察与 TS 搜索策略

参考锁定的 Operit `core/tools/defaultTool/standard/StandardWebVisitTool.kt`（WebView 网页读取与结构化正文/链接）、`examples/various_search.ts`（公开搜索来源与进一步访问），以及 DSH `packages/web/web-fetch-http/src/provider.ts`（独立获取服务、取消、超时和结果边界）。采用这两个层次的职责，未复制上游实现代码。

新增 `WebPageService` 和 `web-android` 模块，宿主加载真实页面并提取 DOM 文字、标题、链接及附近摘要。当前快照在页面完成加载后等待 500ms，不宣称任意长时间异步网页都加载完整。只允许 HTTPS，拒绝文件、Content URI、外部应用跳转及无效证书，不给网页安装原生 JavaScript 接口；不传模型或插件配置的授权 Header。网页工具使用匿名浏览，当前应用没有其他登录 WebView。

观察服务执行串行、有总超时、结果长度和链接数量限制。超时或停止后在主线程停止并销毁 WebView。业务策略仍由 TS 插件实现；后续接入 API 6 的 `ctx.web.visit`，保持 network 权限与执行门禁，补齐公开搜索回退、结果整理、正文和来源返回。

验证分层：JVM 检查结果/链接过滤与大小边界；Android 模拟器用真实 WebView 执行离线页面中的异步 DOM 修改并提取链接；公网搜索另外检查相关性与正文。离线文档测试不等同于真实搜索引擎和真实模型联动验收。

已接入 API 6 `ctx.web.visit(url)`，要求 network 声明/授权和实际工具调用上下文，Hook 和启停回调不能偷偷启动浏览。宿主不接受 Header、Cookie 或任意浏览器设置。插件包解析、TS 类型、市场目录与打包协议同步支持 API 6，旧 API 目录继续过滤。搜索插件升级、正文质量、公网验证与服务器部署继续实现，尚未发布为用户可用的完整搜索升级。
