# 实施记录

## 当前实现

- Compose 持久会话页：Claude Code、Codex；Shell 保留在设置的诊断页。实现阶段及剩余功能见本文末尾。
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

## 真实 Runtime 提取与接通（2026-09-21）

- 新增 `:runtime-engine` 和 `:runtime-android`。Service、环境安装、加密网关、桥接 assets 归平台模块；协议归一化、命令构建、执行协调归 JVM 核心。库不引用 app 或交互模块，通知点击目标由 Application 注入。Manifest 的 libtermux 合并规则随平台模块迁移；bootstrap/JNI 仍由明确的模块依赖进入 APK。
- 测试控制台已改用 RuntimeClient / RuntimeAdminClient / 内部 RuntimeDiagnosticsClient；删除 app 中旧 Service 和旧扁平协议解析器。Shell 与 Agent 使用同一个执行槽，Shell 不进入产品 AgentId。后续对话 UI 仍需通过 Domain/Data 使用这些客户端。
- Runtime 使用独立 SQLite 日志，原子记录请求摘要、接纳、事件序号与当前快照；取消 commandId 回执持久化，冲突复用被拒绝。运行输出存入受控分段文件，观察者以快照替换投影，随后按序补读日志。进程身份在平台层记录，重启先清理核实遗留进程，再将未完成请求标为中断，不自动重发。
- 普通 stderr 仅作为诊断。整轮成功要求 CLI 明确成功终态与退出码 0 一致；Claude 权限拒绝不显示成功，assistant 与 result 的重复正文去重；私有 thinking/reasoning 块不作为公开进度展示。CLI session ID 可随请求恢复，参数始终使用 argv，没有权限或沙箱绕过参数。
- SDK 新增启动/退出观察回调，取消清理后返回真实退出证据；native wait 错误不再伪装为退出码。退出无法确认时记录 OutcomeUnknown 并保留执行槽。已接纳取消优先于迟到成功片段；旧终态不重复写入。
- 网关继续保留原 SharedPreferences 与 Keystore 别名；配置版本历史同样加密，用于冻结运行配置。普通管理读取不返回密钥，保存请求只短暂持有内存凭据，输出在平台边界脱敏。

验证：71 项 Kotlin/JVM 与 Android library 单测通过（API 8、engine 18、runtime-android 3、Domain 10、SDK 32）；Node.js 17、Python 8 项（含模块边界检查）通过，共 96 项。新增取消命令终态后重放的测试先失败再修复。Debug 主 APK、辅助测试 APK 和 lint 构建通过。真实主机 CLI 经三种网关协议的六种组合均通过工具执行/结果回传联调，使用隔离 HOME 与虚假测试密钥。

手机主 APK 已覆盖安装并启动新 Runtime，确认 Runtime 日志数据库已建立；安装前后原网关加密配置摘要一致、原工作区存在。辅助测试 APK 被手机拒绝（INSTALL_FAILED_USER_RESTRICTED），设备仍锁屏，因此新增真实 Service 停止、真实网关与 session 恢复仪器测试只完成编译，尚未计入通过。待解锁与允许测试安装后继续运行；未以主机模拟网关代替设备验收。

仍需继续：交互 Room 存储与事件投影、完整 Compose 对话页面、技能/资源管理和系统交互；Runtime 日志保留清理、管理连通性测试及输出容量/异常恢复仍需补充验证。当前 CLI exec/print 路径不提供可回传交互审批，能力接口明确报告不支持，不显示伪审批按钮。前台服务仍沿用 dataSync，Android 15 服务用途/超时验收未完成。整体“真实可用”目标保持进行中。

## 持久会话与 Compose 主界面（2026-09-21）

新增 `interaction-data`（Room 2.6.1）与 `interaction-ui`，App 负责装配和系统分享/快捷方式。删除旧 TestConsoleViewModel；Shell 从设置的诊断页使用原真实执行路径。UI 只依赖 Domain，Data 只依赖 Domain 与 Runtime API。

- 会话、独立草稿/选区、冻结轮次、运行投影、展开选择和阅读锚点存入 Room。输出与消费游标同事务提交，重复序号不追加文本，缺口请求快照；失联后的待确认请求查询原 requestId，不重发。数据库不使用破坏性迁移。
- 接纳仅清空匹配版本草稿，发送途中新增文字保留；ViewModel 销毁不取消应用作用域中的提交与结果入库。运行状态刷新保留中文输入法组合区；组词和回车不触发发送。
- 主界面接入推开式会话抽屉、置顶/项目/历史分组、标题搜索、Agent/模型/思考程度选择、执行过程原地展开、代码复制/换行、受限高度阅读面板、归档/最近删除恢复、消息选择分享。跨 Agent 和配置冻结遵守 Domain 规则。
- 网关页面读脱敏摘要并通过管理接口保存；编辑内容和密钥不进入 SavedState，当前轮次配置保持冻结。保存不显示为连通性测试成功。
- 语音使用 Android SpeechRecognizer，用户授权并开始录音；结束、转写、校对后只替换原草稿选区。关闭、后台或音频焦点丢失停止采集，晚到回调失效。原草稿或会话变化时拒绝覆盖。无服务、权限拒绝和失败均保留文字输入路径。语音设备验证尚未完成。

