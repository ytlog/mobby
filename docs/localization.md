# 界面语言

在「设置 → 语言」选择「中文」或「English」。默认中文；切换立即刷新 Compose 界面，并保存到应用的 SharedPreferences，重启后继续使用所选语言。保存失败会提示错误并保持原语言。切换不会重新创建 Activity、重启 Agent、清空草稿或更改网关配置。

## 文案维护

- `shared/localization/src/main/kotlin/com/github/ytlog/mobby/android/localization/AppStrings.kt`：统一的 Kotlin 文案目录，`StringCatalog` 同时定义中文和英文；有参数的文案使用命名函数与位置占位符，不在界面拼接句子。
- `app/src/main/kotlin/com/github/ytlog/mobby/android/interaction/ui/UiStrings.kt`：Compose 可观察入口及语言偏好存储。界面通过 `UiStrings` 读取；运行层通过 `AppStrings` 读取，应用启动时先恢复语言再创建运行服务。
- `CatalogIds` 的分类与来源标识不随语言变化；数据库中已有的内部状态标识也不翻译。不要再通过错误文案中的关键词判断成功或失败。
- Node 桥接位于独立进程，诊断文案集中在 `runtime/android/src/main/assets/gateway/gateway-strings.cjs`；每次运行从 `MOBBY_LANGUAGE` 取得语言。网络错误只显示受控提示，不回显可能携带密钥的异常文本。
- Android 系统展示的无障碍服务名称与说明使用 `runtime/device-plugins/src/main/res/values*/strings.xml`，遵循系统语言。
- `AgentPrompts.kt` 保存固定的结构化输出协议指令，不因显示语言改变协议要求。

用户输入、已有会话标题、技能正文、模型回复、第三方 CLI 输出和已生成的历史日志保留原文。新产生的应用提示采用当前语言；语言选项不表示切换语音识别模型或自动翻译对话。

## 验证

`AppStringsTest` 遍历文案，检查两种语言完整性以及参数原样保留。Compose 测试覆盖设置页即时切换、持久化恢复、保存失败，以及语言切换后的插件分类。回归测试还覆盖首次发送前切换语言时的自动会话命名。

2026-09-24 集成验证：JDK 17 构建 APK、应用/termux-core 单测及 Lint 通过；本地化、交互域、运行引擎、Android 运行层、设备插件、语音、Node 桥接和 Python 检查通过。UI 在共享测试进程中曾出现 Espresso 空闲等待超时，使用临时 Gradle init 脚本设置 UI Test 的 `forkEvery = 1` 后，整批测试通过；数据层会话恢复测试曾等待超时，单独复跑通过。未修改生产代码来绕过这些超时。已覆盖安装到连接手机，设置页中英文显示正常，用户确认实测通过。
