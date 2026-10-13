# 0.22.8 候选：超限工具结果清理登记资源

源码 `7122fec3bd6ad2648f0d775792b1f1ab109e2941`，build44/API13。原工具结果在注册器释放取消回调后才被Agent判定超限，导致失败调用已登记资源未清理。现将既有256 KiB UTF-8检查移到注册器成功返回前，超限沿异常路径逆序取消登记资源并回传安全的RESOURCE_LIMIT。精确边界结果可用，用户停止与工具超时语义保留。

[完整Android Actions](https://github.com/veryy/Solvit/actions/runs/38353550176) 成功，实际XML324单元、48模拟器测试，均0失败/错误/跳过；Lint、固定签名、APK和覆盖升级配置/聊天保留通过。两层[红色复现](https://github.com/veryy/Solvit/actions/runs/38353211619) 已观察，修复后的[聚焦回归](https://github.com/veryy/Solvit/actions/runs/38353622842)成功。新增3个回归检查UTF-8边界、逆序清理和Agent下一步收到安全失败，测试Provider为受控fixture。

[下载APK产物](https://github.com/veryy/Solvit/actions/runs/38353550176/artifacts/11791399713)（需登录GitHub）。23,521,972 bytes；SHA-256 `1db1fd2d776e25d576832c71bc4572a10c203ae3073d052a794ddd12a84a44ab`。ZIP/APK完整性及8个内置插件资产核对通过，固定证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

只处理按契约登记的资源，不能回滚已发生副作用或关闭未登记进程。真实模型与真机、完整JS Agent/Provider/存储替换和任务完成验证仍未完成。公开搜索发布门禁仍失败，本版为候选。细节见[结果接受阶段](tool-result-boundary-stage.md)与[完整路线](product-roadmap.md)。
