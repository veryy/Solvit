# Skills与MCP页面交互打磨

基线0.43.9已正式发布，本阶段尚未交付。上轮交付属于progress：main为70ceda57、v0.43.9完整Actions39004170518成功，公开APK实际下载与正式产物一致。继续完整UI目标，不将环境页交付视为全部完成。

## 上游依据与边界

2026-10-17 fetch-upstreams.py --verify-only核对全部9个锁定checkout。Operit dbf71916fae9750cfdc9f9a774f5a0fee56633fb的ui/features/packages/screens/SkillConfigScreen.kt:225–281按名称/说明过滤列表，并区分没有技能与没有匹配结果；MCPConfigScreen.kt:315–336将连接列表检索与配置/详情分开，导入另有独立流程。McpConfigImportParserTest.kt及McpServerRegistrationTest.kt覆盖具名服务器及导入处理；未发现对应独立页面交互测试，不将其视作Solvit页面验证。

DSH d743267388641bc76f17c45ce8b4c231aed1d32c的packages/client/ui-skill/src/client/SkillRow.tsx及tests/skill-row.client.spec.tsx体现紧凑摘要、按需展开、键盘可用及失败摘要；ui-settings-shell/tests/apply.client.spec.ts覆盖可用服务与注册/卸载，preset/agent-preset/skills/cordis-plugin-development/references/ui-plugin.md要求继承主题、本地化与资源清理。采用交互原则，适配Solvit现有HTML/JS插件，宿主仍只负责安全桥和生命周期。已读DSH MIT、Operit LGPL3许可证；不复制上游组件、不新增依赖。

## 本轮验收

Skills保留两列紧凑卡片，合并搜索区、将刷新移到搜索旁，导入是明确主要操作；清空搜索与无匹配状态清楚。详情/预览与库列表分层，原资源选择、原始正文、系统文件选择、替换及删除确认保留。MCP支持页面内检索具名连接，编辑/测试在外层，启停/删除按需展开；当前传输方式明确，高级头/环境/目录默认折叠，所有原始表单字段、原生确认、已有认证的留空保留和多连接数据不变。

以真实QuickJS→WebView→宿主与系统触摸验收搜索、清空、卡片操作、详情返回、关闭重开、深色/大字/键盘；保留完整SAF导入/导出、替换保护、加密配置和原生确认用例。现有官方1.2.0精确字节包可保持状态升级；权限扩张版本仍要求显式更新，修改包/卸载选择不恢复。远程生成资源后完整Actions、固定签名APK、公开下载与实际截图核对才算交付。真实模型、账号任务、ARM64及完整路线继续保留。

首轮远程生成39006983050通过；设备39006984782为Skills通过、MCP失败，原始runner为2项/1失败，artifact12091526667（5117728字节，SHA256 e76ba07df90be53921c7e71a61fa47eb0550bb84a906ac07797819a85f52e1fc）摘要、CRC、原始runner/logcat已核对。失败在McpPageUiTest:72的真实过滤检查；之前原双连接布局检查通过。日志含Uncaught TypeError: Cannot read properties of null (reading classList)，随后field-query为null。重排菜单先将删除按钮移入尚未挂载的details，再从document检索同按钮，导致初始化中止；缓存按钮引用并在移动前设置样式，保留原检查和超时。

MCP窗口原有FLAG_SECURE令系统截图为黑色，这是预期保护；实际查看黑图后，新增仅在此用例已重置的虚构连接上截图的测试辅助函数，截图前后断言保护开启，临时清除仅持续一次截图并finally恢复；生产代码不提供关闭入口。截图中的表单均为空认证、列表无令牌。

正式发布仍需线上Skills包同步。旧部署入口/private/tmp/solvit-deploy-browser-market.py及solvit-server-exec.py已不存在；只读SSH因没有已知host key而未连接，未降低校验。GitHub仓库仅配置签名密钥。当前具备源码与远程Android验证能力，继续完成候选及下载产物；恢复部署能力后再过现有live Skills精确字节门禁，不把生成成功写成正式发布。
