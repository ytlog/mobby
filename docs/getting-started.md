# mobby

Android 本地 CLI Agent 运行验证工程。采用 Compose 持久会话页和内置 libtermux-android。

本文中的命令与非链接文件路径均以项目根目录为基准。

## 构建

需要 JDK 17、Android SDK 35、NDK 27.2.12479018、CMake 3.22.1、Python 3。
在 `local.properties` 中设置 `sdk.dir`，然后执行：

```sh
./gradlew :app:assembleDebug :interaction-domain:test :interaction-data:testDebugUnitTest :interaction-ui:testDebugUnitTest :runtime-api:test :runtime-engine:test :runtime-android:testDebugUnitTest :termux-core:testDebugUnitTest :app:lintDebug
```

Python 打包测试：`python3 -m unittest discover -s runtime -p 'test_*.py'`。
连接 ARM64 设备后运行：`./gradlew :app:connectedDebugAndroidTest`。

首次构建从 Termux 官方仓库和 npm 官方源下载固定 bootstrap、基础依赖及 CLI，校验 SHA-256 / npm integrity 后打包。下载缓存位于 `runtime/cache`，不提交 Git。

APK：`app/build/outputs/apk/debug/app-debug.apk`，仅 ARM64，Android 8.0 及以上。

## 使用与状态

打开 App 后自动安装 Git、Node.js、npm、Claude Code、Codex，并逐项执行版本检查、初始化 Git 工作区。安装包自带依赖，首次安装不需要手机联网下载。已有工作区和 HOME 中的认证文件保留。

从会话抽屉进入设置，可使用 Shell 诊断页；使用 Agent 时，先打开设置中的「网关设置」，分别为 Claude Code / Codex 保存地址、协议、模型和 API Key。支持 Chat Completions、Responses、Messages；网关配置可稍后填写，不需要官方账号登录。依赖异常时可重试初始化。
加号中的技能页支持手动创建和导入 `.md`，先校验预览再保存；选中的技能属于当前会话草稿，发送时重新校验。当前 Agent 提供 Skill Creator 时可另建创建会话，生成的技能草稿需在会话中校验并明确保存。
设置中的外观选择会保存；会话菜单的「在聊天中查找」可点击结果定位消息。
执行中可停止，输出可复制；切换会话保留各自草稿与阅读位置，不停止原任务。归档和删除可在设置中恢复，运行中的会话不允许归档或删除。

已在 ARM64 手机上验证五个依赖的实际版本、Node.js 执行和 Git 工作区初始化。手机已保存网关配置，接口连通性已验证；**手机端完整模型任务与工具流程仍待验收**。
网关协议测试：`node --test runtime/gateway-tests/bridge.test.cjs`。协议范围见 [网关说明](gateway.md)。
已实现内容、已知限制和验收步骤见 [实施记录](implementation.md)。

需求见 [项目需求](requirements.md)，方案见 [运行验证方案](design/runtime-test-plan.md)。
第三方来源及适配见 [third_party/NOTICE.md](../third_party/NOTICE.md)。

## 改名与升级兼容

项目名、源码 namespace 和界面品牌为 `mobby`，源码包为 `com.mobby.app`。Android `applicationId` 保留历史值 `com.mdoer.app`，Keystore 别名保留 `mdoer.gateway`，用于覆盖升级并继续读取已有配置、密钥和工作区；这些是安装兼容标识，不是界面名称。

本地目录及 Codex 持久项目记录均使用 `mobby`，关联任务路径已同步；旧目录名的符号链接已移除。Codex 当前窗口的缓存可能需要重启后刷新。

## 添加文本文件

在对话加号面板选择“本地文件”，通过系统选择器选取 UTF-8 文本。运行环境和当前网关配置就绪后可使用。每个文件最多 32 KiB，每轮最多 4 个；文字与附件编码后的总输入最多 64 KiB。文件处理完成后显示名称和大小，可移除；导入失败可重试或移除，原文字草稿保留。可以只发送附件，也可以补充任务描述。

已发送附件显示在对应用户消息中；更多菜单的“对话附件”区分历史附件与本轮草稿。图片、拍照和 PDF 暂不可用，不能通过改扩展名转换为受支持内容。

导入中退出界面不会取消已开始的处理；若应用进程中断，下次打开会显示“已中断”，可重试或移除。重试时若来源文件已移动、删除或授权失效，请移除待处理项并重新选择文件。未处理的导入不会随任务发送。

## 较长的会话

时间线通常先显示最近 40 轮，可点击顶部“加载更早的消息”。返回旧阅读位置或存在较早的运行任务时会加载所需范围。聊天查找、分享选择和附件列表会读取打开时的完整会话记录；查找旧消息后点击结果可回到原消息。
