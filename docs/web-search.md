# 联网搜索

联网搜索插件 1.1.0 默认访问公开 Bing 搜索，安装并启用后无需填写 API Key 或服务地址。角色使用自定义插件白名单时，需要允许 `dev.solvit.web-search`；实际工具调用仍经过用户授权。

默认先访问 `cn.bing.com` 的 RSS 搜索结果，失败再访问 `www.bing.com`。返回标题、摘要和来源链接，正文按需调用 `read_page`。公共搜索可能受网络、验证码或限流影响，失败会明确说明，不能保证每次都能获得结果。它不是私有付费 API，也不会发送配置中已有的密钥。

保留可选 Tavily、SearXNG 和智谱接入，只有主动选择这些后端时才需要配置。旧版用户明确保存的后端不会被覆盖。

借鉴 [Operit various_search](https://github.com/AAswordman/Operit/blob/main/examples/various_search.ts) 访问公开搜索网页的思路；Solvit 自行实现 RSS 提取，没有复制其源码。网页内容作为外部数据，不覆盖角色与用户指令。
