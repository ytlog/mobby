# mobby

Android 本地 CLI Agent 运行验证工程。采用 Compose 持久会话页和内置 libtermux-android。

本文中的命令与非链接文件路径均以项目根目录为基准。

## 构建

需要 JDK 17、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1、Python 3。
在 `local.properties` 中设置 `sdk.dir`，然后执行：

```sh
./gradlew :app:assembleDebug :interaction-domain:test :interaction-data:testDebugUnitTest :interaction-ui:testDebugUnitTest :runtime-api:test :runtime-engine:test :runtime-android:testDebugUnitTest :termux-core:testDebugUnitTest :app:lintDebug
```

Python 打包与模块边界测试：`python3 -m unittest discover -s runtime -p 'test_*.py'`。
仓库的 `Runtime and architecture checks` 工作流在 push / pull request 时运行这些检查及 Node 桥接测试；不构建 Android 镜像，也不替代本地 APK 构建或真机验收。
连接 ARM64 设备后运行：`./gradlew :app:connectedDebugAndroidTest`。

首次构建从 Termux 官方仓库和 npm 官方源下载固定 bootstrap、基础依赖及 CLI，校验 SHA-256 / npm integrity 后打包。下载缓存位于 `runtime/cache`，不提交 Git。

APK：`app/build/outputs/apk/debug/app-debug.apk`，仅 ARM64，Android 8.0 及以上。

## 使用与状态

打开 App 后自动安装 Git、Node.js、npm、Claude Code、Codex，并逐项执行版本检查、初始化 Git 工作区。安装包自带依赖，首次安装不需要手机联网下载。已有工作区和 HOME 中的认证文件保留。

从会话抽屉进入设置，可使用 Shell 诊断页；使用 Agent 时，先打开设置中的「网关设置」，分别为 Claude Code / Codex 保存地址、模型和 API Key。Claude Code 使用 Messages，Codex 使用 Responses；模型请求统一走本地桥接，暂不提供协议转换。网关配置可稍后填写，不需要官方账号登录。依赖异常时可重试初始化。
加号中的技能页支持手动创建和导入 `.md`，先校验预览再保存；选中的技能属于当前会话草稿，发送时重新校验。当前 Agent 提供 Skill Creator 时可另建创建会话，生成的技能草稿需在会话中校验并明确保存。
加号中的插件页目前提供「使用当前手机」：需先在系统无障碍设置中开启 mobby 的同名服务，再把插件加入本轮草稿。未开启或未加入时，Agent 不能操作本机；应用不会自行打开系统权限。已加入后，Claude 的每次操作确认由应用自动允许，不再逐次弹出卡片。本轮选中后按 Codex 桌面插件交付：`plugin.json` + `skills/use-current-phone/SKILL.md` + `scripts/phone.cjs`。运行时把该技能装进当前 Agent 的技能目录（Codex：`~/.agents/skills`，Claude Code：`~/.claude/skills`），并在提示中带上 `$use-current-phone` / `/use-current-phone` 与 helper 命令。不改写用户 `config.toml` 或 Claude 配置，结束后移除本次技能。后续 Agent 只要能加载同一套 SKILL.md 即可接入。插件选择在切换 Agent 时保留，技能选择仍按 Agent 清除。
对话生成技能使用 CLI 原生结构化输出，由应用生成有效的 SKILL.md；模型未返回合规结构时会明确失败，不把普通回复登记为技能。Codex 已在真机验证生成、确认保存、重新发现与新会话实际调用。
设置中的外观选择会保存；会话菜单的「在聊天中查找」可点击结果定位消息。
执行中可停止，输出可复制；切换会话或页面、把应用切到后台，都不停止正在运行的任务。同一对话里，Codex 与 Claude Code 各自复用自己的 CLI 会话：进程还在就直接追加下一条，进程退出后才恢复；只有新建对话才从头开始。中途更换 Agent 仍留在当前对话，并为那个 Agent 单独建立或恢复会话，不会沿用另一边的上下文。归档和删除可在设置中恢复，运行中的会话不允许归档或删除。

已在 ARM64 Android 13 手机上验证运行环境、真实网关下的 Agent 执行、Codex 项目工作区工具调用、Claude 审批与取消，以及两种 Agent 的图片输入。相机已验证真实拍摄、预览确认加入草稿，以及预览期间进程结束后的恢复与取消。语音完整转写、TalkBack 和其他 Android 版本的部分系统行为仍待验收；逐项证据见实施记录。
网关协议测试：`node --test runtime/gateway-tests/bridge.test.cjs`。协议范围见 [网关说明](gateway.md)。
已实现内容、已知限制和验收步骤见 [实施记录](implementation.md)。

需求见 [项目需求](requirements.md)，方案见 [运行验证方案](design/runtime-test-plan.md)。
第三方来源及适配见 [third_party/NOTICE.md](../third_party/NOTICE.md)。

## 安装身份与开发数据

项目名和界面品牌为 `mobby`。Android `applicationId` 与应用源码包为 `com.github.ytlog.mobby.android`。交互、运行时和语音模块的包名使用同一前缀，例如 `com.github.ytlog.mobby.android.interaction.ui`。Keystore 别名是 `mobby.gateway`。

更换 `applicationId` 后，此前已安装的应用不会带入配置、密钥、HOME 和工作区。同一 `applicationId` 的覆盖安装仍保留 SharedPreferences、HOME 和工作区。开发阶段数据库结构变化直接重建对应开发数据库，不维护旧版本迁移。

## 添加文件与照片

在对话加号面板选择“本地文件”，通过系统选择器选取 UTF-8 文本。运行环境和当前网关配置就绪后可使用。每个文件最多 32 KiB，每轮最多 4 个；文字与附件编码后的总输入最多 64 KiB。文件处理完成后显示名称和大小，可移除；导入失败可重试或移除，原文字草稿保留。可以只发送附件，也可以补充任务描述。

已发送附件显示在对应用户消息中；更多菜单的“对话附件”区分历史附件与本轮草稿。照片可通过系统照片选择器添加并预览，发送时作为原生图片输入交给 Agent。拍照先打开系统相机，回到应用预览并确认后才加入原会话草稿，不会自动发送；取消保留原草稿。PDF 暂不支持，不能通过改扩展名转换为受支持内容。

导入中退出界面不会取消已开始的处理；若应用进程中断，下次打开会显示“已中断”，可重试或移除。重试时若来源文件已移动、删除或授权失效，请移除待处理项并重新选择文件。未处理的导入不会随任务发送。

## 较长的会话

时间线通常先显示最近 40 轮，可点击顶部“加载更早的消息”。返回旧阅读位置或存在较早的运行任务时会加载所需范围。聊天查找、分享选择和附件列表会读取打开时的完整会话记录；查找旧消息后点击结果可回到原消息。
