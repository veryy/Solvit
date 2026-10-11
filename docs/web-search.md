# 联网搜索插件

内置 TS 插件 `dev.solvit.web-search`，升级后出现在已安装列表，默认停用。启用网络权限后，在插件配置中选择来源；自定义角色还要勾选此插件。

- `tavily`（默认）：填写 API Key，地址留空使用 `https://api.tavily.com/search`。
- `searxng`：填写支持 `format=json` 的 HTTPS `/search` 地址，可选 Bearer 令牌。公共实例不一定开启 JSON；HTTP 403 会明确报告。
- `zhipu`：填写智谱 API Key，地址留空使用官方 `web_search` API。

让 AI “联网搜索……并给来源”，它会调用 `search`，按需调用 `read_page` 读取静态网页文字。普通聊天和代码示例无需搜索。结果包含标题、摘要和 HTTPS 链接；无结果、服务错误或未配置不会伪造答案。工具详情折叠在同一聊天气泡里。

密钥沿用宿主加密插件设置，不会进入搜索结果或转发到阅读的网页。网络请求仍走权限宿主，工具仍受角色和用户授权限制。“允许且不再询问”可以在权限设置里清除。

参考 Operit 的 [visit_web 工具注册](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt) 和 [智谱搜索脚本](https://github.com/AAswordman/Operit/blob/main/examples/zhipu_search.ts)：搜索服务和网页读取是独立工具。这里使用原生 TS 实现，没有复制 Operit 的脚本运行时。接口依据 [Tavily 文档](https://docs.tavily.com/documentation/api-reference/endpoint/search) 和 [SearXNG 文档](https://docs.searxng.org/dev/search_api.html)。

边界：没有浏览器自动操作、登录、验证码处理或动态网页渲染；搜索 API 需要用户自己的服务配置。真实搜索账号尚待配置后验证。
