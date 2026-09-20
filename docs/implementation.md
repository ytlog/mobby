# 实施记录

## 当前实现

- Compose 测试页：Shell、Claude Code、Codex；输入、发送、停止、清空与流式输出。
- ViewModel + StateFlow，运行服务持有任务；SDK 参数数组执行、双管道读取、进程组取消和超时。
- libtermux-android core / bootstrap-arm64，固定上游提交 `3a7e2ae63c4824fac384769c9afb8bc579458da9`。
- 自动安装：APK 携带经过摘要校验的基础依赖和 CLI，首次启动自动部署，随后逐项执行 `--version` 并初始化 Git 工作区。
- 更新或关键文件缺失时重新部署；每个数据文件先写临时文件再替换，全部完成后写版本标记。
- 安装只写 SDK PREFIX，不删除 HOME、认证文件或 workspace。每次启动刷新指向 nativeLibraryDir 的链接。
- 原生程序由 Android APK 安装器放到可执行目录；npm / Claude Code 通过内置原生入口运行 Node.js，提示词按参数传递。
- Git、证书、npm 缓存使用本 App 路径。初始化失败可重试，部分 CLI 异常时保留 Shell 和诊断信息。

## 固定依赖

| 依赖 | 版本 | 来源 |
| --- | --- | --- |
| Git | 2.55.0 | Termux 官方仓库 |
| Node.js | 24.18.0 | Termux 官方仓库 |
| npm | 11.19.1 | Termux 官方仓库 |
| Claude Code | 2.1.112 | Anthropic 官方 npm JavaScript 版本 |
| Codex | 0.155.1 | OpenAI 官方 npm ARM64 musl CLI |

精确 URL、版本和校验值在 `runtime/agents.lock.json`。Claude Code 使用可由 Android Node.js 执行的固定 JavaScript 版本，不是最新原生安装器。未纳入其他平台的可选音频、图像原生插件；Codex 未集成语音组件。

## 验证结果（2026-09-20）

设备：M2007J1SC，Android 13 / API 33，ARM64。

- 更新 APK 已覆盖安装到手机，应用启动后自动部署依赖。
- 在应用 UID 下逐项验证五个命令，均返回上表中的实际版本。
- Node.js 执行 `console.log(6*7)` 返回 `42`。
- 自动创建的 workspace 中 `git rev-parse --is-inside-work-tree` 返回 `true`。
- Node.js HTTPS 请求 npm 官方 ping 接口返回 200，证书链验证有效。
- Codex `login status` 返回 `Not logged in`；Claude Code `auth status` 返回 `loggedIn: false`。未发起模型请求。
- 自动安装版本构建及 App 5 项单元测试通过；此前 SDK 32 项单元测试通过。
- Python 安装包校验测试 5 项通过，覆盖摘要篡改、越界路径和资源映射。
- 4 项设备测试已编译；辅助测试 APK 被手机以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝安装，未计入通过。

## 剩余验收

- 填入网关配置后，验证真实模型任务、工具调用和文件修改。
- 通过应用界面验证持续输出、停止、超时、屏幕旋转及后台切换。
- 验证 Codex 在 Android 上的默认沙箱能力；没有添加跳过沙箱或权限检查参数。
- 普通后代随进程组退出；主动创建独立会话的后代仍需额外验证。
- 上游 apt 仍有 com.termux 固定路径，当前自动安装使用已锁定的 APK 资源，不依赖 apt。

