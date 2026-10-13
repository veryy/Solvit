# 文档提交与远程验收

诊断过程中，只有验收说明变化的分支推送也启动完整Android门禁，且同分支的concurrency会取消仍在执行的有效UI门禁。Android push仅排除 `docs/**` 和 `README.md`；代码、配置、测试和工作流变化仍触发全套检查，pull_request及手动触发保留。

依据[GitHub官方工作流规则](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)，标签推送不评估路径过滤，因此正式标签继续执行完整发布门禁。本次工作流配置变更本身会触发远程Android；之后用实际文档提交检查该门禁不会被取消。不在本地构建Android或TS，不删减任何验收作业。

工作流变更 `7ef8b39` 已实际触发 [Android 38394289986](https://github.com/veryy/Solvit/actions/runs/38394289986)。本次仅文档提交用于核对不会取消该门禁；完成状态后续补录。

实际两次文档推送未启动Android，也未取消原门禁。配置回归构建成功，但角色请求页面在ChatLayoutTest.kt:459等待首个请求8秒失败；XML显示该角色用例失败、其他用例通过。这是后续回归失败，不改写0.24.1正式门禁全绿的事实。已增加同一角色场景10轮远程诊断，记录请求数、busy/ready、是否错误与结算状态，保留原8秒与请求/角色断言；未根据一次超时直接认定产品角色逻辑坏了或延长超时。

角色诊断源码`f500b8e`的[独立重复38396604840](https://github.com/veryy/Solvit/actions/runs/38396604840)成功，下载的产物包含10个独立result-N.txt，每个实际OK (1 test)，没有FAILURES。随后[完整Android38396554194](https://github.com/veryy/Solvit/actions/runs/38396554194)构建、Lint、签名/APK及完整UI成功；此次非产品/标签变更，插件/搜索及release按既有条件跳过。尚未稳定复现原始角色超时，保留请求阶段诊断，不宣称根因已修复；这些请求用受控Provider，不等于真实模型角色回答验收。

文档提交e47494a与7c0ad85实际没有生成Android运行，未取消7ef8b39门禁。正式标签规则依据官方文档，未为测试创建额外发布标签；已发布0.24.1的完整正式门禁保持全绿。
