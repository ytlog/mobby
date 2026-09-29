# mobby

Android 本地 CLI Agent 运行验证工程。采用 Compose 持久会话页和内置 libtermux-android。

本文中的命令与非链接文件路径均以项目根目录为基准。

## 构建

需要 JDK 17、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1、Python 3。
在 `local.properties` 中设置 `sdk.dir`，然后执行：

```sh
./gradlew :app:assembleDebug :interaction-domain:test :interaction-data:testDebugUnitTest :app:testDebugUnitTest :runtime-api:test :runtime-engine:test :runtime-android:testDebugUnitTest :termux-core:testDebugUnitTest :app:lintDebug
```

Python 打包与模块边界测试：`python3 -m unittest discover -s runtime -p 'test_*.py'`。
仓库的 `Runtime and architecture checks` 工作流在 push / pull request 时运行这些检查及 Node 桥接测试；不构建 Android 镜像，也不替代本地 APK 构建或真机验收。
连接 ARM64 设备后运行：`./gradlew :app:connectedDebugAndroidTest`。

首次构建从 Termux 官方仓库和 npm 官方源下载固定 bootstrap、基础依赖及 CLI，校验 SHA-256 / npm integrity 后打包。下载缓存位于 `runtime/cache`，不提交 Git。

APK：`app/build/outputs/apk/debug/app-debug.apk`，仅 ARM64，Android 8.0 及以上。

## 使用与状态

打开 App 后自动安装 Git、Node.js、npm、Pi、Claude Code、Codex、OpenCode，并逐项执行版本检查、初始化 Git 工作区。安装包自带依赖，首次安装不需要手机联网下载。已有工作区和 HOME 中的认证文件保留。OpenCode 启动失败时，Claude Code 与 Codex 仍可使用。

从会话抽屉进入设置，可使用 Shell 诊断页；首次进入会话首页且没有网关时，弹窗可直接带你到「网关设置」。自定义网关填写 Base 地址和 API Key 后，先点「获取模型」，再从下拉菜单搜索、筛选并选择默认模型；模型列表不可用时也可手动添加模型 ID。点「保存网关」时应用自动探测可用 Agent，通过后才保存。网关列表中点按整个网关即可用于当前会话并设为新会话默认，选中后可在下方选择当前会话模型；新建会话中选择网关也会确定 Agent。Claude Code 使用 Messages，Pi、Codex 与 OpenCode 使用 Responses；模型请求统一走本地桥接，暂不提供协议转换。网关配置可稍后填写，不需要官方账号登录。依赖异常时可重试初始化。
加号中的技能页支持手动创建和导入 `.md`，先校验预览再保存；选中的技能属于当前会话草稿，发送时重新校验。当前 Agent 提供 Skill Creator 时可另建创建会话，生成的技能草稿需在会话中校验并明确保存。
加号中的插件页按手机、沟通、文件列出设备能力。把插件加入本轮草稿即授权这一轮使用；未加入时不启动对应命令通道。屏幕需先在系统无障碍设置中开启「屏幕」服务，并允许应用通知及「任务执行」通知渠道（通知提供停止入口），存储需选择一个目录树，其他危险权限在开启时由系统对话框授予。发送短信、修改通讯录、修改日历和写入剪贴板是单独的引用，默认关闭。已加入后，Claude 的每次操作确认由应用自动允许，不再逐次弹出卡片。运行时只把本轮启用的动作写进 `mobby-<能力>/SKILL.md`，脚本经 `127.0.0.1` 调用应用；技能装进当前 Agent 的技能目录（Pi：`~/.pi/agent/skills`，Codex：`~/.agents/skills`，Claude Code：`~/.claude/skills`，OpenCode：`~/.config/opencode/skills`）。提示只给出 `/skill:mobby-<能力>`、`$mobby-<能力>` 或 `/mobby-<能力>` 和技能文件路径。若用户已有同名非临时技能，这一轮失败且不覆盖该文件。不改写用户 `config.toml` 或 Claude 配置，结束后移除本次技能。切换 Agent 时保留 `plugin:device:` 引用，技能选择仍按 Agent 清除。说明见 [设备插件方案](design/device-plugins.md)。
空会话页以两行两列展示「使用手机」「查看照片」「访问文件」「使用相机」四个插件。点击会把可用插件直接加入当前会话草稿；若尚未获得系统授权，会直接启动对应的系统权限申请、目录选择或无障碍设置，返回后检查权限并自动加入，不经过插件列表。已加入的插件会在输入框上方显示，可点叉移除。
对话生成技能使用结构化 JSON 输出，Pi 由提示约束并在 App 严格校验，Codex 与 Claude 使用 CLI 原生 schema，由应用生成有效的 SKILL.md；模型未返回合规结构时会明确失败，不把普通回复登记为技能。Codex 已在真机验证生成、确认保存、重新发现与新会话实际调用。
设置中的外观选择会保存；会话菜单的「在聊天中查找」可点击结果定位消息。设置里可以打开桌面悬浮球。离开应用后，桌面上显示可拖动的悬浮球；运行中显示动态蓝点，等待确认、停止中、完成和失败分别用球上的标记提示。点按可查看状态、停止运行中的任务或回到对话。任务完成后的标记会保留到打开对话、回到应用或开始新任务。第一次打开需要允许显示在其他应用上层。回到应用后它会暂时隐藏，离开后重新出现。
执行中可停止，输出可复制；切换会话或页面、把应用切到后台，都不停止正在运行的任务。同一对话里，Pi、Codex 与 Claude Code 各自复用自己的 CLI 会话：进程还在就直接追加下一条，进程退出后才恢复。OpenCode 每一轮启动一次 `opencode run`，下一轮用 `--session` 冷启动恢复。只有新建对话才从头开始。中途更换 Agent 仍留在当前对话，并为那个 Agent 单独建立或恢复会话，不会沿用另一边的上下文。归档和删除可在设置中恢复，运行中的会话不允许归档或删除。