CLI 协议参考：[Claude Code](https://code.claude.com/docs/en/headless)、[Codex](https://learn.chatgpt.com/docs/non-interactive-mode)。

## 网关扩展

新增独立 Agent 网关配置、Android Keystore 加密保存，以及 Messages / Responses 到三种网关协议的本地桥接。细节与限制见 [网关说明](gateway.md)。网关版本构建、App 8 项单元测试和 lint 通过；协议桥接 14 项 Node.js 测试通过。网关版本已覆盖安装到手机。两个 Agent 的配置通过界面加密保存到 SharedPreferences，重启 App 后可正常读取；域名及密钥未写入源码。

主机真实 CLI 与本地模拟网关的六种组合均完成文本回复和工具读取/结果回传，详细测试版本及范围见网关说明。真实网关的 Messages / Responses 小请求均返回 HTTP 200；手机端完整 Agent 任务仍待验收。

## 代码复查与重构

- 原生协议匹配时改用 CLI 官方配置直连；保留必要的跨协议/路径/无鉴权桥接。原生 Codex 不再被跨协议限制关闭自动压缩、推理摘要和默认重试。
- 修复 Responses 多个工具调用转 Chat 时拆散 assistant 轮次的问题，保证一组调用之后再回传对应结果。
- SSE 转发改用 Node.js pipeline 管理背压与连接清理；断流不再追加普通 JSON，避免掩盖失败。
- bootstrap 打包清除不在当前映射中的旧 native 文件，避免依赖调整后旧文件继续混入 APK。
- 修复真 CLI 模拟网关测试把模型探测 GET 当推理 JSON 解析的缺陷。
- 上述运行缺陷先由新增回归测试复现，再完成修复。Node.js 17 项、Python 6 项、App/SDK 单元测试、构建和 lint 通过；六种组合的真 CLI 工具回传联调通过。该轮重构尚未在手机运行完整 Agent 任务。
- 根目录 AGENTS.md 固化“先纠正基础、必要时替换错误实现，再继续迭代”的开发要求。

## mobby 改名

项目与目录名、源码包名、界面品牌、脚本环境变量和 SDK 适配构建文件统一改为 mobby。Android 安装标识及 Keystore 别名保留兼容值，覆盖升级前后设备网关加密配置摘要一致。改名后构建、lint、63 项测试和六种 CLI 联调通过，已覆盖安装到原手机并成功启动新入口。

## 交互方案实施：首个契约检查点（2026-09-21）

已按两份设计的迁移顺序开始实施，新增 `:runtime-api` 与 `:interaction-domain` 两个纯 Kotlin/JVM 模块，沿用 Kotlin 1.9.0、coroutines 1.7.3 和 JDK 17。两模块均无项目依赖，领域层不引用 Runtime DTO、Android 或 Compose。应用仍使用现有测试控制台；新增契约尚未接入生产执行路径。

- Runtime 核心 API：提交、按请求查询、取消、审批、能力、快照、事件观察和有界产物读取。请求只含受控引用；产品 Agent 仅 Codex / Claude Code，Shell 后续保留于内部诊断。
- 明确区分执行阶段与连接状态、取消接纳与进程退出、消息完成与整轮成功。事件包含运行 ID 和单调序号；基线恢复替换投影，后续事件按游标消费，不再次追加基线已有输出。
- 测试目录中的 FakeRuntimeClient 验证幂等请求、全局 Busy、取消与完成竞态、审批版本、基线与事件衔接；它不执行 CLI、不持久化，也不打包进应用。下一阶段必须将相同契约验证用于真实执行核心和日志实现。
- 领域用例要求先原子保存待提交轮次及草稿版本，再调用执行端口；应答未知时查询原请求，不自动生成新任务。取消等待后也可通过已有待提交记录核实结果。
- 统一交互规则：接纳后仅清除对应版本草稿；运行期间编辑保留为下一轮；已有会话跨 Agent 新建并仅复制文字；空对话可调整工作区，已有记录后不可修改执行目录；技能对话另建并绑定 Creator；过程折叠遵守手动选择与阅读状态。
- 数据投影序号策略拒绝跨运行事件、跳号和重复增量；未来 Data 实现必须在同一事务中写入投影与消费游标。

验证：新增 Runtime 8 项、Domain 10 项测试通过；空会话调整工作区的边界先由失败测试复现，再修正。JDK 17 下 `:runtime-api:test :interaction-domain:test :app:assembleDebug :app:testDebugUnitTest :termux-core:testDebugUnitTest :app:lintDebug` 通过；现有 App 8 项、SDK 32 项测试保持通过。Node.js 网关 17 项、Python 打包 6 项通过，合计 81 项测试。未重复运行真 CLI 模拟网关联调（本轮未改执行/桥接代码），未覆盖安装或声称通过手机交互验收。

后续仍需落实：管理接口及领域管理端口、runtime-engine/runtime-android 提取、持久幂等日志、Service 客户端、interaction-data/Room、Compose 页面与 App 装配。管理接口尚未作为已完成 API 发布；当前契约也未宣称冻结为最终稳定版本。推开抽屉、面板高度、焦点与阅读锚点、IME、技能导入/保存、真实审批和进程停止均待原生接入及验收。当前测试证明领域规则和假实现契约，不能证明真实执行、持久恢复或视觉交互已完成。
