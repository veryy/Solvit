# 流式富文本修复阶段

当前角色/流式真实模型质量未验收。本阶段只修复可重复的展示缺陷：`MarkdownBody` 在生成中改用普通Text，结束后才使用既有富文本库，导致模型尚未结束时原始粗体标记和代码围栏显示在聊天气泡里。不会把此修复称为用户网关流式或角色问题已经解决。

## 实际参考

读取固定Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` 的 `ui/common/markdown/RenderBatchCoordinator.kt`、`StreamMarkdownRenderer.kt`、`RenderBatchCoordinatorTest.kt`、`MarkdownPlainTextRendererAndroidTest.kt` 与 `StreamMarkdownPluginTest.kt`；采用内容接收与渲染分开、合并刷新且保留最后变更的原则。读取DSH `d743267388641bc76f17c45ce8b4c231aed1d32c` 的 `agent-loop/src/assistant-stream.ts`、`tests/request-reconstruction.spec.ts`、`tests/system-prompt-projection.spec.ts` 与 `system-prompt/src/index.ts`；上下文和流事件仍需独立验收。本阶段未复制Operit源码或引入Cordis。

既有 `com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0` 无新增依赖；核对其v0.30.0提交 `fe32d3b217003891072429d7bd0e467d52ce3eba` 的M3入口、Compose实现及 `app/src/screenshotTest/kotlin/com.mikepenz.markdown.ui/SnapshotTests.kt`。该版本在Composable中解析完整字符串，不能直接每16ms重新解析全部长回复。

## 复现与修改

原真实聊天用例保持实际ChatService/Agent/ViewModel/Compose，确定性Provider首先输出 `**live-first-token**`，随后等待测试明确放行。未结束时要求格式化后的 `live-first-token` 可见，原始带星号文本不存在；原长回复、键盘上方末尾可见、Done后诊断与收尾断言保留。

[红色运行38326968234](https://github.com/veryy/Solvit/actions/runs/38326968234)安装真实APK后失败，8秒内未找到格式化文字，1个用例失败。修复提交 `d64f245` 让生成中与结束后都使用同一富文本库，流式最多每80ms刷新，期间变更合并；结束或中断立即使用准确结算文本。原有接收/展示队列及停止功能保留。

首次[修复运行38327419898](https://github.com/veryy/Solvit/actions/runs/38327419898)的粗体格式检查通过，随后原长回复末尾可见断言失败：富文本合并刷新后气泡高度变化，列表只跟随输入行和viewport变化。修复 `bf10a1a` 额外观察实际可见项的index/offset/size，保持用户主动滚动时停止跟随的既有策略；失败捕获同时包含AssertionError以保留实际截图。原完整运行38327401475因新代码提交被取消，不视为成功。最终[单项运行38327955205](https://github.com/veryy/Solvit/actions/runs/38327955205)实际1个用例10.846秒成功；完整Android运行38327875475的302个单元、Lint、APK及固定签名构建通过，全量模拟器仍在进行，尚不宣称完整成功。未在本地运行Android或TS构建/测试。真实模型、真机、复杂公式/表格/超长回复性能与完整插件化内核仍按全路线继续验收。

## 全量暴露的滚动回归

原完整运行38327875475失败：流式用例通过，终端首次输出等待超时，随后编辑旧问题分支用例持续重绘直到12分钟门禁中止；实际XML仅完成App20个用例，不能当成46个测试通过。新增观察含offset/index，主动或无障碍滚动会被误认为内容增长，强制回到末尾；修复4b1f860只跟随实际回复高度及viewport变化，并保留跨不可见状态的最后已测高度。终端失败原因尚未独立确认，不将其称为已定位。流式、分支、终端分别运行38329923726/38329926671/38329928935，完整运行38329902814，最终三项各1个用例通过：流式10.46秒、分支9.003秒、终端14.124秒。终端首次失败未独立证明根因，保持记录。

## 最终交付证据

[完整运行38329902814](https://github.com/veryy/Solvit/actions/runs/38329902814)全成功，实际XML核对302个单元、46个模拟器，无失败、错误或跳过；Lint、APK、固定签名与旧APK覆盖升级后的加密配置/聊天保留通过。最终全量流式用例9.75秒、旧问题分支4.637秒、交互终端8.632秒；原终端持久环境、输出、原始输入、中断和关闭检查保留。已核对[真实未结束回复截图](screenshots/0.22.2/chat-rich-stream-before-done.png)，末尾位于悬浮输入区上方且停止按钮仍显示。

[build38候选产物11781624309](https://github.com/veryy/Solvit/actions/runs/38329902814/artifacts/11781624309)为23,378,892字节，SHA-256 `47e8d3d2721e77f025f5355563069e0865877554f4ce98c98d582eb4606bfcf7`。实际下载ZIP/APK完整性与固定签名构建日志已核对。未发布正式Release：公网搜索门禁仍未通过。原生PTY界面、真实模型角色与工具质量、网关缓冲和真机验收仍未完成，整个目标保持。诊断中的队列显示时间不是实际屏幕帧提交时间，也不能据此判定服务端首字节或缓冲。
