# 0.23.1 候选：可安装的 JS/TS Agent 驱动

源码 [90ec20d](https://github.com/veryy/Solvit/commit/90ec20dba7fc8ec923554c22c7cb30f0c5ef097a)，build46/API14。普通插件包现在可提供聊天步骤策略，通过默认关闭配置与agent权限注册；在全部会话中选择一个策略，多个同时选择明确报错。停用/卸载撤销入口并取消、等待完整轮次清理。宿主继续校验步骤、工具授权与执行事实；Provider、存储和协议消息投影仍由原生服务承担。

[完整Android运行](https://github.com/veryy/Solvit/actions/runs/38365614238)通过：实际XML335单元/48模拟器，均0失败/错误/跳过，Lint、固定签名APK和覆盖升级通过。[TS运行](https://github.com/veryy/Solvit/actions/runs/38361158771)通过84 SDK/2市场测试、typecheck及包字节一致性。市场已上线示例，API1–13目录保持原样，API14下载字节已核对。

[APK下载产物](https://github.com/veryy/Solvit/actions/runs/38365614238/artifacts/11796937877)（需登录GitHub）。23,522,373 bytes；SHA-256 `143fb7472787526d0d6f498c4fbb7e85ccc31beefa7dfc177e80b715a04efe7a`。固定证书 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。ZIP/APK完整性、8个内置插件与JS驱动源码资产一致性通过。

使用“插件 → 市场 → JS Agent 步骤示例”：安装后打开配置中的接管开关，再授权启用。更改配置须先停用；恢复默认关闭接管或停用即可。不是按会话选择，也不支持JS消息投影、Provider或存储替换。

首次模拟器工作区创建入口用例失败，改为等待按钮启用后完整重跑成功，原真实Shell/文件页断言保留。实际截图与全部失败/修复记录见[阶段说明](agent-plugin-stage.md)。真实模型和真机尚未验证；公网搜索发布门禁未通过，未发布正式Release。[完整路线](product-roadmap.md)继续保留。
