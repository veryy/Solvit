# 聊天侧栏标题搜索（2026-10-17）

承接0.43.10/build95，继续优化会话较多时的查找和长标题显示。侧栏原先只有全量列表，标题仅maxLines=1、没有Ellipsis；本轮增加明确命名的“按标题搜索”、清空、无结果提示，保留原生会话ID、排序、选择和删除确认。主界面继续使用75%屏宽，底部设置保持固定位置。当前为build96候选，尚未验证编译或设备结果，不作为正式发布。

## 参考实现与取舍

已实际阅读锁定Operit dbf71916的 `app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/ChatHistorySelector.kt:567–614`：修剪查询、标题/分组忽略大小写、空查询恢复全部，额外正文检索通过单独仓库执行并有防抖。本轮只过滤已载入的会话标题，UI明确写出搜索范围，不接入其分组、正文库或400ms延迟。其 `app/src/test/java/com/ai/assistance/operit/data/model/ChatHistoryTest.kt` 实际覆盖标题、消息和顺序字段，不是搜索UI证明，未找到直接对应标题输入清空设备测试。

DSH d7432673 `packages/client/ui-workspace/src/client/rows/Rows.tsx` 的SearchResultItem及标题裁切行为、`tests/rows.client.spec.tsx:104–128` 验证选中结果与点击只打开对应session；作为稳定ID和相同标题不可串会话的验收参考，未复制桌面hover/marquee。两库LICENSE分别LGPL-3.0/MIT，实际查阅；本轮仅适配行为，使用现有Compose/Material3，没有复制上游代码或引入依赖。`scripts/fetch-upstreams.py --verify-only` 用于核对固定checkout，不运行上游应用。

## Android实现与验证范围

侧栏内容抽出ChatHistoryDrawerContent，查询状态由Compose保存；关闭侧栏即重置，避免新建/重开时继续隐藏会话。搜索忽略大小写和首尾空格；清空保留输入焦点以便重输，整个侧栏随真实IME缩小，列表与底部设置保持在键盘上方；搜索键收起键盘，选择/删除/设置/新建前收起键盘。长标题显式省略，删除仍经原确认弹窗，运行中操作保护保持。6语言文案覆盖。没有存储/迁移/签名/插件格式变化。

新增设备覆盖：150%字体、两个完全同标题的独立ID、实际选择/删除回调、busy保护、空结果及清空恢复；真实App/SQLite插入独立会话，打开真实OS键盘并核对列表和设置的可见几何，用实际侧栏输入选中并核对原生session和消息，关闭重开后恢复全量列表。独立报告门禁必需这两个用例，App最低84增至86，既有流式/角色和其他必需回归保留。截图取自实际设备，等待完整GitHub Actions后再记录成功、产物与限制。没有本地Android/TS构建或测试。

正式Release/线上市场仍沿用此前实际状态，市场部署认证受阻不影响本轮原生UI继续优化；不因源码提交宣称已正式发布。真实模型/真机任务和完整路线继续保留。

## 第一轮完整设备失败与定位（2026-10-18）

39047145389在首提交上因后续IME适配提交由Actions自动取消，不能作为完整通过。实际64b4aef9的39047773141构建、626单元、Lint和既定签名通过，设备完整执行86+3+26后失败6项；未交付该失败APK。原始UI artifact12106743699（10997929字节，SHA256 1b0a9fc1c13a6e70bdea26fa552c7c14d17bd5c01ada02468bf9ef5dbea0e2ad）已核对大小、整包摘要/CRC和原始XML。

5项既有聊天/附件/PDF用例报相同的两个SetText节点歧义：chat_input在可见聊天区，history_search在负x坐标的关闭侧栏。启动焦点日志中chat_input实际Focused=true，不能把定位歧义称为焦点/角色/流式生产回归。永久测试改为定位既有chat_input标签，保留原发送、身份请求、流式和附件断言；没有隐藏或删除搜索框来让唯一性假设继续成立。

新增大字体用例在清空后直接assertExists第三条会话失败。针对“查询未清空”与“LazyColumn未组合屏外行”的区分，追加真实EditableText为空断言，再通过真实列表滚动到目标并断言显示，保留同名会话ID、selected、busy及删除回调断言。不是增加等待或重试点击；下一次远程结果将验证这一判断。

原始XML确认真实SQLite标题搜索→打开目标→关闭重开重置、以及原生角色继承用例通过，实际搜索键盘/匹配结果PNG已审阅：列表与设置在键盘上方，筛选目标正常展示。仍不把部分通过当作完整门禁成功；修正测试后重新执行完整Android检查，无本地Android/TS构建或测试。
