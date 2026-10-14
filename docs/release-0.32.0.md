# Solvit 0.32.0：编辑消息时带回附件

- 编辑旧问题并创建分支时，图片和文件一起回到输入框，可以预览、移除、添加，再发送。只有附件、没有文字的问题也能编辑。
- 新输入框使用独立附件副本，移除它不会删除原对话或之前历史中的文件；复制失败时撤回新分支并保留原对话。
- 移除等待队列里的消息时，回收没有其他使用者的附件；已保存消息、输入框和其他待发消息引用的文件会保留。
- 应用启动时，根据数据库引用清理上次未发送附件和中断导入的遗留文件。仅处理附件服务自己的文件，不跟随链接或清理未知目录；读取记录失败时不删除文件。
- 附件编辑说明支持中文、英文、日文、韩文、西班牙文和法文。

用法：打开用户消息右侧的操作菜单，选择「编辑并分支」。新分支输入框会显示文字和附件；点击附件可预览，点击附件上的关闭按钮可移除，然后正常发送。

未发送输入框和等待队列仍不支持进程死亡恢复，重启后遗留文件会清理。已发送附件仍保存并可重开查看。PDF、工作区导入、全局存储配额仍待实现。Linux 环境延续0.31.1；ARM64真机及真实模型完整任务仍未验收。

## 实际发布验证

正式源码[f690c5f](https://github.com/veryy/Solvit/commit/f690c5f)，[Actions38569876572](https://github.com/veryy/Solvit/actions/runs/38569876572)的build、verify-ui、verify-plugins、verify-search、verify-linux和release六项全部成功。

实际完整下载正式unit artifact11897437426和UI artifact11898490585，ZIP CRC/XML核对452单元、59应用/Web/PTY设备测试，零失败/错误/跳过。真实Android用例覆盖编辑纯附件问题、独立副本、文件预览与触摸移除、实际HTTP文字/图片字节、原对话和重开附件保留；实际截图确认移除后只剩图片卡片，编辑文字与发送按钮完整。固定签名和旧版覆盖升级门禁通过。

正式插件日志核对113 SDK、5市场测试零失败，线上HTTPS MCP/Skills成功；公开搜索日志确认中英文/版本查询正文和主引擎故障区域回退成功，模型回答引用未验收。生产Linux任务日志为3/3 API35 Android用例成功、零失败/跳过，严格设备日志门禁确认Python3.12.3、Node24.21.0/npm11.19.0及Java17，执行与共享PTY通过；本轮未下载完整Linux探针归档，不以日志宣称该归档完整校验。

公开APK实际下载后与正式artifact11897667310逐字节一致，25,296,932字节，SHA256 `4b396a4f60471df96695bf11c3287e5726fb8eecd665dfdf60950f086de72e58`，artifact和APK的ZIP CRC通过。正式apksigner核对固定证书SHA256 `f076abf86de542063f8655abf9021cb88b642ab9b664cafc46c0f16b4303bcc3`。

公开对应源码实际下载后与artifact11897546234内源码ZIP逐字节一致，1,072,439字节，SHA256 `cf0d9767a71e8a52199f9039a86843fa9b14f4b539dce388733e235469359a8e`；artifact/源码ZIP CRC均通过。

[下载APK](https://github.com/veryy/Solvit/releases/download/v0.32.0/solvit-debug.apk) · [对应源码](https://github.com/veryy/Solvit/releases/download/v0.32.0/solvit-linux-sources.zip)。ARM64真机、真实模型完整任务及所有Linux/Node扩展兼容性仍未验收；不能把HTTP夹具及模拟器替代真实模型效果证明。
