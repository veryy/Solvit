# API13：插件自带的交互终端页面

业务页面在插件的 `ui/index.html`、CSS、JS 中实现，Android 只提供离线
WebView、主题/语言、确认窗口和可撤销的 PTY 能力。完整示例是
`builtin-plugins/terminal`，终端显示使用离线 xterm.js 5.5.0，不需要 CDN。

清单声明 `apiVersion: 13`、`permissions: ["workspace", "shell"]`、
`nativeServices: ["terminal.pty"]` 和带 `entry` 的 `pages`。QuickJS 的
`pages.main` 返回页面标题与初始控件树；HTML 页面由通用容器加载。原有
五个模型终端工具保持独立：页面权限不会注入工具或 Hook。

HTML 调用 `solvitHost.postMessage({type:"ready"})` 后，宿主通过
`window.solvitPageUpdate(state)` 提供 token、locale、theme 和 view。发送的
实际数据必须为 JSON 字符串。终端请求格式：

```js
solvitHost.postMessage(JSON.stringify({
  type: 'terminal', token: state.token, id: 'request_1',
  operation: 'open', arguments: {shellId: '从 snapshot 获取的 Shell ID'}
}));
solvitHost.onmessage = message => {
  const reply = JSON.parse(message.data);
  // {type:'terminalReply', id, result} 或 {type:'terminalReply', id, error}
};
```

支持 `snapshot({})`、`createShell({name})`、`open({shellId})`、
`attach({id})`、`status({id})`、`input({id,text})`、
`resize({id,rows,columns})`、`interrupt({id})`、`close({id})`。
创建、打开和连接已有终端由宿主弹出确认；确认后本页面可持续发送输入。
脚本请求不能携带 chatId/workspaceId/owner/confirmed。身份由宿主捕获，
原生边界检查当前聊天和绑定。状态/输入只接受已授权的会话 ID。

下载的页面必须启用插件并持有 workspace/shell 授权，只能连接自己的
插件终端。不可变的人工管理页可在 AI 插件关闭时使用，并可在另一次
原生确认后查看/控制当前聊天中 AI 创建的终端；它不会启用模型工具。
共用工作目录的另一聊天仍不能访问这些终端。

`outputOffset` 是保留输出首字符的 UTF-16 绝对位置。将上一次读到的位置
与它比较，只将增量写入 xterm；若输出已回收则明确提示并重放保留部分。
输出最多 65536 个 UTF-16 单元，不从代理对中间截断。尺寸为 2–300 行、
2–500 列，发送到真实 PTY。原始输入不自动补换行，HTML 快捷命令栏会补。

关闭页面撤销它的控制能力，已授权的手动 Shell 保留，直到明确关闭、
执行身份变化或应用退出；关闭页面不等于结束已运行的命令。停用/卸载
下载插件会回收其终端。模型回合取消沿用既有清理规则。此阶段没有
Root PTY、Linux 发行版、SSH/SFTP、进程级脚本隔离或完整 DSH Agent 生命周期。

本页描述协议，不代替运行证据；真实 Android/HTML 回归及 APK 结果见
[终端页面阶段](terminal-plugin-stage.md)。
