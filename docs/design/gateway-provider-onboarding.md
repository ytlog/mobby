# 网关 Provider 配置引导

所有服务统一使用原有「网关 → 添加网关 / 编辑网关」流程。扩充 Provider、官网取 Key 引导及 OpenRouter 登录能力，不再提供单独的免费入口、免费模式、模型白名单或额度确认勾选。

## 页面与行为

- 新增百炼、火山方舟、Requesty，与原有 OpenRouter、OpenAI、Anthropic、DeepSeek、MiMo、Kimi、智谱、xAI、Groq 并列。自定义地址继续可用，不加入 NVIDIA 灰色预设。
- 中文界面新建默认北京地域百炼，国内服务排在前；英文默认 OpenRouter，国际服务排在前。语言决定新表单的初始推荐、菜单顺序以及有独立国内/国际平台的默认站点；已有网关根据保存的地址识别原站点，站点可由用户手动切换，不自动改写已保存的账号、地址或 Key。
- Kimi、百炼、火山方舟与智谱提供国内/国际站选择，官网取 Key 入口和 API 基址随站点一起变化。中文默认国内，英文默认国际；无独立两站的服务共用官方平台，MiMo 和 DeepSeek 按语言选择文档。
- 每个预设都有针对该平台的注册/Key 说明、官网入口和官方使用文档；编辑已有网关也能查看引导。自定义服务显示填写地址、Key 与手动添加模型的方法。
- 统一流程为：选择 Provider → 官网获取 Key（OpenRouter 也可登录授权）→ 获取模型 → 选择默认模型及保留的模型 → 保存并探测原生协议。获取模型不发起推理，保存探测可能消耗调用额度。
- 模型列表完整展示服务商返回的内容，保留搜索、手动添加和多模型选择。不自动选择免费模型、不按免费名单过滤，不静默改写已有地址、模型或套餐。
- 免费额度、套餐、余额及模型价格只是服务商条件说明。赠送额度会过期、限速或耗尽，App 无法通过推理 Key 查询所有平台的账户余量；实际计费与用完停止由官网设置决定。
- 切换 Provider、站点或修改自定义地址会清空待提交 Key，并明确以空凭据覆盖待提交请求，避免把原有加密存储中的 Key 发到新地址；未保存时不改变设备配置。重选相同 Provider 保留 Key 和模型列表。
- 注册、身份验证、套餐开通和付费都在用户自己的服务商账号上完成。App 不提供共享密钥或自动替用户开通付费。

## 官方入口与说明

