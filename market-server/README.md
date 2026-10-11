# Solvit 插件市场服务

Node.js 22+，无第三方运行依赖。只读目录 API 与插件包下载；发布由仓库维护者部署到服务器，不开放匿名上传。

```sh
npm test
npm start
```

默认只监听 `127.0.0.1:18765`，公开访问通过 HTTPS 反向代理。

线上地址：[Solvit 插件市场](https://solvit.207.57.126.73.sslip.io/)。运行在用户提供的服务器 `207.57.126.73`，独立 systemd 服务、非 root 运行用户、只读目录和 TLS。OpenResty 配置与证书续期 timer 位于 `deploy/`；当前部署目录 `/opt/solvit-market`，内部端口 `18765`。

- `GET /health`：健康检查。
- `GET /api/v1/plugins`：目录、权限、依赖、包字节数和 SHA-256。
- `GET /api/v1/packages/{id}/{version}`：下载对应包。

包格式是 UTF-8 JSON，包含 `manifest` 和 JS `source`。TS 发布前编译并打包为 JS。当前示例插件是 `dev.solvit.clock`，不依赖系统能力。客户端须验证来源、大小、SHA-256、API 版本、依赖和权限后安装；下载并不代表启用。

原生 App 安装和执行流程正在实现。市场目录仅包含实际提供的包，不将待实现的记忆、MCP 或 Skills 标记为可用。
