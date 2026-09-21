# Android AI Agent 测试方案

## 1. 目标

在 Android App 中运行 Claude Code、Codex，通过一个 Compose 页面输入任务并查看实时输出。

## 2. 技术方案

| 部分 | 实现 |
| --- | --- |
| 开发语言 | Kotlin |
| 页面 | Jetpack Compose、Material 3 |
| 页面状态 | ViewModel、StateFlow |
| 运行环境 | libtermux-android |
| Agent | Claude Code、Codex CLI |

`libtermux-android` 以源码模块接入，使用 `core` 和 `bootstrap-arm64`。Shell、CLI 和文件操作在 App 内执行，CLI 通过网络访问模型服务。

## 3. 测试页面

页面包含：

- 模式选择：Shell、Claude Code、Codex。
- 输入框：输入命令或任务。
- 发送按钮：提交输入，执行期间禁用。
- 输出列表：持续追加执行输出和错误信息。
- 状态文字：初始化中、就绪、执行中、完成、失败。
- 停止按钮：终止当前任务。

输出区域使用 `LazyColumn`，输入使用多行 `OutlinedTextField`。ViewModel 保存输入、当前模式、执行状态和输出内容，通过 StateFlow 更新页面。

## 4. 执行流程

1. App 启动，初始化 libtermux-android 并检查运行依赖。
2. 用户选择模式，输入内容并点击发送。
3. ViewModel 将请求交给运行层。
4. 运行层启动命令或 Agent，持续读取 stdout 和 stderr。
5. 页面追加输出，执行结束后显示结果与退出状态。

同一时间执行一个任务。空白输入或环境未就绪时禁用发送，初始化及执行错误直接显示在输出区域。

## 5. 运行层

| 模块 | 职责 |
| --- | --- |
| RuntimeEnvironment | 初始化 SDK、管理工作目录、检查依赖 |
| AgentRunner | 启动进程、读取输出、停止任务、返回退出状态 |
| ClaudeAdapter | 构造 Claude Code 参数并解析输出 |
| CodexAdapter | 构造 Codex 参数并解析输出 |

Shell 模式执行单次命令，Agent 模式使用非交互调用：

```text
Claude Code: claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio
Codex:       codex exec --json <prompt>
```

运行层以参数数组启动 CLI，并发读取 stdout/stderr，按完整 JSON 行解析 Agent 事件。Claude 通过双向 stdin JSONL 先初始化、再发送文本/图片与审批决定；原始任务正文不放入 argv，等待决定时不能关闭 stdin。Codex 按其原生参数传递输入。参数与输出格式以实际安装版本为准。

SDK 集成时修正运行路径硬编码、流式输出延迟和管道读取阻塞问题。安装运行依赖与 CLI 后，完成认证并执行最小任务检查。

## 6. 文件与任务管理

工作目录放在 SDK HOME 下的 `workspace`，初始化为 Git 仓库。认证与任务执行使用相同的 SDK 配置和 HOME，凭据不写入日志。

任务由运行服务持有；页面重建后恢复状态。停止或超时时终止任务及其子进程，保留已有输出。App 进程被系统终止后显示任务中断。

## 7. 实施顺序

1. 编写 Compose 输入输出页面。
2. 接入 libtermux-android，执行 Shell 命令并显示真实输出。
3. 安装并验证 Claude Code、Codex。
4. 接入 Agent 输入输出，补充停止、状态和错误处理。

## 8. 验收

- 页面可以输入命令或任务，执行期间持续显示真实输出。
- Shell 可以执行命令并读写工作目录文件。
- Claude Code、Codex 均可以接收任务、执行基础工具并返回结果。
- 执行失败有错误信息，停止操作能够结束任务。

记录真机配置、SDK 与 CLI 版本、执行结果。当前文档为设计方案，运行能力待实现验证。

## 前台服务平台验收补充

Runtime 当前以 specialUse 声明用户启动的本地终端任务，不再声明 dataSync。Android 14/15 设备需验证：在可见界面启动 Shell/Agent 后切换应用，通知持续显示且可返回停止；退出确认后通知移除；平台拒绝启动时请求不被接纳；系统终止宿主后重新进入只恢复/核实原任务，不自动重发。检查最终 APK 的服务类型、权限及 subtype，不以源码清单代替合并结果。

超时入口的阻塞清理、清理失败和原因保留由 Robolectric/API 34 与引擎单测覆盖；尚未完成 Android 15 实机验收。不要用 dataSync 专用配额测试推断 specialUse 的真实行为，也不要把显式调用回调的测试描述为操作系统实际触发了超时。

## 原生审批验收补充

Claude 应用链路已接通。主机使用生产 Kotlin 控制会话、固定 CLI 和模拟模型验证允许、拒绝、待审批取消及恢复会话；手机原有网关的三次工具运行已由用户点击 ALLOW_ONCE，Runtime 持久事件与文件内容相互印证。手机应用内拒绝与待审批取消已通过：拒绝记录 DENY，最终为 PERMISSION_DENIED；停止记录 CANCELLED/exit 143，两项均未创建目标文件。待审批时短暂返回桌面再打开，卡片仍保留；旋转、长时间后台、宿主重启/断连恢复，以及新控制通道上的真实图片模型请求仍需验证。审批验收以 ApprovalRequired/ApprovalResolved 日志、CLI 工具结果和文件副作用共同确认，不能因轮询没有看到卡片就推断未发生审批。详见[实施记录](../implementation.md)。
