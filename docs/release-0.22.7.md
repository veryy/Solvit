# 0.22.7 候选：工具超时回传，保留用户停止

源码 `6507d9089a721d768a3b1e426c20572b0cf50803`，build43 / API13。修复单个工具达到注册器60秒期限时被当成整轮聊天取消：现在记为 `FAILED/TIMEOUT`，清理工具登记资源，回传同一调用ID的安全失败信息，再进入模型下一步。失败建议核对操作是否已产生影响，不自动重复执行。用户停止和外层期限仍取消整轮，不继续请求模型。

[完整 Android Actions](https://github.com/veryy/Solvit/actions/runs/38351266635) 成功。实际 XML 321 单元、48 模拟器测试，0失败/错误/跳过；Lint、固定签名及APK通过。已先观察远程红色复现，再核对4个新增回归成功：工具自身期限、资源清理、外层期限与实际Agent失败回传/用户停止。

[下载 APK 产物](https://github.com/veryy/Solvit/actions/runs/38351266635/artifacts/11790894418)（需登录 GitHub）。23,521,972 bytes；SHA-256 `621ddc4bb31c6bf0132dfbe93c95075b46a27f9d0d7a2c0d18d187e5487c53bb`。APK/ZIP完整性与8个内置插件资产核对通过。固定签名证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`，数据结构与市场/插件协议未改。

真实模型和真机尚未验收；本次Agent回归使用受控Provider。60秒为协作式期限，不承诺所有原生操作或清理的绝对墙钟上限。公开搜索本轮英文/中文来源正文通过，最新版遇验证页、区域回退失败，正式发布门禁仍失败；不把候选当作正式Release。详见[工具期限阶段](tool-deadline-stage.md)、[公网复核](browser-search-stage.md)与[完整产品进度](product-roadmap.md)。
