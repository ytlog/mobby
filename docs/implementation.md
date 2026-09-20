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
