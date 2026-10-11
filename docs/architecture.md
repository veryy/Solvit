# Solvit 第一阶段架构

## 参考研究与取舍

2026-10-10 读取以下官方文档和源码。仅借鉴设计，Solvit 代码独立实现，不复制 Operit 或 Cordis 运行时。

| 参考 | 最值得借鉴的设计 | Solvit 的落点 |
| --- | --- | --- |
| [Operit AIService](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/AIService.kt) | 统一模型接口、流式回复、取消与资源释放 | 不含 Android Context 的 ModelProvider + Flow；Coroutine 取消连接 |
| [Operit AIServiceFactory](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/AIServiceFactory.kt) | Provider 创建与网络连接管理集中化 | 网络完全封装在 provider-openai 插件，UI 不选具体实现 |
| [Operit ChatViewModel](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/ChatViewModel.kt) | ViewModel、Coroutine、Flow、聊天及工具体验 | 保留原生流式聊天与多会话；避免第一版引入复杂服务与工具依赖 |
| [Operit AndroidPermissionLevel](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidPermissionLevel.kt) | 基础、无障碍、调试、管理员、Root 能力分层 | ToolDescriptor 声明权限及敏感确认；授权接口默认必须显式允许 |
| [DSH Architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md) | Everything is a Plugin；Definition / Provider / Consumer 能力缝隙；组合启动 | Core 只有契约和注册机制，模型、存储、Agent 和聊天服务都由插件提供 |
| [DSH Cordis Primer](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-primer.md) | inject 声明依赖，Context 查服务，注册是可撤销 effect | requires / provides、PluginContext、逆序 disposer 与 SupervisorJob |
| [DSH Tools](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/tools.md) | 发现与执行用同一注册表，执行前权限门禁，模型 schema 与 host 回调分离 | ToolRegistry.descriptors / execute；ToolDescriptor 不暴露执行函数 |

DSH 的完整配置覆盖、作用域树、waterfall 中间件和持久事件日志值得长期研究，但不属于第一版需要的最小机制。Solvit 暂用单 Provider 的显式服务键和简单观察事件；不模拟整个 Cordis。

## 模块关系

```mermaid
flowchart TD
  UI[Compose UI / ChatViewModel] --> C[ChatService interface]
  C --> R[ChatServicePlugin]
  R --> S[ChatStore interface]
  R --> A[AgentRuntime interface]
  A --> M[ModelProvider interface]
  M --> O[OpenAiPlugin]
  S --> SQL[SqliteStoragePlugin]
  Boot[Application composition] --> PM[PluginManager / PluginRegistry]
  PM --> R
  PM --> O
  PM --> SQL
  PM --> T[ToolsPlugin / ToolRegistry]
```

`app` 是唯一组合根，可以引用具体插件；聊天 ViewModel 和 UI 只消费核心 ChatService、ModelConfig 和 SettingsStore 契约。Core 无 Android、HTTP 或 SQLite 依赖，不承担实际推理。

## 插件和依赖

插件声明稳定 id、requires 服务集合、provides 服务集合。`load` 只登记插件，`enableAll` 反复选择已满足依赖的插件，首次执行 initialize 后调用 enable。加载顺序不决定执行顺序；缺失或循环依赖会给出明确错误，并回滚本轮已启用的插件。重复插件或服务键被拒绝。V1 未自动等待外部插件，也不替换已激活的单例服务。

Context 只允许消费已声明依赖及注册已声明服务。注册、监听、工具和网络资源须交给 `own`。停用时先运行 disable 钩子，再取消并等待插件协程，最后逆序清理效果。全部清理会继续执行并合并异常。启用失败同样回收 Context。

状态为 LOADED → INITIALIZED → ENABLED → DISABLED → UNLOADED，停用后可以 enableAll 重新激活。仍被启用插件依赖的服务不能停用；shutdown 自动先关闭消费者再关闭提供者。资源不要在 initialize 中获取，否则无法由激活 Context 管理。

启动只组合内置的可信 Kotlin 实现，所有代码运行在同一进程中。这是模块化和生命周期管理，不是第三方安全沙箱。第三方动态加载与插件市场延期。

## 最小扩展示例

```kotlin
interface Greeting { fun greet(): String }
val greeting = ServiceKey("example.greeting", Greeting::class)
class GreetingPlugin : SolvitPlugin {
    override val id = "example.greeting"
    override val provides = setOf(greeting)
    override suspend fun enable(context: PluginContext) {
        context.register(greeting, object : Greeting {
            override fun greet() = "Hello, Solvit"
        })
    }
}
```

消费者在 requires 中声明 greeting，在 enable 中调用 context.require(greeting)。`PluginManagerTest` 实际演示逆序加载、禁止停用依赖、卸载撤销注册、失败回滚与协程取消。要替换存储或模型，在组合根加载另一个提供同一 Services.Storage / Services.Model 的插件，不同时加载两个相同服务提供者。`ChatServiceTest` 使用 MemoryStore 验证聊天运行时不依赖 SQLite。

## 消息、事件与取消

ChatService 在请求前持久化用户消息，通过 ChatStore 历史组装模型请求。一次 turn 仅执行一次 AgentRuntime；AgentRuntime 默认代理 ModelProvider.stream。回复片段是瞬态 Flow，完整回复在 Done 时写入 SQLite。没有 Done、网络失败、额度截断、取消分别结算为失败或中断；部分助手回复不会进入下一次上下文。

SQLite 将消息插入与会话标题/时间更新放进同一事务；参数绑定避免 SQL 拼接。会话删除通过外键级联。可观察 StateFlow 触发查询。CoreEvent 是尽力而为的进程内通知，不是消息持久化依据，不用它做授权决策或重放。UI 使用 ChatStore 的 Flow 获取记录。

同一 ChatService 只允许一个进行中的 turn；删除在 turn 期间被拒绝。ViewModel 生存期拥有生成 Job，停止按钮及 ViewModel 销毁都取消它；取消通过 OkHttp Call.cancel 中断 SSE 读取，结算使用 NonCancellable 确保资源和状态收尾。启动组合使用 Application Scope，配置变更不会重新创建插件。

## 工具与权限

ToolRegistry 提供描述、JSON 输入 schema、权限集合与确认要求。执行前必须通过 ToolAuthorization，再检查取消与注册有效性。授权拒绝不调用工具实现；卸载 disposer 撤销能力。第一版生产环境没有任何实际工具，也没有模型驱动的调用循环，所以不请求敏感 Android 权限。

后续 Android 授权适配器要同时检查运行时权限、无障碍/调试等特殊授权、每次敏感动作确认和作用域策略。用户确认不能替代系统权限，也不能仅凭声明就认为能力已经授权。同进程插件仍可绕过接口访问 Android API；真正第三方隔离需独立进程或受限协议。

## 演进方向

1. 多 Provider 注册与用户 profile，独立工具/模型选择，持久化组合配置。
2. 有界 Agent Loop、结构化工具调用、执行预算和授权 UI。
3. Skills、MCP、Memory 作为服务提供者；扩展工具 schema 加入模型上下文。
4. 注册 UI 设置卡片和能力展示，逐步增加 scoped registration。
5. 需要重放和恢复时引入版本化持久事件日志，区分事实与瞬态流。

当前设置只组合一个兼容模型、系统提示词与内置聊天 Agent；高级组合页面延期。长期扩展仍沿着契约/提供者/消费者分离，不把工具逻辑塞回 UI 或 Core。
