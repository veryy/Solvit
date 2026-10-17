# 聊天阅读历史与回到最新消息（2026-10-17）

承接build94，检查现有聊天交互发现：手势向上阅读会暂停自动跟随，但没有回到最新位置的可见入口。当前生产代码尚未修改；先扩展现有真实ChatScreen/Agent/Provider流式设备用例，在同一未结束的请求里向上翻阅、继续接收、点击返回、再接收，并检查按钮真实边界在聊天区和输入框上方。存储/Provider仅用现有可控测试接口，不用预绘制截图或直接指定列表坐标代替实际手势。

参考锁定Operit dbf71916 `ui/features/chat/components/ScrollToBottomButton.kt:96–150,221–264`：读者上翻暂停跟随、底部重新跟随、独立可访问返回按钮。其app/src/test没有对应滚动按钮测试。DSH d7432673 `packages/client/ui-chat/src/client/chat/use-scroll-follow.ts:29–124` 区分读者意图与原生滚动，`tests/chat-view.client.spec.tsx:3688–3710` 明确验证离开底部后新块不拉走读者、点击返回再跟随；3641附近覆盖新用户消息触底，4045附近覆盖接近底部不额外拉动。Android继续用Compose现有LazyListState/Coroutine，不移植浏览器测量或引入依赖。Operit LGPL-3.0、DSH MIT；只采用行为与验收场景，未复制实现。

本阶段是补齐缺失的导航操作，不以源码推测宣称现有Provider/角色/流式发生新缺陷。旧完整流式、键盘、诊断断言保留。待显式远程chat-stream诊断取得原始结果后再实施；最终仍需完整远程门禁、实际截图和独立可下载APK。
