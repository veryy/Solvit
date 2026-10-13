# 0.22.4 候选：插件资料与角色规则分层

源码 `7f2623c`，build40 / host API13。不是正式Release，releases/latest仍为0.21.3。

## 改动

OpenAI及通用非签名路径不再把插件Hook资料拼进system。记忆、Skills等资料附在当前用户消息的参考数据区；每个模型步骤刷新，保留真实请求，不累积旧资料，不写入用户聊天原文，不拆开工具调用/结果。角色和用户规则保持原有system来源。原生签名适配继续使用独立runtimeContext。

不能保证模型遵守角色或免受提示注入。真实模型、用户网关可见流式、工具选择与真机体验尚未验收；这次只证明实际请求结构和受测运行链。

## 验证

[原始HTTP边界复现](https://github.com/veryy/Solvit/actions/runs/38339666340)明确失败于插件资料被提升到system。[完整Android运行](https://github.com/veryy/Solvit/actions/runs/38340728439)构建成功，解析XML为310单元测试，0失败/错误/跳过，包含实际Agent→OpenAiProvider→HTTP序列化、资料刷新、预算保护、原生适配、记忆重启/删除/停用/角色限制和Skills按需加载。Lint和固定签名通过。模拟器XML为48个测试，0失败/错误/跳过，完整运行成功。覆盖真实Android记忆、角色设置/新对话继承、流式显示、HTML页面/PTY和旧签名APK覆盖升级保留配置与聊天。受测角色回答为fixture，不是真实模型。

[APK产物](https://github.com/veryy/Solvit/actions/runs/38340728439/artifacts/11786941206)需要登录GitHub下载，23,504,992 bytes，SHA-256 `30d68473b14b08d1f1809b7e155f5cde5fe068dcf80155103d8b13968e421071`；ZIP和APK CRC通过，8个内置插件资产与源码一致。CI证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

此前首轮修复的记忆/Skills旧断言仍要求system资料而失败，迁移后保留全部生命周期断言，并增加system不含资料及SQLite用户原文不被改写的检查。详细上游、复现和范围见[阶段记录](plugin-context-trust-stage.md)。无本地Android或TS构建/测试。

## 未完成范围

公网搜索发布门禁、真实角色/流式/工具及记忆质量、真机操作、完整JS Agent/Provider/存储替换仍未完成；终端长提示和控件密度仍需收整。插件市场协议和包未改，无需另行部署。完整路线见[产品进度](product-roadmap.md)。