Room 的真实 SQLite/Robolectric 测试覆盖接纳与拒绝、切换草稿、跨 Agent、运行中归档/删除限制、展开/锚点持久化、数据库重开后查询原请求和冻结网关版本；投影测试覆盖序号、协议版本和成功证据。输入法组合区丢失先由失败测试复现再修复。

尚未完成：技能真实目录/导入/创建、受控附件导入、完整 Markdown、查找结果跳转、主题跨进程持久化、长历史分页、预测返回与焦点恢复的完整验收。技能与插件页当前明确显示不可用或空目录，附件入口禁用，不记为已交付功能。手机锁屏及辅助测试安装限制尚未解除，不能把主机测试或 APK 构建当作新界面的设备验收。整体目标继续进行。

本检查点验证：83 项 Kotlin/Android library 单测（API 8、engine 18、runtime-android 3、Domain 10、Data 8、UI 4、SDK 32）、Node.js 17、Python 8，共 108 项通过；JDK 17 下 Debug APK 与 lint 通过（仍有依赖版本和 ARM64 限定等既有警告）。主 APK 已覆盖安装并启动，设备建立 `interaction.db`，安装前后加密网关配置摘要一致。设备仍锁屏；未执行新 UI 的点击/键盘/语音验收，也未重复声称真网关通过。本轮未修改桥接或 CLI 执行协议，未重跑六种真 CLI 模拟网关联调。

## 技能管理与原生调用（2026-09-21）

技能页已接入真实管理端口：按当前 Agent 列出用户技能和 CLI 内置技能，可搜索、筛选、查看内容并加入本轮草稿。返回目录保留筛选与滚动位置。选择与移除增加草稿版本；已提交轮次保留冻结的技能引用，不受下一轮编辑影响。

- 手动创建填写名称、用途/场景、正文，校验预览后明确保存。文件导入使用系统文档选择器，Data 层读取有界 UTF-8 `.md` 内容；普通 Markdown 可补全元信息，带 YAML 的文件保留原文供修正。取消选择或返回不写入技能目录。
- Runtime 使用安全 YAML 数据解析，不构造任意对象；拒绝重复键、非法名称、空描述/正文、超限内容、越界与符号链接路径。同名技能拒绝覆盖；写入临时目录并同步文件后才移动到正式目录。运行期间拒绝修改技能目录，保留待保存表单。
- Codex 用户技能保存在 HOME 的 `.agents/skills`，Claude Code 保存在 `.claude/skills`；不改变已有 HOME 与工作区。引用包含 Agent、来源、名称及内容摘要，文件发生变化后旧引用不可继续提交。选择技能后使用对应 CLI 原生调用语法；Claude 明确使用 Skill 工具，保留原有权限检查。
- “与 mobby 对话创建”仅在实际发现可用 skill-creator 时开放。新会话预填需求、绑定 Creator，保留原会话草稿、选区和任务；Creator 不可用时不转成手动创建。生成产物的受控发现、预览及确认保存闭环仍待实现，不能将此入口等同于完整的对话创建验收。

真实 CLI 联调先发现 Claude 普通 Read 工具读取 HOME 下技能被权限限制；新增失败回归后改为原生 Skill 工具，未添加绕过权限参数。新增 `runtime/gateway-tests/skills-smoke.cjs`，在隔离 HOME 和虚假密钥下检查两个 CLI 的实际技能发现，以及真实工具返回技能正文。主机 Codex 为 0.155.0-alpha.9.2，Claude Code 为 2.1.112；不能替代 Android 固定版本与真网关验收。原三种网关协议的六种 CLI 工具回传组合也重新通过。

依据：[Codex 技能发现与调用](https://learn.chatgpt.com/docs/build-skills)、[Claude Code 技能](https://code.claude.com/docs/en/skills)。验证结果与设备状态在本节后补充；附件导入、创建技能产物保存、完整 Markdown、主题持久化、查找跳转、预测返回及设备交互验收仍未完成。

验证：94 项 Kotlin/Android library 单测（API 8、engine 22、runtime-android 9、Domain 10、Data 9、UI 4、SDK 32）、Node.js 17、Python 8，共 119 项通过；主 APK、辅助测试 APK 与 lint 构建通过。技能专项真实 CLI 两项及网关六种组合通过。主 APK 已覆盖安装并启动，原加密网关配置摘要一致；设备仍锁屏，未重试被系统拒绝的辅助测试安装，未把主机结果计入手机交互验收。
