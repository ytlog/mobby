# 免费网关体验入口

基于 `91aa170` 的实现，按 2026-10-15 核对的官方说明设计。目标是让新用户取得自己的模型调用凭据，使用服务商提供的免费模型或新人额度，开发者无需提供共享密钥或补贴模型费用。

## 页面与默认选择

- 网关列表提供独立的「免费体验」入口，原有「添加网关」继续提供普通 Provider 和自定义地址。
- 首次使用、没有网关时的引导进入免费体验。中文界面默认百炼，顺序为百炼、火山方舟、OpenRouter、Requesty、Groq；英文界面默认 OpenRouter，国际服务在前。
- 依据 App 当前界面语言决定初始推荐，不根据 IP 推断用户位置。用户主动选择服务后不会因重组列表而被切换。推荐规则不修改已有网关、默认网关或会话。
- 免费服务菜单分为国内与国际两组，单选后预填各原生协议基址。切换免费服务会清空待提交的 Key，避免把前一家服务的凭据提交给另一家。
- 页面显示服务的额度条件、官方注册/Key 入口和额度说明。国内服务在选定模型后、保存前确认余量及额度耗尽停止条件；获取模型列表不要求该确认，切换 Key、服务或默认模型会撤销确认。
- 获取 Key 不代表网关保存成功。保存仍通过现有 Runtime 原生协议探测，失败保留表单，取消和超时如实显示。

## 服务与能力边界

| 服务 | 免费依据 | 取得凭据 | 候选基址 |
| --- | --- | --- | --- |
| 百炼（北京） | 首次开通，部分模型通常各 100 万 Token、90 天有效；未认证账号强制用完即停 | 官网注册、开通并创建北京地域 Key，填入 App | Responses：`https://dashscope.aliyuncs.com/compatible-mode/v1`；Messages：`https://dashscope.aliyuncs.com/apps/anthropic/v1` |
| 火山方舟（北京） | 按模型赠送免费推理额度；额度耗尽后需实名认证及手动开通付费 | 官网注册、确认模型额度，创建 Key 后填入 App | Responses：`https://ark.cn-beijing.volces.com/api/v3`；Messages：`https://ark.cn-beijing.volces.com/api/compatible/v1` |
| OpenRouter | 未购买额度的账号当前每天 50 次免费模型请求 | 官方 OAuth PKCE 登录授权自动取得 Key，或手动填 Key | 两协议：`https://openrouter.ai/api/v1` |
| Requesty | 新组织免费模型合计每天 200 次、每分钟 20 次；无需信用卡 | 官网注册和创建 Key，填入 App | Responses：`https://router.requesty.ai/v1`；Messages：`https://router.requesty.ai/anthropic/v1` |
| Groq | Free 套餐按模型限制请求数和 Token 数 | 官网邮箱/已有账号注册、创建 Key 后填入 App | Responses：`https://api.groq.com/openai/v1` |

国内入口属于「领取和使用新人额度」，不是永久免费模型。百炼已认证账号须在官方控制台开启免费额度用完即停；官方目前说明移动端不能配置该开关，需要电脑端。火山方舟应选仍有免费额度、未开通付费的模型。App 不能通过推理 Key 查询这些账号的赠送额度，确认控件也不代替服务商的计费设置。

国际入口限制初始模型选择：OpenRouter 默认 `openrouter/free`，只允许该路由和 `:free` 模型；Groq 默认 GPT-OSS 20B，只提供已核对 Free 列表中的文本模型；Requesty 默认 Nemotron 3 Super，只提供已核对官方免费列表的生成模型，排除收费模型、任意 policy 和安全分类模型。模型列表和手动添加都遵守同一限制，不配置付费回退。

这些限制属于免费入口的设置流程，保存后仍使用原有网关记录结构。普通网关编辑可以由用户主动改选其他模型。Requesty/Groq 免费名单可能调整，账号升级也可能改变收费方式，不能承诺现有名单永久零费用。网页说明中的免费网页聊天不等于免费 API。

NVIDIA 免费托管 API 的 Chat Completions 不符合当前 Agent 原生协议约束，不添加灰色预设。NanoGPT 虽提供 OAuth，但免费模型仅限网页，不列入免费体验。Requesty 官方 CLI 有 OAuth 自动取 Key 实现，尚未确认开放给 mobby 的客户端注册方式，当前仅提供真实可用的官网取 Key 流程，不复用其他客户端身份。

## OpenRouter 授权

