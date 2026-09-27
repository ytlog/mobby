# 网关接入

「网关设置」按网关列出已经保存的配置，每个网关只有一个选择入口。应用探测网关的原生协议后自动生成可用 Agent；同一网关可能支持多个 Agent，同一 Agent 也可使用多个网关。点按网关后，当前会话的下一轮使用该网关，并把它设为新建会话默认；如果该网关支持当前 Agent，保持 Agent 不变，否则切到它支持的 Agent。模型列表默认收起，点按网关条目展开，再点按可收起；展开后可为当前会话选择已保存的模型，当前 Agent 对该模型提供思考程度时才显示思考程度选项。新建会话使用当前默认网关；最近一次同 Agent 所选模型仍在这个网关的模型列表中时会沿用，否则使用网关默认模型。编辑网关时，仍在新列表中的会话模型也会保留。新建后可在会话模型菜单中调整。编辑、删除作用于整个网关。密钥可留空以连接无鉴权网关。默认选择与网关配置一起加密保存在设备上。已有任务的执行配置不会改变。

新建或编辑时，一个网关保存一次默认模型、密钥和用户筛选的模型列表。自定义服务只填写一个 Base 地址；预设服务提供内部候选地址。填写 Base 地址和密钥后点「获取模型」，按钮显示加载状态；列表获取完成后才显示模型筛选、默认模型选择和「保存网关」。模型目录不可用时可手动输入模型 ID。点击保存后，按钮显示加载状态，应用使用所选默认模型自动探测 Agent 的原生协议；只有接口返回成功且响应符合对应协议、没有错误对象时才写入可执行路由。未通过时保留表单并显示错误。模型因输出上限停止不影响协议支持判定。保存后生成独立网关 ID，后续编辑增加版本；已有会话引用旧版本时仍按当时的地址、模型与密钥运行。删除网关会删除其加密版本及默认选择，引用该网关的会话需要重新选择网关。开发阶段直接采用当前存储结构，旧网关配置需要重新创建。

获取模型时应用请求 `GET {基址}/models`；例如 Base 地址为 `https://host/v1` 时请求 `/v1/models`，此步不执行 Agent 推理探测。Messages 会按 `has_more` 分页，最多二十页；最多读取两千个模型。模型下拉菜单可搜索、勾选并设置默认项；手动添加通过弹窗一次填写一个模型 ID，模型列表不可用时仍可添加。保存时默认模型必须通过原生请求探测并始终保留；其余手动添加的模型不会逐个探测。只保存所选模型的 id 和显示名，不保存响应正文，也不把密钥写进列表。下一轮把所选模型交给本地桥接。模型列表接口通常不提供思考程度，因此这些模型仍显示为未开放调整。

| Agent | 唯一支持的协议 | 上游接口 |
| --- | --- | --- |
| Codex | Responses | `/responses` |
| OpenCode | Responses | `/responses` |
| Claude Code | Messages | `/messages` |

本页仅描述现有 mobby App 的远端网关。其模型请求统一经过本地 Node 桥接，暂不提供任何协议转换；同 APK、独立进程的本地模型服务有自己的 HTTP 协议适配，见[首版说明](design/local-model-first-version.md)，但当前宿主已隐藏本地模型入口，也不自动接入临时本地网关。Chat Completions 不作为可选协议；协议由 Agent 决定，用户无需手动指定网关支持的 Agent。探测会发送小型原生请求，可能产生少量费用；探测成功仅证明当前地址、密钥和模型能收到该协议的有效响应，不保证完整生成或 Agent 执行成功。

界面只要求 Base 地址，例如 `https://host/v1`。粘贴完整接口路径时会去掉末尾的 `/responses` 或 `/messages`；无路径时使用 `/v1`。自定义路径会保留，例如 `https://host/api/v2` 会追加所探测接口的路径。

## 服务选择

服务预设列在一个表单中，提供内部候选地址，不预先宣称 Agent 支持情况。界面仅显示一个 Base 地址，不替换模型或已保存密钥。再次打开时，地址与预设一致就选中该服务；其余地址保持自定义。

