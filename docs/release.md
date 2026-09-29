# GitHub 手动发布 Android Release

仓库的 **Publish Android release** 工作流只通过 GitHub Actions 的 **Run workflow** 手动运行。它使用不含签名 secrets 的构建 runner 执行运行时检查、Android 单测和 lint，构建经过 R8 压缩、优化、混淆及资源收缩的 ARM64 未签名 release APK，再由独立 runner 使用原发布密钥签名、验证签名和版本信息，然后创建 `v<版本>` 标签与 GitHub Release。APK 与 SHA-256 校验文件是公开的 Release 附件；R8 `mapping.txt` 以 age 接收者加密后保存在 Actions artifact 中 90 天，用于崩溃堆栈反混淆。

## 首次设置

1. 准备一个长期保存的 Android 签名 keystore。若已有对外发布的同一 `applicationId`，必须使用它原来的签名密钥；签名不同的 APK 不能覆盖安装。不要使用开发机的 debug keystore。妥善离线备份 keystore、别名和口令。项目源码包与 `applicationId` 均为 `com.github.ytlog.mobby.android`。
2. 在 GitHub 仓库 **Settings → Secrets and variables → Actions → Repository secrets** 添加：

   | Secret | 内容 |
   | --- | --- |
   | `MOBBY_RELEASE_KEYSTORE_BASE64` | keystore 文件的单行 Base64 内容 |
   | `MOBBY_RELEASE_STORE_PASSWORD` | keystore 口令 |
   | `MOBBY_RELEASE_KEY_ALIAS` | 签名密钥别名 |
   | `MOBBY_RELEASE_KEY_PASSWORD` | 密钥口令 |

3. 确认仓库允许 Actions 使用 `GITHUB_TOKEN` 创建 Release。工作流声明 `contents: write`，无需另建个人访问令牌。

签名文件和口令存放在仓库目录之外，不能提交 Git。Debug 保持 Android 默认开发签名；上述 Secrets、release 签名和 R8 配置只用于 release。部分签名环境变量缺失时，release 的签名校验会失败，Debug 打包不依赖这些变量。工作流安装 SDK 35 和 36，以满足当前各模块的编译要求。

GitHub 临时 runner 会在构建前清理未使用的 .NET、Haskell 和 CodeQL 工具，给 bootstrap、原生库和 APK 中间产物留出磁盘空间；此步骤不作用于开发机。

R8 仅忽略三个已确认可选的缺失类：Ktor 2.3.12 的桌面 JVM 调试探测使用 `ManagementFactory` 和 `RuntimeMXBean`，其[实现](https://github.com/ktorio/ktor/blob/2.3.12/ktor-utils/jvm/src/io/ktor/util/debug/IntellijIdeaDebugDetectorJvm.kt)会捕获不可用错误并返回 false；SLF4J 1.7 的 `StaticLoggerBinder` 缺失时，按[官方说明](https://www.slf4j.org/codes.html#StaticLoggerBinder)使用自带的 NOP 日志实现。这些规则不关闭 R8，也不放行其他缺失依赖。

编译前会检查锁定依赖的下载地址。Termux 仓库会移除旧包；检查报告 404 时，应根据官方 Packages 索引更新对应包在 `runtime/agents.lock.json` 中的版本、地址和 SHA-256，并校验下载文件。构建仍只使用锁定版本，不自动改用最新版或跳过校验。

可在本机用以下命令生成第一个 Secret 的值；不要把输出保存到仓库文件中：

```sh
base64 < path/to/release.jks | tr -d '\n'
```

## 发布

1. 将待发布代码合入仓库默认分支。到 **Actions → Publish Android release → Run workflow**，选择默认分支；工作流会拒绝从其他分支发布。
2. 输入 `version`，例如 `0.1.0`；输入递增的正整数 `version_code`，例如 `1`。前者会写入 APK `versionName` 并生成 `v0.1.0` 标签；后者写入 Android `versionCode`，每次覆盖升级都必须高于前一版。同名标签已存在时默认停止。如需重新发布同一版本，勾选 `replace_existing` 并递增 `version_code`；新 APK 构建和校验通过后，工作流替换附件、更新标签到本次提交，并保留原发布说明。
3. 工作流成功后，在仓库 **Releases** 下载 `mobby-v<版本>-arm64-v8a.apk`，并用 `SHA256SUMS.txt` 校验。失败时不会创建新 Release；先查看失败步骤并修复后重跑。

仅支持 ARM64 和 Android 8.0 及以上。签名和构建成功不能替代在真实手机上安装、覆盖升级和验证 Shell、Pi、Claude Code、Codex、OpenCode、网关及本地模型；首次发布前应完成这些验收。当前 debug APK 使用 Android 开发签名，通常不能被 release APK 直接覆盖；请先备份需保留的设备数据。

## 本地验证

不设置四个签名环境变量时，`./gradlew :app:assembleRelease` 生成未签名的优化 APK，便于本地检查 R8；发布工作流则要求四个 GitHub Secrets 齐备。需要本地签名时设置 `MOBBY_RELEASE_STORE_FILE`、`MOBBY_RELEASE_STORE_PASSWORD`、`MOBBY_RELEASE_KEY_ALIAS`、`MOBBY_RELEASE_KEY_PASSWORD` 后构建。可用 `-Pmobby.versionName=0.1.0 -Pmobby.versionCode=1` 指定版本。不要将密钥、口令、APK、mapping 或本地配置提交到 Git。

### 已连接手机上的 Release 回归

使用现有 Android Studio 的 JDK 17 和本机 SDK 构建签名 Release，用 `adb -s <设备序列号> install --no-incremental -r app/build/outputs/apk/release/app-release.apk` 覆盖安装。签名必须与已安装版本一致；此流程不卸载应用、不清空数据。手机保持解锁且处于空闲状态后运行：

```sh
python3 runtime/android-release-smoke.py --serial <设备序列号> --repeat 5 --check-cli
```

脚本确认安装的是不可调试的 Release，冷启动后通过真实 UI 检查 Shell 重复执行、非零退出、停止执行，以及 Node、Pi、Codex、Claude Code、OpenCode 的版本命令。它不修改网关，不验证真实模型请求或本地模型推理；这些还需要单独验收。遇到失败会明确退出，不将错误或取消计为成功。

进程注册会在启动期间最多等待约一秒，读取 `/proc/<pid>/stat` 中的进程起始时间。JNI 在注册回调完成前不回收子进程，等待期间 PID 不能被复用；仍无法确认身份时保持失败，避免向未知进程发送信号。


2026-09-29 实机记录：使用长期发布密钥构建启用 R8 的非调试 Release，在 Xiaomi M2007J1SC / Android 13 上覆盖安装。91 项 runtime-android 单测通过；两轮冷启动下共 10 次 Shell 短命令、两次非零退出、两次取消、两次 Node 和四个 Agent CLI 启动检查全部通过。已修复启动期间进程身份暂时不可读导致 Shell 偶发失败的问题；真实模型请求和本地模型推理未在这次回归中验收。


发布安全与公开前的剩余检查见 [审查记录](public-release-audit.md)。Actions artifact 在公开仓库中可下载，只有加密后的 mapping.txt.age 可以上传；仓库中的接收者不是私钥。构建和签名隔离，不向 Gradle、npm 或项目构建脚本提供签名 secrets。
