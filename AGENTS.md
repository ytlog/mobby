# mobby 项目开发约定

## 先纠正基础，再继续迭代

- 不得在已知错误的实现、错误假设或不符合当前需求的架构上继续堆叠功能。
- 修改前先核对用户的最新需求、现有代码与真实运行行为，区分已验证事实和未经验证的假设。
- 发现问题时先定位根因；必要时重构或替换错误实现，使主路径直接实现正确功能。不要用额外条件、异常吞掉、伪造成功或不断增加兼容层来掩盖错误。
- 重构范围以修复问题为限，保留仍有效的功能、用户数据和配置；删除失效逻辑，避免新旧两套实现并存但职责不清。
- 对可复现的缺陷先添加会失败的回归测试，再修复。测试应验证用户可观察行为和协议约束，不应只重复实现逻辑。
- 完成后运行相关测试与构建；明确说明已修复问题、验证证据及尚未验证的范围。模拟网关成功不能替代手机或真实网关验收。

## 本项目的实现边界

- 使用内置 libtermux-android 和 Compose，保留 Shell、Claude Code、Codex、OpenCode 的执行能力。
- 按当前用户决定，现有 App 内 Agent 的模型请求统一走本地 Node 桥接，由 Android 网络栈联网；Codex 与 OpenCode 仅使用 Responses，Claude Code 仅使用 Messages。现有 App 的远端网关保持原生协议透传。另有独立安装的本地模型服务设计，可在它自己的 `:local_model` 进程内提供 Responses、Chat Completions、Messages、Gemini、Ollama HTTP 协议适配，见 `docs/design/local-model-runtime.md`；该服务不依赖或修改现有 App/Node 桥接/Agent 功能。协议适配不得静默丢弃字段，不支持的语义须明确拒绝；该设计不代表已经实现。
- 网关选项必须有实际执行路径；保留但拒绝执行旧的不匹配协议配置，不静默改写用户配置，不丢弃工具、图片、推理或未知消息字段。
- 按用户明确授权，Codex、Claude Code 与 OpenCode 都以 Android 应用 UID/SELinux 沙箱为执行边界，不获取 root、不修改系统权限。Codex 使用 danger-full-access 与 approval_policy=never。Claude Code 仍走 stdio 审批协议，但应用对格式正确的 can_use_tool 立即按原始参数允许一次，不再弹出确认卡；否则使用当前手机时每次操作都会停住。OpenCode 使用当次 `--auto`，不写持久权限规则。三者都不使用 bypass 参数。取消、错误和超时必须如实传递，不能显示为成功。
- 网关密钥和真实用户配置仅存放于设备的加密存储；不得写入源码、测试夹具、文档、日志或 Git。
- 当前 Android `applicationId` 与应用源码包为 `com.github.ytlog.mobby.android`，现有 App 的其余模块包使用同一前缀；独立本地模型服务另用自身包名。Keystore 别名是 `mobby.gateway`。再次更改 applicationId 或 Keystore 别名必须先设计数据迁移方案。更换 applicationId 后，此前已安装的应用不会带入配置、密钥、HOME 和工作区。
- 开发阶段数据库结构变化直接清空对应开发数据库并按当前结构重建，不维护旧版本迁移、升级兼容代码或历史迁移测试。保留设备 SharedPreferences、HOME 和工作区；安装更新使用覆盖安装。
- 构建产物、下载缓存和本地机器配置不得提交。

## Git 提交规范

- 完成独立且验证通过的功能、修复或重构后，必要时主动提交 commit，形成可回溯的检查点，不必每次等待用户提醒。用户明确要求暂不提交时遵从用户要求。
- 提交应围绕一个完整目的，包含对应代码、测试和必要文档；避免把尚未修正的错误实现作为后续迭代基础，也不要混入无关改动或他人未授权提交的工作。
- 提交前检查 `git status`、实际 diff 和暂存内容，运行与改动相关的检查，确认没有密钥、真实用户配置、构建产物或缓存。仅文档修改无需重复运行完整构建。
- commit message 清楚描述修改目的；提交后确认结果并向用户报告 commit ID、验证情况和剩余工作。
- 本地 commit 不代表授权推送远端、合并或发布；这些操作遵循用户的单独授权。不要擅自改写已有提交历史。

## 常用验证

- 不得自行下载新的 Android 系统镜像；设备验证优先使用已连接手机和已有环境，避免额外占用带宽与磁盘。

- `node --test runtime/gateway-tests/bridge.test.cjs`
- `python3 -m unittest discover -s runtime -p 'test_*.py'`
- 使用 JDK 17 执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :termux-core:testDebugUnitTest :app:lintDebug`
- 真 CLI 的模拟网关联调使用 `runtime/gateway-tests/cli-smoke.cjs`；使用隔离的 HOME 和虚假测试密钥。

## 文档管理

- 项目自有需求、方案、使用说明和实施记录统一放在 `docs/`，设计文档放在 `docs/design/`；根目录仅保留精简的 `README.md` 与本文件。
- `docs/README.md` 维护文档索引。文档迁移或重命名时同步修复项目内引用，不保留失效链接或重复设计副本。
- 第三方源码自带文档、许可证及配套资源保留原位；构建产物、缓存、本地配置和真实网关配置不得混入文档。
