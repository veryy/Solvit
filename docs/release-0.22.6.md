# 0.22.6 候选：真实模型验收入口与流式接收诊断

源码 `e040b04edf518ceb0d495ebfee2e70562bfd9ad3`，build42 / API13。最近请求诊断增加收到的非空文本段数及末段到达时间，逐字显示动画不算接收段。新增一次性模拟器真实模型验收工作流，使用实际 APK/SQLite/角色设置/QuickJS，检查默认、应用、切换、新建与重选对话角色，Provider 完成前可见的分段正文，以及 JS 工具随机结果被模型实际使用。

[完整 Android Actions](https://github.com/veryy/Solvit/actions/runs/38348358741) 成功，实际 XML 317 单元、48 模拟器测试，0 失败/错误/跳过；Lint、固定签名与 APK 通过。终端 Ctrl+C 回归改为等待前台子进程启动标记及中断后的提示符，保留后续真实命令断言。配置和聊天覆盖升级、HTML 页面及受控模型流式通过。真实模型验收类编译通过，但没有 Key，尚未执行；常规套件明确排除这一需真实凭据的类，不能把常规通过视作真实模型通过。

[下载 APK 产物](https://github.com/veryy/Solvit/actions/runs/38348358741/artifacts/11788784505)（需登录 GitHub）。APK 23,521,972 bytes，SHA-256 `e6178b9baf7c534b7326b55bf425f643ae0c77ba4e57c44b4106444d5e98b579`。ZIP/APK CRC 通过，8 个内置插件资产与源码一致。CI 签名证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`；插件包和市场协议未改，无服务器部署变更。

这仍是候选，正式 releases/latest 为 0.21.3；没有绕过公开搜索发布门禁。真实模型角色/流式/工具选择、搜索引用质量、完整工作区任务、真机与完整 JS Agent 内核替换仍待验收或实现。凭据设置、具体用例、上游阅读和验证边界见 [阶段记录](real-model-acceptance.md)，完整目标见 [产品进度](product-roadmap.md)。未进行本地 Android 或 TS 构建/测试。