| 服务 | 候选协议 | 基址 |
| --- | --- | --- |
| OpenRouter | Responses、Messages | `https://openrouter.ai/api/v1` |
| OpenAI | Responses、Messages | `https://api.openai.com/v1` |
| xAI | Responses、Messages | `https://api.x.ai/v1` |
| Groq | Responses、Messages | `https://api.groq.com/openai/v1` |
| Anthropic | Responses、Messages | `https://api.anthropic.com/v1` |
| DeepSeek | Responses、Messages | `https://api.deepseek.com`；`https://api.deepseek.com/anthropic/v1` |
| 小米 MiMo | Responses、Messages | `https://api.xiaomimimo.com/v1`；`https://api.xiaomimimo.com/anthropic/v1` |
| Kimi | Responses、Messages | `https://api.moonshot.ai/v1`；`https://api.moonshot.ai/anthropic/v1` |
| 智谱 GLM | Responses、Messages | `https://open.bigmodel.cn/api/v1`；`https://open.bigmodel.cn/api/anthropic/v1` |

Google Gemini 目前提供的是 Chat Completions 和 Interactions，没有 Responses 或 Messages，因此不列入可执行选项。需要 Gemini 时，使用 OpenRouter 上的对应模型，或在自定义中填写已经提供上述原生接口的网关。Messages 基址必须包含服务要求的 `/v1`，桥接会在其后追加 `/messages`。

## 运行方式

内置 Android Node 负责域名解析、TLS 和上游请求。CLI 通过官方环境变量/provider 配置连接 `127.0.0.1` 随机端口，每次任务使用随机令牌。CLI 只接收本地令牌，真实网关配置只交给桥接进程，任务结束或取消时关闭服务。Shell 不接收网关凭据；CLI 的权限、沙箱和审批参数保持原有约束。

请求 JSON 保留原生字段，只将 model 设置为用户保存的模型。不重新编码图片、工具、推理或未知消息类型。上游成功响应原样流式传输，不缓冲成完整消息、不合成成功或 SSE 终态。辅助接口支持 Responses `/compact` 与 Messages `/count_tokens`，保留查询参数及相关原生版本头。不匹配协议在本地明确拒绝，不请求上游。

上游发送 Bearer；Messages 同时发送 `x-api-key`、`anthropic-version` 和 CLI 的 `anthropic-beta`。HTTPS 正常校验证书；HTTP 可用于局域网，界面会提示明文传输。桥接不跟随重定向，不自行重试；CLI 自身的重试仍生效。上游错误正文不回传，只显示 HTTP 状态和脱敏提示；流式中断会断开连接，不追加伪造成功或普通 JSON。

API Key 和保存配置使用 Android Keystore AES-GCM 加密。桥接不主动写凭据到 CLI HOME、项目或日志，安装更新保持原应用标识、Keystore 别名、HOME、工作区和配置。

## 测试已保存连接

“测试已保存连接”使用 Android HTTP 客户端发送不带会话历史、最多 16 个输出 token 的小请求，可能产生少量费用。修改后先保存，检查期间可取消，返回页面会取消等待。保存提示与检查结果独立；接口返回成功且响应符合协议、没有错误对象即显示通过，不要求生成完整结束。HTTP、DNS、TLS、超时和异常响应分别报告。

此检查不启动 CLI，不能证明工具、图片、流式输出、沙箱或会话恢复可用，完整 Agent 任务需要另外验收。

## 范围和限制

- 仅模型原生接口及上述辅助接口通过桥接；CLI 更新、插件等其他网络功能不在此范围。
- 请求上限 16 MiB，每次上游请求超时 5 分钟，整个任务仍受 Runtime 10 分钟限制。响应采用背压流式传输。
- 压缩请求明确返回 415；当前固定 CLI 在自定义 provider 配置下发送普通 JSON。未支持的接口返回 404，不伪造能力响应。
- App 图片导入目前只支持受控 PNG/JPEG；拍照、真实视觉模型和不同厂商相机仍需全链路验收。
- 当前 M2007J1SC / Android 13 内核未启用 USER_NS/PID_NS，Codex 所需的 Linux 沙箱无法运行；官方 bwrap 已补齐，但工具执行门槛仍失败。不能把文本模型响应通过解释为完整 Agent 可用。
- 旧 Linux musl Codex 裸程序在本机 Android 缺少可用的域名解析路径。统一桥接将联网交由 Android Node，没有修改 Codex 二进制或关闭沙箱。裸程序单独执行仍不等于 App 执行路径。

## Claude 审批

