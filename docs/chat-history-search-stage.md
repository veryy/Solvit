# 聊天侧栏标题搜索（2026-10-17）

承接0.43.10/build95，继续优化会话较多时的查找和长标题显示。侧栏原先只有全量列表，标题仅maxLines=1、没有Ellipsis；本轮增加明确命名的“按标题搜索”、清空、无结果提示，保留原生会话ID、排序、选择和删除确认。主界面继续使用75%屏宽，底部设置保持固定位置。当前为build96候选，尚未验证编译或设备结果，不作为正式发布。

## 参考实现与取舍

已实际阅读锁定Operit dbf71916的 `app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/ChatHistorySelector.kt:567–614`：修剪查询、标题/分组忽略大小写、空查询恢复全部，额外正文检索通过单独仓库执行并有防抖。本轮只过滤已载入的会话标题，UI明确写出搜索范围，不接入其分组、正文库或400ms延迟。其 `app/src/test/java/com/ai/assistance/operit/data/model/ChatHistoryTest.kt` 实际覆盖标题、消息和顺序字段，不是搜索UI证明，未找到直接对应标题输入清空设备测试。

DSH d7432673 `packages/client/ui-workspace/src/client/rows/Rows.tsx` 的SearchResultItem及标题裁切行为、`tests/rows.client.spec.tsx:104–128` 验证选中结果与点击只打开对应session；作为稳定ID和相同标题不可串会话的验收参考，未复制桌面hover/marquee。两库LICENSE分别LGPL-3.0/MIT，实际查阅；本轮仅适配行为，使用现有Compose/Material3，没有复制上游代码或引入依赖。`scripts/fetch-upstreams.py --verify-only` 用于核对固定checkout，不运行上游应用。

## Android实现与验证范围

侧栏内容抽出ChatHistoryDrawerContent，查询状态由Compose保存；关闭侧栏即重置，避免新建/重开时继续隐藏会话。搜索忽略大小写和首尾空格；清空保留输入焦点以便重输，整个侧栏随真实IME缩小，列表与底部设置保持在键盘上方；搜索键收起键盘，选择/删除/设置/新建前收起键盘。长标题显式省略，删除仍经原确认弹窗，运行中操作保护保持。6语言文案覆盖。没有存储/迁移/签名/插件格式变化。

新增设备覆盖：150%字体、两个完全同标题的独立ID、实际选择/删除回调、busy保护、空结果及清空恢复；真实App/SQLite插入独立会话，打开真实OS键盘并核对列表和设置的可见几何，用实际侧栏输入选中并核对原生session和消息，关闭重开后恢复全量列表。独立报告门禁必需这两个用例，App最低84增至86，既有流式/角色和其他必需回归保留。截图取自实际设备，等待完整GitHub Actions后再记录成功、产物与限制。没有本地Android/TS构建或测试。

正式Release/线上市场仍沿用此前实际状态，市场部署认证受阻不影响本轮原生UI继续优化；不因源码提交宣称已正式发布。真实模型/真机任务和完整路线继续保留。
