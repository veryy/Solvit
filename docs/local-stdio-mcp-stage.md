# 本地 stdio MCP：实现与验收记录

起点：公开0.41.0 / `cd9c98c7`，Linux环境已可运行Python、Node/npm及Java。本阶段继续现有实现，不另造虚拟机。

## 实际阅读的上游

- DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`：`packages/mcp/mcp-client/src/transport.ts`、`connection.ts`、`tests/negotiation-lifecycle.spec.ts`。采用直接stdio、显式子进程环境、旧进程清理完成前不得启动新一代的约束。DSH使用Node MCP SDK；QuickJS没有Node进程API，不能直接引入该传输实现。
- Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`：`app/src/main/java/com/ai/assistance/operit/data/mcp/plugins/MCPStarter.kt`及`app/src/test/java/com/ai/assistance/operit/core/tools/mcp/McpServerRegistrationTest.kt`。其本地连接使用终端中的Node桥；注册运行连接与向AI暴露工具是两个不同步骤，必须分别验证。
- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)：UTF-8逐行JSON-RPC，stdout只承载协议，stderr独立；不能拿PTY的回显、换行转换和交互提示作为stdio。
- 已核对DSH MIT、Operit LGPL-3.0及上述文件头；本阶段独立实现Android宿主适配，不复制上游源码或新增Node/Cordis运行时依赖。

## 宿主适配

既有普通Shell的stdin用于EOF退出看门狗，程序stdin为`/dev/null`，不能兼任MCP输入。既有PTY保持交互终端用途。新增原生stdio通道，stdin、stdout、stderr与宿主生命期控制分别持有独立描述符；进程组管理和回收不依赖JSON-RPC输入。

子进程只接收显式环境，不继承应用进程环境。调用者必须是宿主构造的启动计划，JS不获得JNI句柄或任意原生启动入口。子进程及其普通后代在会话关闭、启动失败或宿主控制管道关闭后回收。与已有Shell一样，同一Android UID的程序不是对恶意代码的安全隔离；主动逃离进程组的程序不属于此清理保证。

## 后续接入与验收

先远程验证真实管道、分离stderr、环境隔离、启动失败、并发关闭、进程组及后代回收，再接Ubuntu启动计划、插件授权/绑定、逐行边界与资源限额、多连接JS页面、MCP协商和AI工具路由。当前内部原语不代表本地MCP已向用户交付。

正式交付需要远程Node/Python MCP进程及取消/停用回收测试、旧HTTPS连接回归、完整Actions和可下载APK。真实模型与ARM64真机验收仍受既有条件限制；不能用确定性Provider替代真实模型结果。
