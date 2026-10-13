# 文档提交与远程验收

诊断过程中，只有验收说明变化的分支推送也启动完整Android门禁，且同分支的concurrency会取消仍在执行的有效UI门禁。Android push仅排除 `docs/**` 和 `README.md`；代码、配置、测试和工作流变化仍触发全套检查，pull_request及手动触发保留。

依据[GitHub官方工作流规则](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#onpushpull_requestpull_request_targetpathspaths-ignore)，标签推送不评估路径过滤，因此正式标签继续执行完整发布门禁。本次工作流配置变更本身会触发远程Android；之后用实际文档提交检查该门禁不会被取消。不在本地构建Android或TS，不删减任何验收作业。

工作流变更 `7ef8b39` 已实际触发 [Android 38394289986](https://github.com/veryy/Solvit/actions/runs/38394289986)。本次仅文档提交用于核对不会取消该门禁；完成状态后续补录。