Android Runtime 生成随机 PKCE verifier、S256 challenge 和 state，绑定 `127.0.0.1` 随机端口。主 Activity 仅负责在系统浏览器打开 OpenRouter 官方授权页；账号注册、验证、条款及授权由用户在服务商页面完成。

回调仅接受本机端口上的 `GET /callback`，校验 Host、state、重复参数、路径、请求大小与错误字段。有效的一次性授权码交给固定 HTTPS 端点 `POST https://openrouter.ai/api/v1/auth/keys`，附 verifier 和 `S256`。不跟随重定向，读取响应有大小和超时限制，不向界面返回上游错误正文。

授权等待最多五分钟，可在页面取消；离开页面时随 Compose 生命周期取消。取消会关闭回调服务和在途连接，任何未交付凭据不会被当作成功。回调页面不显示授权码或 Key，只提示返回 mobby。若 Android 回收进程或旋转导致该页销毁，用户需要重新授权，不把 verifier/Key 写入 SavedState 恢复。

取得的 Key 只在待提交表单内存中使用，字符数组交付后清零，不进入日志、普通偏好、数据库、源码或 CLI 配置。保存探测成功后，沿用 `mobby.gateway` Keystore 加密存储。授权能力经主 Activity 的 host action 传给界面；Runtime 持有网络实现，UI 只处理领域结果。

## 协议与验收

Pi、Codex、OpenCode 继续仅走 Responses，Claude Code 仅走 Messages；不在 App 内添加 Chat Completions 转换。预设只提供候选地址，可执行 Agent 必须由原生请求探测确认。

验证覆盖语言默认和分组、普通网关编辑、官方链接、Key 导入和清零、国内额度确认、免费模型过滤及手动添加限制。授权测试用隔离 loopback 回调和虚假凭据，覆盖 S256、state/路径/重复参数拒绝、重定向/错误/超大响应、取消关闭端口及在途 HTTP 连接和超时。

2026-10-15 基于本次工作区的验证：JDK 17 下 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:termux-core:testDebugUnitTest`、`:runtime-android:testDebugUnitTest`、`:conversation-domain:test`、`:localization:test`、`:app:lintDebug` 通过；共 382 个单元测试通过。Node 桥接测试 15 个、Python Runtime 测试 43 个通过。使用已连接手机覆盖安装，保留原有应用数据。

随后在同一台 M2007J1SC（Android 13）解锁后完成真机页面验收：中文默认百炼、国内组在前；英文默认 OpenRouter、国际组在前；五个免费 Provider 均可切换且预填地址正确；普通添加网关中新增选项与 Kimi 并列。OpenRouter 登录进入等待，返回 App 可取消，中英文取消提示正确；官网 Key 入口能启动系统浏览器。未输入真实凭据、未保存新网关、未发起模型调用。验收后恢复中文并停在百炼免费体验页。第三方账号授权成功回调、剩余额度与真实推理尚未验收。

模拟授权不代表真实账号注册、第三方授权页、国内网络、剩余额度或真实模型的工具/图片/多轮执行已经验收。发布前仍需分别在实际账号和手机上验证。

## 官方依据

- [百炼新人额度和用完即停](https://help.aliyun.com/zh/model-studio/new-free-quota)、[北京地域地址](https://help.aliyun.com/en/model-studio/beijing-access-information)、[取得 Key](https://help.aliyun.com/zh/model-studio/get-api-key)、[Responses](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-responses)、[Messages](https://help.aliyun.com/zh/model-studio/anthropic-api-messages)。
- [火山方舟免费额度](https://docs.volcengine.com/docs/ark/free-inference-quota?lang=zh)、[Responses 示例](https://docs.volcengine.com/docs/ark/quick-start?lang=zh)、[Messages 示例](https://docs.volcengine.com/docs/ark/web-search?lang=zh)。
- [OpenRouter OAuth PKCE](https://openrouter.ai/docs/guides/overview/auth/oauth)、[免费套餐](https://openrouter.ai/pricing)、[免费模型路由](https://openrouter.ai/docs/guides/routing/routers/free-router)。
- [Requesty 免费模型](https://docs.requesty.ai/features/free-models)、[接口入门](https://docs.requesty.ai/quickstart)、[官方 CLI 授权实现](https://github.com/requestyai/cli#first-run)。
- [Groq 免费限额](https://console.groq.com/docs/rate-limits)、[Responses API](https://console.groq.com/docs/responses-api)。
- [NanoGPT OAuth](https://nano-gpt.com/blog/sign-in-with-nanogpt-oauth-pkce)、[免费模型仅限网页的价格说明](https://nano-gpt.com/pricing)。