已在 ARM64 Android 13 手机上验证运行环境、真实网关下的 Agent 执行、Codex 项目工作区工具调用、Claude 审批与取消，以及 Codex 与 Claude Code 的图片输入。OpenCode 的手机执行尚未验收。相机已验证真实拍摄、预览确认加入草稿，以及预览期间进程结束后的恢复与取消。语音完整转写、TalkBack 和其他 Android 版本的部分系统行为仍待验收；逐项证据见实施记录。
网关协议测试：`node --test runtime/gateway-tests/bridge.test.cjs`。协议范围见 [网关说明](gateway.md)。
已实现内容、已知限制和验收步骤见 [实施记录](implementation.md)。

需求见 [项目需求](requirements.md)，方案见 [运行验证方案](design/runtime-test-plan.md)。
第三方来源及适配见 [third_party/NOTICE.md](../third_party/NOTICE.md)。

## 安装身份与开发数据

项目名和界面品牌为 `mobby`。Android `applicationId` 与应用源码包为 `com.github.ytlog.mobby.android`。交互、运行时和语音模块的包名使用同一前缀，例如 `com.github.ytlog.mobby.android.interaction.ui`。Keystore 别名是 `mobby.gateway`。

更换 `applicationId` 后，此前已安装的应用不会带入配置、密钥、HOME 和工作区。同一 `applicationId` 的覆盖安装仍保留 SharedPreferences、HOME 和工作区。交互数据库只维护当前结构；再次修改表结构前须清除 `interaction-current.db`，随后按新结构创建，不维护旧版本迁移。

## 添加文件与照片

在对话加号面板选择“本地文件”，通过系统选择器选取 UTF-8 文本。运行环境和当前网关配置就绪后可使用。每个文件最多 32 KiB，每轮最多 4 个；文字与附件编码后的总输入最多 64 KiB。文件处理完成后显示名称和大小，可移除；导入失败可重试或移除，原文字草稿保留。可以只发送附件，也可以补充任务描述。

已发送附件显示在对应用户消息中；更多菜单的“对话附件”区分历史附件与本轮草稿。照片可通过系统照片选择器添加并预览，发送时作为原生图片输入交给 Agent。拍照先打开系统相机，回到应用预览并确认后才加入原会话草稿，不会自动发送；取消保留原草稿。PDF 暂不支持，不能通过改扩展名转换为受支持内容。

手动添加的附件及设备拍照、录音和导出的媒体文件保存在应用私有的持久附件目录，默认上限 512 MiB；容量不足时拒绝新资源，不删除现有附件。屏幕截图和自动缩略图保存在独立的 LRU 缓存，默认上限 128 MiB；写入空间不足时淘汰最久未使用的缓存，读取和预览会刷新使用时间。两项上限均可在「设置 → 存储与保留」调整。缓存被淘汰后原引用仍保留，但资源无法再打开或发送。工作区文件不属于这两处存储。

导入中退出界面不会取消已开始的处理；若应用进程中断，下次打开会显示“已中断”，可重试或移除。重试时若来源文件已移动、删除或授权失效，请移除待处理项并重新选择文件。未处理的导入不会随任务发送。

## 较长的会话

时间线通常先显示最近 40 轮，可点击顶部“加载更早的消息”。返回旧阅读位置或存在较早的运行任务时会加载所需范围。聊天查找、分享选择和附件列表会读取打开时的完整会话记录；查找旧消息后点击结果可回到原消息。

Pi 0.87.1 已加入内置运行环境，在没有选择记录时作为新会话默认 Agent。初始化保留已有的 Agent 与网关选择；用户在会话配置中点击「应用」或新建会话中点击「创建」后，下次新建会话沿用这次选择的 Agent，项目内新建和重启应用后也一样；关闭菜单而未确认不改变记录。旧会话不切换 Agent，原共享技能会在内容一致且无同名冲突时补入 Pi 目录。接入与验证细节见 [Pi Agent](design/pi-agent.md)。
