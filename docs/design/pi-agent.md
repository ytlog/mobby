# Pi Agent

Pi 0.87.1 已作为第四个 Agent 接入，并成为新会话的默认选择。Shell、Codex、Claude Code 和 OpenCode 的原执行路径保留。

## 运行与协议

- 官方 npm 包为 `@earendil-works/pi-coding-agent`。全部传递依赖固定在 `runtime/pi-package/package-lock.json`，构建时校验 npm integrity，禁用安装脚本和可选平台二进制；Android 原生启动器调用内置 Node.js 的 `dist/bundle/cli.js`。
- `PiRpcSession` 通过双向 JSONL RPC 发送 `get_state`、`prompt` 与 `steer`。首轮以生成的 UUID 启动 `--session-id`，恢复时使用 `--session`；状态响应必须与指定会话一致。会话保存在 `~/.pi/agent/sessions`，进程存活时直接提交下一轮。
- CLI 接受 prompt 不表示完成。`agent_end` 后可能自动重试，只有 `agent_settled` 才结束本轮；最终 assistant 的 stopReason 必须是 `stop`。错误、取消、输出截断和无有效终态均不能成功。
- 解码器把 text/thinking 增量与最终快照合并，工具开始、更新、结束映射到已有步骤卡，保留真实工具结果。图片作为 base64 RPC image 内容传入，不经 Shell 参数，不自动缩放。技能生成使用提示中的 JSON 契约，严格校验后才发布提案；Pi 不宣称支持原生 JSON schema 强制输出。
- 网关仅开放 Responses。`mobby` provider 显式使用 `openai-responses`，请求经本地 Node 桥接，由 Android 网络栈联网。临时模型文件只包含鉴权 loopback 地址及本地令牌环境变量引用，不含上游地址或密钥，不修改用户 `models.json`、`auth.json` 或 `settings.json`。禁用动态模型更新、版本检查、第三方扩展、模板和主题加载。
- 内置工具在 Android 应用 UID/SELinux 沙箱内运行，不获取 root，也不逐次请求确认。已有 JNI 执行器负责取消、超时和进程组回收；正常退出和 SIGTERM 会移除本次临时模型文件。

## 默认选择与技能

升级首次选用原默认网关中兼容 Pi 的 Responses 路由；没有则选首个兼容网关。不转换 Messages 网关，不改写网关记录、模型、密钥和会话。只有 Messages 时继续显示兼容 Agent。用户后续主动修改默认选择后，不再自动切回 Pi。

新入口和无会话的技能页优先使用 Pi。旧会话继续使用自己的 Agent，会话内切换 Agent 后，各自的 session 独立保存。

共享技能增加 `~/.pi/agent/skills`，显式调用为 `/skill:<name>`；RPC 同时加载当前项目的 `.pi/skills`。旧共享技能仅在原三个目录都有有效 `.mobby-shared` 副本、元信息、内容和配套资源完全一致、Pi 目录不存在同名文件时补入，并保留脚本执行属性。冲突目录保留，避免覆盖用户数据。设备与应用功能技能仍按本轮授权临时挂载。

## 验证

单元测试覆盖会话身份、RPC 失败、图片、流式去重、工具失败、真实终态、重试、技能生成、默认候选与共享技能升级。`pi-rpc-smoke.cjs` 使用真实锁定 CLI、隔离 HOME 与模拟 Responses，覆盖图片、中文、个人与项目技能发现、read/write/edit/bash、多轮、执行中追加指令、冷恢复、错误、取消和配置隔离。

`pi-android-smoke.cjs` 通过 adb reverse 调用已安装 App 的 `PiRpcDeviceTest`，使用内置 ARM64 CLI、真实 JNI 管道与 Android DNS 网络桥接，检查图片、文件工具、多轮、冷恢复及取消清理。模拟网关的结果不能替代真实网关、真实模型及设备插件的端到端验收。

2026-09-28 已在 M2007J1SC / Android 13 覆盖安装并通过上述手机门槛，另通过真实 Keystore 的默认升级、用户后续选择保留及网关版本保留测试。Node 桥接测试 15 项和 bootstrap/依赖打包测试 10 项通过。JDK 17 的构建、Runtime/领域单元测试、App/termux-core 单元测试与 lint 通过。全量 Python 检查仍有一项既有失败：`test_module_boundaries.py` 的允许列表尚未包含项目原有 `runtime-android -> plugin:appfunction` 依赖。

扩大到全部 interaction-ui 测试时，154 项中有 7 项失败：6 项技能编辑器生命周期测试找不到名称输入节点，另 1 项网关页面测试找不到旧说明文案。在隔离目录把技能页默认 Agent 和共享技能说明恢复为原行为后，6 项编辑器失败仍可复现；本次不修改这些页面行为或既有测试。Pi 默认选择与四个 Agent 图标另有针对性验证。

官方接口依据：[RPC](https://github.com/earendil-works/pi-mono/blob/main/packages/coding-agent/docs/rpc.md)、[自定义模型](https://github.com/earendil-works/pi-mono/blob/main/packages/coding-agent/docs/models.md)、[技能](https://github.com/earendil-works/pi-mono/blob/main/packages/coding-agent/docs/skills.md)。
