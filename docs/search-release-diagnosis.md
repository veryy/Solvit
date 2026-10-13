# 发布前公开搜索诊断

基线 fa65cf3，2026-10-13。按 `AGENTS.md` 阅读锁定 Operit
`dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `examples/various_search.ts`
（performSearch、search_bing）与 `StandardWebVisitTool.kt`（匿名浏览器访问、UA 选择、DOM 提取）。
采用公开浏览器访问、过滤结果、继续读取来源正文的行为；没有复制 LGPL 实现。
Solvit 的实际 WebView 回归位于 `web-android/src/androidTest/.../WebPagesTest.kt`。

已有门禁在英文/中文/最新版本三个查询上记录实际页面和错误，不允许首页链接冒充搜索结果。
隔离诊断分支的运行 [38369810763](https://github.com/veryy/Solvit/actions/runs/38369810763)
和 [38370149931](https://github.com/veryy/Solvit/actions/runs/38370149931) 保留真实 DOM 证据：
Google、DuckDuckGo、Mojeek 被拦截；Brave/Yahoo 部分查询有结果，部分也被拦截，不能据此宣称稳定可用。
必应同一会话的后续查询返回验证码；手机结果页面实际存在 `a > h2`，旧提取器只查 `h2 > a`。

修正标题链接两种嵌套形式和移动结果块摘要；仍只以真实结果标题作为搜索标记，不能扩大到任意页脚链接。
真实 WebView 用例同时验证桌面、手机布局、来源、摘要和页脚排除。
还需核对匿名访问：App 的 CookieManager 禁用 Cookie，而此前远程验收默认保留会话。
是否足以解决公网失败以远程对照与完整门禁为准，当前不能声称发布通过。
