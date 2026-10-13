# 0.23.0 候选：JS 调度聊天 Agent 步骤

源码 [01e481a](https://github.com/veryy/Solvit/commit/01e481a0e16896d08fa2abe926342ac412094fc0)，build45/API13。包内JS通过可替换驱动服务发起每次模型请求、逐个执行工具、结算结果并请求下一步。Android必须使用这个服务；宿主命令状态机校验顺序、授权、资源及持久记录。文本/思考直接实时发给UI，不等待JS整轮结束。

[完整Android Actions](https://github.com/veryy/Solvit/actions/runs/38357742717)成功，实际XML329单元/48模拟器，均0失败/错误/跳过；Lint、固定签名APK和覆盖升级配置/聊天保留通过。真实QuickJS、HTTP工具回传、非法跳步、取消、原生转录保留、工作区失败→修复→验证及Android可见流式回归通过。测试Provider为受控fixture，不能证明真实模型效果。

[下载APK产物](https://github.com/veryy/Solvit/actions/runs/38357742717/artifacts/11794325405)（需登录GitHub）。23,522,373 bytes；SHA-256 `b52fa0c3fece0b3a3186c8c03e15ae8d23af5ae8174c8c686fb5bca64dde3403`。ZIP/APK CRC及8个内置插件、JS驱动源码资产核对通过。固定证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

仍只支持受信任内置Agent脚本，第三方Agent插件协议未开放。上下文、Provider和存储仍在宿主；完整JS内核、真实模型/真机与实际任务验收未完成。公网搜索发布门禁仍失败，未发布正式Release。详情与实际截图见[JS Agent阶段](javascript-agent-stage.md)，[完整路线](product-roadmap.md)继续保留。
