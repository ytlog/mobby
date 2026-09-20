# 网关接入

在「网关设置」中选择 Claude Code 或 Codex，填写协议、网关地址、模型名称、API Key，然后保存当前配置。两个 Agent 分别保存；没有配置时仍能使用 Shell。API Key 可留空以连接无鉴权网关。界面保存代表本地配置保存成功，不代表远端鉴权已通过。

| 选项 | 上游接口 |
| --- | --- |
| Chat Completions | `/chat/completions` |
| Responses | `/responses` |
| Messages | `/messages` |

地址可为基础路径或完整接口路径，例如 `https://host/v1`、`https://host/v1/messages`；无路径时自动使用 `/v1`。自定义路径会保留，因此 `https://host/api/v2` 会追加所选接口。此处 completion 指 Chat Completions，不包含旧式纯文本 `/completions`。

## 运行方式

Claude Code 本身使用 Messages，Codex 使用 Responses。内置 Node.js 以参数数组启动原来的 CLI。协议匹配且带密钥时优先使用 CLI 官方环境变量/provider 配置直连网关；Claude 的直连地址须采用 `/v1/messages` 后缀。跨协议、Claude 特殊路径或无鉴权配置才启动本地桥接。桥接服务仅监听 `127.0.0.1` 随机端口并要求随机令牌，任务结束关闭服务。Shell 不接收网关凭据。没有添加跳过 CLI 沙箱和权限检查参数。

原生直连由 CLI 处理请求与流式响应，使用配置中的模型。需要同协议路径适配时，桥接透传流式响应。跨协议转换文本、system 指令、函数工具定义、工具调用及结果，并支持 Codex 自定义文本工具（如 apply_patch）的函数封装、命名空间工具的名称映射和还原。跨协议调用上游 `stream:false`，收到完整响应后构造原生 SSE 事件，因此第一条回复需要等待上游生成完成。

原生直连使用 CLI 的 Bearer 认证配置；桥接模式发送 Bearer，Messages 桥接额外发送 `x-api-key` 与 `anthropic-version`。没有配置真实地址前不会执行远端模型调用。HTTP 地址可用于局域网，但界面会提示密钥及内容明文传输；HTTPS 保持正常证书校验；本地桥接不跟随重定向，原生直连的 HTTP 行为由 CLI SDK 管理。

API Key 和配置使用 Android Keystore AES-GCM 加密存储；启动器通过环境变量传递凭据，不主动写入 CLI 配置文件、项目或日志。原生直连时 CLI 使用网关密钥；桥接模式下 CLI 只收到临时本地令牌。桥接的上游错误响应仅保留 HTTP 状态和诊断提示；流式中断会断开连接，交给 CLI 正常报错/重试，不向 SSE 混入普通 JSON。

## 范围和限制

- 跨协议支持文本与用户图片块（PNG/JPEG/WebP/GIF 的 base64 或 HTTP(S) URL），保持图文顺序与图片字节。桥接不主动下载 URL。提供商 file_id、图片 transformations、音频和远端托管工具等未适配输入明确失败。App 的图片导入与拍照入口仍未接通。
- Responses 的图片 detail 转到 Chat/Responses 时保留；Messages 没有等价字段，显式 low/high/original 均拒绝，auto 使用目标默认行为。当前 Codex 图片请求带 high，因此 Codex → Messages 图片路径不支持，不能视为六组合图片兼容。图片工具结果可转到 Messages/Responses，转到 Chat 明确拒绝。
- 本地转换失败返回 HTTP 400，避免无意义重试；网络与上游响应解析失败仍返回 502。原生同协议请求不受跨协议字段限制。
- 原生协议私有推理内容不能跨供应商转换；跨协议不转发 hidden reasoning / encrypted reasoning。同协议保留。
- 不支持跨协议 `previous_response_id`，需要 CLI 携带完整会话；Responses 原生请求可以透传。
- 仅跨协议时禁用 Codex 自动远端压缩与默认 web_search，跨协议桥接没有伪造压缩响应。长任务可能达到模型上下文上限。
- 桥接请求/转换响应限制 16 MiB，每次远端请求超时 5 分钟；整个任务沿用 10 分钟限制。
- CLI 的更新、插件等其他网络能力不属于模型协议桥接；真实网关的模型能力、工具支持与鉴权需要实际联调。

## 验证

运行 `node --test runtime/gateway-tests/bridge.test.cjs`。21 项测试覆盖 6 个 Agent 原生协议/上游协议组合、工具 ID 与结果、命名空间名称还原、取消请求、Codex 自定义工具、URL 规范化、SSE 透传、拒绝不支持的输入、鉴权错误脱敏、输出截断状态、并行工具调用分组、断流错误和原生直连配置，并覆盖图文顺序、图片字节、无法表示字段与工具图片结果的拒绝、转换错误不请求上游。

参考：[Codex provider 配置](https://learn.chatgpt.com/docs/config-file/config-reference)、[Responses 事件](https://developers.openai.com/api/reference/resources/responses/streaming-events)、[Claude Code 网关配置](https://code.claude.com/docs/en/llm-gateway-connect)。

2026-09-20 主机联调：Claude Code 2.1.112 与主机现有 Codex 0.154.0-alpha.6.2，各自通过三个本地模拟上游，六种组合的文本回复全部通过；再进行实际工具读取临时文件并回传，六种组合全部通过。使用独立临时 HOME 和虚假测试密钥，未访问真实模型网关。手机内置 Codex 为 0.155.1，手机已更新安装并持久化网关配置，重启后读取正常。真实网关 Messages 和 Responses 小请求均返回 HTTP 200（Responses 的 64 token 探测达到输出上限）；手机端完整 Agent 任务仍待验收。

可复用的 CLI 工具回传验收脚本：`runtime/gateway-tests/cli-smoke.cjs`，通过 `MOBBY_TEST_CODEX` 与 `MOBBY_TEST_CLAUDE_JS` 指定本机测试程序路径。

图片联调脚本：`runtime/gateway-tests/images-smoke.cjs`，使用同样的 CLI 环境变量，生成临时测试图片、隔离 HOME 与虚假密钥。2026-09-21 主机 Codex 0.155.0-alpha.9.2 / Claude Code 2.1.112 实测：五个组合完整传递图片字节；Codex → Messages 确认在上游调用前明确失败。该结果不等于 Android 图片入口、真实模型视觉能力或真实网关验收。

图片格式依据：[OpenAI 图像输入](https://developers.openai.com/api/docs/guides/images-vision)、[Claude 图像输入](https://platform.claude.com/docs/en/build-with-claude/vision)。两个提供商的分辨率与 token 规则不同，不能把 Messages 默认处理推定为 OpenAI 显式 detail 的等价实现。