Claude 使用双向 stream-json 控制通道。手机上的执行边界是应用 UID/SELinux 沙箱，因此格式正确的 `can_use_tool` 由应用立即按原始参数允许一次，不再显示确认卡。这样屏幕插件的连续点击、输入和返回可以继续跑完。允许不添加持久规则，也不改用 bypass 参数。格式错误、取消、过期和重复请求仍然不能授权。模型在回复里询问确认不等于这条协议。Codex 使用 `danger-full-access` 与 `approval_policy=never`，同样不再询问。OpenCode 对当前这一次运行使用 `--auto`，不写持久权限规则，也不使用 `dangerously-skip-permissions`。

OpenCode 的内置 `openai` provider 固定走 Responses。桥接把 `OPENAI_API_KEY` 设为本地令牌，并用 `OPENCODE_CONFIG_CONTENT` 把 `baseURL` 指到本地桥接的 `/v1`；上游地址和密钥不会交给 CLI。设备门槛 `android-codex-network.cjs` 仍只覆盖 Codex 与 Claude Code，不能当作 OpenCode 的手机验收。

## 代码位置

`runtime-android` 的 `gateway` 包管理加密存储、协议探测、模型目录与连接检查；`interaction-ui` 的 `gateway` 包管理服务预设、网关列表和编辑界面；`runtime-api` 与 `interaction-domain` 各自的 `gateway` 包提供跨层契约。网关涉及 Android Keystore、HTTP、业务契约和 Compose 界面，按职责保留在现有模块中，不另建一个混合所有层的 Gradle 模块。

## 验证命令

- `node --test runtime/gateway-tests/bridge.test.cjs`：同协议字段/流完整性、辅助接口、鉴权、拒绝转换、输入限制、取消、断流与错误脱敏。
- `MOBBY_TEST_ADB=/path/to/adb node runtime/gateway-tests/android-codex-network.cjs`：已安装 App 的实际启动脚本与内置 Codex，比较 IP/域名上游。独立 HOME、虚假密钥、adb reverse 本地模拟服务；不改真实配置。用 `MOBBY_TEST_AGENT=CLAUDE` 改测 Claude Code；可用 `MOBBY_TEST_TOOL=1` 增加设备 shell 读取与回传门槛（当前因设备缺少用户/PID 命名空间且限制 bwrap 所需内核信息而失败）；可用 `MOBBY_TEST_RAW_CODEX=1` 单独诊断裸 CLI，当前域名阶段预期失败。
- `MOBBY_TEST_ADB=/path/to/adb node runtime/gateway-tests/structured-output-smoke.cjs`：已安装固定 CLI 的结构化输出，覆盖 Codex/Claude 的首轮和续接；检查原生请求 schema、成功终态及字段一致。Claude 使用 stream-json 输入与 stdio 审批通道。需空闲真机、串行执行；隔离 HOME、虚假密钥和本地模拟网关，不能代替真实模型生成稳定性验收。
- `runtime/gateway-tests/approval-smoke.cjs`：固定 Claude CLI 的真实允许/拒绝/等待时取消，用 `MOBBY_TEST_CLAUDE_JS` 验证主机或 `MOBBY_TEST_ADB` 验证手机。该脚本只验证 CLI 协议，不能单独替代应用界面验收。
- `MOBBY_TEST_CLAUDE_JS=/path/to/cli.js ./gradlew :runtime-engine:test --tests '*ClaudeControl*'`（JDK 17）：生产 Kotlin 控制会话与真实固定 Claude CLI 的允许、拒绝、等待时取消及恢复会话检查，使用隔离 HOME 和模拟模型。
- `runtime/gateway-tests/cli-smoke.cjs`、`images-smoke.cjs`、`skills-smoke.cjs`：用 `MOBBY_TEST_CODEX` 与 `MOBBY_TEST_CLAUDE_JS` 指定主机程序，隔离 HOME 并使用虚假密钥。只验证 Codex/Responses 与 Claude/Messages，结果不能替代手机真实网关验收。

历史的六组合转换结果只属于旧版本，见[实施记录](implementation.md)。当前设备与真实模型验证结果也在该记录中持续更新。

参考：[Codex provider 配置](https://learn.chatgpt.com/docs/config-file/config-reference)、[Claude Code 网关配置](https://code.claude.com/docs/en/llm-gateway-connect)。
