# 网关接入

在「网关设置」中选择 Agent，填写网关地址、模型和 API Key，然后保存。两个 Agent 分别保存；密钥可留空以连接无鉴权网关。保存成功只代表设备配置已保存。

| Agent | 唯一支持的协议 | 上游接口 |
| --- | --- | --- |
| Codex | Responses | `/responses` |
| Claude Code | Messages | `/messages` |

按 2026-09-21 用户更新，模型请求统一经过本地 Node 桥接，暂不提供任何协议转换。Chat Completions 不再作为可选协议。旧的 Chat 或 Agent/协议不匹配配置仍保留在加密存储中，但不能执行、检查或原样保存；界面提示后须明确点击“改用 Responses / Messages”并保存，密钥不会因此清空。网关本身也必须支持对应接口。

地址可为基础路径或完整接口路径，例如 `https://host/v1`、`https://host/v1/messages`；无路径时使用 `/v1`。自定义路径会保留，例如 `https://host/api/v2` 会追加原生接口。

## 运行方式

内置 Android Node 负责域名解析、TLS 和上游请求。CLI 通过官方环境变量/provider 配置连接 `127.0.0.1` 随机端口，每次任务使用随机令牌。CLI 只接收本地令牌，真实网关配置只交给桥接进程，任务结束或取消时关闭服务。Shell 不接收网关凭据；CLI 的权限、沙箱和审批参数保持原有约束。

请求 JSON 保留原生字段，只将 model 设置为用户保存的模型。不重新编码图片、工具、推理或未知消息类型。上游成功响应原样流式传输，不缓冲成完整消息、不合成成功或 SSE 终态。辅助接口支持 Responses `/compact` 与 Messages `/count_tokens`，保留查询参数及相关原生版本头。不匹配协议在本地明确拒绝，不请求上游。

上游发送 Bearer；Messages 同时发送 `x-api-key`、`anthropic-version` 和 CLI 的 `anthropic-beta`。HTTPS 正常校验证书；HTTP 可用于局域网，界面会提示明文传输。桥接不跟随重定向，不自行重试；CLI 自身的重试仍生效。上游错误正文不回传，只显示 HTTP 状态和脱敏提示；流式中断会断开连接，不追加伪造成功或普通 JSON。

API Key 和保存配置使用 Android Keystore AES-GCM 加密。桥接不主动写凭据到 CLI HOME、项目或日志，安装更新保持原应用标识、Keystore 别名、HOME、工作区和配置。

## 测试已保存连接

“测试已保存连接”使用 Android HTTP 客户端发送不带会话历史、最多 16 个输出 token 的小请求，可能产生少量费用。修改后先保存，检查期间可取消，返回页面会取消等待。保存提示与检查结果独立；只有符合协议且完整结束的响应才显示通过。HTTP、DNS、TLS、超时、异常响应和输出达到上限分别报告。

此检查不启动 CLI，不能证明工具、图片、流式输出、沙箱或会话恢复可用，完整 Agent 任务需要另外验收。

## 范围和限制

- 仅模型原生接口及上述辅助接口通过桥接；CLI 更新、插件等其他网络功能不在此范围。
- 请求上限 16 MiB，每次上游请求超时 5 分钟，整个任务仍受 Runtime 10 分钟限制。响应采用背压流式传输。
- 压缩请求明确返回 415；当前固定 CLI 在自定义 provider 配置下发送普通 JSON。未支持的接口返回 404，不伪造能力响应。
- App 图片导入目前只支持受控 PNG/JPEG；拍照、真实视觉模型和不同厂商相机仍需全链路验收。
- 当前 M2007J1SC / Android 13 内核未启用 USER_NS/PID_NS，Codex 所需的 Linux 沙箱无法运行；官方 bwrap 已补齐，但工具执行门槛仍失败。不能把文本模型响应通过解释为完整 Agent 可用。
- 旧 Linux musl Codex 裸程序在本机 Android 缺少可用的域名解析路径。统一桥接将联网交由 Android Node，没有修改 Codex 二进制或关闭沙箱。裸程序单独执行仍不等于 App 执行路径。

## 验证命令

- `node --test runtime/gateway-tests/bridge.test.cjs`：同协议字段/流完整性、辅助接口、鉴权、拒绝转换、输入限制、取消、断流与错误脱敏。
- `MOBBY_TEST_ADB=/path/to/adb node runtime/gateway-tests/android-codex-network.cjs`：已安装 App 的实际启动脚本与内置 Codex，比较 IP/域名上游。独立 HOME、虚假密钥、adb reverse 本地模拟服务；不改真实配置。用 `MOBBY_TEST_AGENT=CLAUDE` 改测 Claude Code；可用 `MOBBY_TEST_TOOL=1` 增加设备 shell 读取与回传门槛（当前因设备缺少用户/PID 命名空间且限制 bwrap 所需内核信息而失败）；可用 `MOBBY_TEST_RAW_CODEX=1` 单独诊断裸 CLI，当前域名阶段预期失败。
- `runtime/gateway-tests/approval-smoke.cjs`：固定 Claude CLI 的真实允许/拒绝/等待时取消，用 `MOBBY_TEST_CLAUDE_JS` 验证主机或 `MOBBY_TEST_ADB` 验证手机。仅验证协议；App 审批界面尚未接通。
- `runtime/gateway-tests/cli-smoke.cjs`、`images-smoke.cjs`、`skills-smoke.cjs`：用 `MOBBY_TEST_CODEX` 与 `MOBBY_TEST_CLAUDE_JS` 指定主机程序，隔离 HOME 并使用虚假密钥。只验证 Codex/Responses 与 Claude/Messages，结果不能替代手机真实网关验收。

历史的六组合转换结果只属于旧版本，见[实施记录](implementation.md)。当前设备与真实模型验证结果也在该记录中持续更新。

参考：[Codex provider 配置](https://learn.chatgpt.com/docs/config-file/config-reference)、[Claude Code 网关配置](https://code.claude.com/docs/en/llm-gateway-connect)。
