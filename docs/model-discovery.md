# 获取模型列表

在“设置 → 模型配置 → 模型与连接”填写 API 基础地址和 API Key，点击“获取模型”，搜索后点击模型名称，再保存配置。获取列表不要求事先填写模型名称；也可以继续手动输入。

默认请求 `GET <API 基础地址>/models`，例如基础地址 `https://api.example.com/v1` 对应 `https://api.example.com/v1/models`。标准响应参考 [OpenAI List models](https://developers.openai.com/api/reference/resources/models/methods/list)。本地服务允许 loopback HTTP，远程地址要求 HTTPS。

如供应商提供单独的订阅或模型列表链接，将完整 URL 填入“模型列表 / 订阅地址”。当前支持以下 JSON 格式：

```json
{"data":[{"id":"model-a","owned_by":"provider"}]}
```

```json
{"models":["model-a",{"id":"model-b","name":"显示名称"}]}
```

```json
["model-a","model-b"]
```

列表去重并排序，选择后实际请求使用模型 ID。获取成功只证明服务返回了模型名称，不保证所有模型都支持聊天、工具调用或当前账户有调用额度。

默认向同一站点发送当前 API Key；独立站点的列表不会收到该密钥。若这个列表接口确实需要同一密钥，可以勾选“不同站点的列表也使用此 API Key”。不会跟随重定向发送凭据，遇到重定向需填写最终链接。链接里的订阅 token 与 API Key 都加密保存在本机，并包含在密码加密配置备份中，不进入 Android 保存状态或日志。

连接、地址或密钥变化会取消之前的列表请求并清空旧列表。可手动取消加载；404、认证失败、限流、格式不兼容和空列表都有提示。响应最多 2 MiB、最多 2000 条原始条目，单个模型 ID 最多 256 个字符。不支持从网页 HTML、YAML 或其他未定义的订阅格式猜测模型名称。

实现通过 `ModelCatalogService` 服务接口与独立 Provider 连接，UI 不直接拼接 HTTP 请求。真实 HTTP 测试覆盖默认接口、认证、独立订阅、凭据隔离、重定向、响应错误、取消和取消后复用；真实服务账号和真机仍需验证。