| Provider | 取得 Key | 引导重点 / 官方文档 |
| --- | --- | --- |
| OpenRouter | [Key 管理](https://openrouter.ai/settings/keys)，或官方 PKCE 授权 | [入门](https://openrouter.ai/support)；免费模型可选 `openrouter/free` 或 `:free` ID，其他模型按官网计费 |
| OpenAI | [API 平台 Key](https://platform.openai.com/api-keys) | [API 和 ChatGPT 的独立计费](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform) |
| Anthropic | [Claude Console Key](https://platform.claude.com/settings/keys) | [创建个人 Key、权限与有效期](https://platform.claude.com/docs/en/get-api-key) |
| DeepSeek | [开放平台 Key](https://platform.deepseek.com/api_keys) | [创建 Key 和接入](https://api-docs.deepseek.com/guides/codex)；确认账户余额及模型价格 |
| MiMo | [API 开放平台](https://platform.xiaomimimo.com/)控制台 | [官方文档](https://mimo.mi.com/docs)；核对普通 API / Token Plan 的 Key、地址与额度 |
| Kimi | [国内 Key](https://platform.kimi.com/console/api-keys) / [国际 Key](https://platform.kimi.ai/console/api-keys) | [国内接入](https://platform.kimi.com/docs/get-api-key)、[两站账号与 Key 隔离说明](https://www.kimi.com/help/kimi-api/api-troubleshooting)；国内使用 `api.moonshot.cn`，国际使用 `api.moonshot.ai` |
| 智谱 GLM / Z.ai | [国内 Key](https://bigmodel.cn/usercenter/proj-mgmt/apikeys) / [国际 Key](https://z.ai/manage-apikey/apikey-list) | [快速开始](https://docs.bigmodel.cn/cn/guide/start/quick-start)；普通 API 和 Coding Plan 须匹配 Key、地址与额度 |
| xAI | [Console](https://console.x.ai/)的 API Keys | [快速开始](https://docs.x.ai/developers/quickstart)；使用模型调用 Key，确认 API 余额 |
| Groq | [Key 管理](https://console.groq.com/keys) | [免费及付费套餐限额](https://console.groq.com/docs/rate-limits) |
| 百炼 / Model Studio | [国内北京 Key](https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key) / [国际控制台](https://modelstudio.console.alibabacloud.com/)（选择新加坡） | [新人额度与用完即停](https://help.aliyun.com/zh/model-studio/new-free-quota)；已认证账号需自行设置停止条件 |
| 火山方舟 / BytePlus ModelArk | [国内北京 Key](https://ark.volcengine.com/region:cn-beijing/apikey) / [国际新加坡 Key](https://ai.byteplus.com/ark/region:ap-southeast-1/apikey) | [免费推理额度](https://docs.volcengine.com/docs/ark/free-inference-quota?lang=zh)；按模型开通，耗尽后需实名认证及手动开通付费 |
| Requesty | [Key 管理](https://app.requesty.ai/api-keys) | [入门](https://docs.requesty.ai/quickstart)、[免费模型活动](https://docs.requesty.ai/features/free-models)；所选模型和账号条件决定费用 |

国内和国际配置对应：

| 服务 | 国内原生基址 | 国际原生基址 |
| --- | --- | --- |
| Kimi | Responses：`https://api.moonshot.cn/v1`；Messages：`https://api.moonshot.cn/anthropic/v1` | Responses：`https://api.moonshot.ai/v1`；Messages：`https://api.moonshot.ai/anthropic/v1` |
| 百炼 | Responses：`https://dashscope.aliyuncs.com/compatible-mode/v1`；Messages：`https://dashscope.aliyuncs.com/apps/anthropic/v1` | 新加坡 Responses：`https://dashscope-intl.aliyuncs.com/compatible-mode/v1`；Messages：`https://dashscope-intl.aliyuncs.com/apps/anthropic/v1` |
| 火山方舟 / BytePlus | Responses：`https://ark.cn-beijing.volces.com/api/v3`；Messages：`https://ark.cn-beijing.volces.com/api/compatible/v1` | Responses：`https://ark.ap-southeast.bytepluses.com/api/v3`；Messages：`https://ark.ap-southeast.bytepluses.com/api/compatible/v1` |
| 智谱 / Z.ai | 保留原有国内候选 Responses：`https://open.bigmodel.cn/api/v1`；Messages：`https://open.bigmodel.cn/api/anthropic/v1` | 仅列入已核对的 Messages：`https://api.z.ai/api/anthropic/v1`；未声明 Responses 支持 |

国际新加坡百炼的额度不套用北京赠送规则；如果创建 Key 时提供业务空间专属 API Host，应按官网使用自定义地址。Z.ai 国际预设按 [官方 Claude Code 接入](https://docs.z.ai/devpack/tool/claude)使用 Messages，不能把其 Chat Completions 当作 Responses。国际入口依据还包括 [百炼 Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)、[国际 Key 管理](https://www.alibabacloud.com/help/en/model-studio/get-api-key)、[BytePlus 原生 Messages](https://docs.byteplus.com/en/docs/modelark/messages-api)。

官方页面及活动会调整；引导不承诺永久免费或所有账号都能使用同样的额度。页面说明按 2026-10-15 核对。国内/国际排序是推荐分类；已保存配置保持原站点和执行协议，不能仅凭服务名称或界面语言断言网络可达。

## OpenRouter 授权

遵循 [OpenRouter 官方 PKCE 授权流程](https://openrouter.ai/docs/guides/overview/auth/oauth)。

Android Runtime 生成随机 PKCE verifier、S256 challenge 和 state，绑定 `127.0.0.1` 随机端口。主 Activity 仅负责在系统浏览器打开 OpenRouter 官方授权页；账号注册、验证、条款及授权由用户在服务商页面完成。

回调仅接受本机端口上的 `GET /callback`，校验 Host、state、重复参数、路径、请求大小与错误字段。有效的一次性授权码交给固定 HTTPS 端点 `POST https://openrouter.ai/api/v1/auth/keys`，附 verifier 和 `S256`。不跟随重定向，读取响应有大小和超时限制，不向界面返回上游错误正文。

授权等待最多五分钟，可在页面取消；离开页面时随 Compose 生命周期取消。取消会关闭回调服务和在途连接，任何未交付凭据不会被当作成功。回调页面不显示授权码或 Key，只提示返回 mobby。若 Android 回收进程或旋转导致该页销毁，用户需要重新授权，不把 verifier/Key 写入 SavedState 恢复。

取得的 Key 只在待提交表单内存中使用，字符数组交付后清零，不进入日志、普通偏好、数据库、源码或 CLI 配置。保存探测成功后，沿用 `mobby.gateway` Keystore 加密存储。授权能力经主 Activity 的 host action 传给界面；Runtime 持有网络实现，UI 只处理领域结果。

## 协议与验收

Pi、Codex、OpenCode 继续仅走 Responses，Claude Code 仅走 Messages，不添加 Chat Completions 转换。Provider 地址是原生协议探测候选；官网取 Key 或登录成功不等于网关已保存成功。失败、取消和超时如实显示。

回归测试覆盖：单一配置入口；新建中文/英文推荐；全部 Provider 的说明和官网链接；保留完整模型列表、手动 ID、多模型与保存流程；国内/国际入口匹配对应地址、已有国际站配置在中文下仍保留原站点且可明确切换；站点单选行向无障碍接口报告真实选中状态；重选相同 Provider 保留凭据，切换服务及地址不复用旧 Key；授权取消与凭据清零。既有 PKCE、回调校验、错误响应、在途连接取消和超时测试继续保留。

模拟授权不能替代实际账号注册、完整第三方授权回调、余量核对以及真实模型的工具、图片和多轮执行验收。

本轮验证：JDK 17 下 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:termux-core:testDebugUnitTest`、`:runtime-android:testDebugUnitTest`、`:conversation-domain:test`、`:localization:test`、`:app:lintDebug` 全部通过，共 387 项单元测试通过。已覆盖安装至原有 M2007J1SC 手机，保留设备应用数据；安装后设备锁屏，新增站点页面与完整第三方注册、授权和真实模型调用尚未完成真机验收。
