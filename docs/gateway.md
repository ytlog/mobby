# 网关接入

在「网关设置」中选择 Claude Code 或 Codex，填写协议、网关地址、模型名称、API Key，然后保存当前配置。两个 Agent 分别保存；没有配置时仍能使用 Shell。API Key 可留空以连接无鉴权网关。界面保存代表本地配置保存成功，不代表远端鉴权已通过。

| 选项 | 上游接口 |
| --- | --- |
| Chat Completions | `/chat/completions` |
| Responses | `/responses` |
| Messages | `/messages` |

地址可为基础路径或完整接口路径，例如 `https://host/v1`、`https://host/v1/messages`；无路径时自动使用 `/v1`。自定义路径会保留，因此 `https://host/api/v2` 会追加所选接口。此处 completion 指 Chat Completions，不包含旧式纯文本 `/completions`。

## 运行方式

Claude Code 本身使用 Messages，Codex 使用 Responses。每次任务由内置 Node.js 启动临时本地网关，再以参数数组启动原来的 CLI；上游可选三种协议。服务仅监听 `127.0.0.1` 随机端口并要求随机令牌，任务结束关闭服务。Shell 不接收网关凭据。没有添加跳过 CLI 沙箱和权限检查参数。

相同协议转发原始请求与流式响应，覆盖模型为用户设置的名称。跨协议转换文本、system 指令、函数工具定义、工具调用及结果，并支持 Codex 自定义文本工具（如 apply_patch）的函数封装、命名空间工具的名称映射和还原。跨协议调用上游 `stream:false`，收到完整响应后构造原生 SSE 事件，因此第一条回复需要等待上游生成完成。

上游统一发送 Bearer 认证；Messages 同时发送 `x-api-key` 与 `anthropic-version`。没有配置真实地址前不会执行远端模型调用。HTTP 地址可用于局域网，但界面会提示密钥及内容明文传输；HTTPS 保持正常证书校验，不跟随重定向携带密钥。

API Key 和配置使用 Android Keystore AES-GCM 加密存储，明文仅在任务环境/内存中传递，不写入 CLI 配置文件、项目或日志。Agent 仅收到临时本地令牌。上游错误响应仅保留 HTTP 状态和诊断提示，不回显可能含密钥的响应正文。

## 范围和限制

- 跨协议当前面向文本编码任务；图片、音频、远端托管工具等未适配时明确报错，不静默丢弃。
- 原生协议私有推理内容不能跨供应商转换；跨协议不转发 hidden reasoning / encrypted reasoning。同协议保留。
- 不支持跨协议 `previous_response_id`，需要 CLI 携带完整会话；Responses 原生请求可以透传。
- 禁用 Codex 自动远端压缩与默认 web_search，跨协议桥接没有伪造压缩响应。长任务可能达到模型上下文上限。
- 桥接请求/转换响应限制 16 MiB，每次远端请求超时 5 分钟；整个任务沿用 10 分钟限制。
- CLI 的更新、插件等其他网络能力不属于模型协议桥接；真实网关的模型能力、工具支持与鉴权需要实际联调。

## 验证

运行 `node --test runtime/gateway-tests/bridge.test.cjs`。14 项测试覆盖 6 个 Agent 原生协议/上游协议组合、工具 ID 与结果、命名空间名称还原、取消请求、Codex 自定义工具、URL 规范化、SSE 透传、拒绝不支持的输入、鉴权错误脱敏和输出截断状态。

参考：[Codex provider 配置](https://learn.chatgpt.com/docs/config-file/config-reference)、[Responses 事件](https://developers.openai.com/api/reference/resources/responses/streaming-events)、[Claude Code 网关配置](https://code.claude.com/docs/en/llm-gateway-connect)。

2026-09-20 主机联调：Claude Code 2.1.112 与主机现有 Codex 0.154.0-alpha.6.2，各自通过三个本地模拟上游，六种组合的文本回复全部通过；再进行实际工具读取临时文件并回传，六种组合全部通过。使用独立临时 HOME 和虚假测试密钥，未访问真实模型网关。手机内置 Codex 为 0.155.1，手机已更新安装并持久化网关配置，重启后读取正常。真实网关 Messages 和 Responses 小请求均返回 HTTP 200（Responses 的 64 token 探测达到输出上限）；手机端完整 Agent 任务仍待验收。

可复用的 CLI 工具回传验收脚本：`runtime/gateway-tests/cli-smoke.cjs`，通过 `MDOER_TEST_CODEX` 与 `MDOER_TEST_CLAUDE_JS` 指定本机测试程序路径。
